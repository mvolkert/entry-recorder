package io.github.mvolkert.entryrecorder.util

import timber.log.Timber
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.video.H264Transcoder
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where a manually triggered export delivers the file. */
enum class RecordingExportKind {
    SHARE, GALLERY, FOLDER,

    /** Delivers the original captured file to the SAF export folder with no transcode, ignoring the Settings toggle. */
    RAW_FOLDER
}

/**
 * A server gallery row reduced to just what a download-and-deliver needs, so [ExportRunner] stays free of
 * UI types (the screen builds this from a `GalleryItem.Remote`). [videoAbsoluteUrl] is already resolved by
 * [io.github.mvolkert.entryrecorder.data.server.ServerMediaUrls].
 */
data class ServerRecordingSource(
    val id: Long,
    val videoAbsoluteUrl: String,
    val deviceName: String,
    val eventType: EventType,
)

/**
 * The sinks the caller wires up so a running export can report back: which stage of progress the strip
 * should show, how a batch is counting down, how far a server download has come, and the one-shot effects
 * (a snack-bar message, the share sheet, the celebration check). [ExportRunner] never touches a flow or a
 * channel itself — it only calls these — so the pipeline is testable without a ViewModel, and the ViewModel
 * keeps nothing but the state plumbing behind these callbacks.
 */
class ExportContext(
    val onExportProgress: (percent: Int, bodyRes: Int) -> Unit,
    val onBatchProgress: (done: Int, total: Int) -> Unit,
    val onServerDownload: (percent: Int) -> Unit,
    val onMessage: (text: String, short: Boolean) -> Unit,
    val onShare: (files: List<File>, metadata: ExportMetadata?) -> Unit,
    val onCelebrate: () -> Unit,
)

/**
 * The whole manual-export pipeline: transcode the raw MKV when the Settings toggle asks for it, then deliver
 * to SHARE / GALLERY / FOLDER / RAW_FOLDER for a single local clip, a multi-select batch, or a server row.
 *
 * Extracted from the Recordings ViewModel so the ViewModel keeps only state + intent plumbing (the busy
 * guard, the progress flows, the event channel) while every branch of the pipeline — including the
 * cancellation cleanup that drops a half-written encode — lives here, driven by the [ExportContext] sinks
 * rather than by Android or UI types. That lets the transcode → deliver → progress → celebrate flow be
 * exercised from an ordinary JVM test, and collapses what used to be three near-duplicate deliver bodies
 * (local, remote, batch) onto one [deliver].
 *
 * The methods run in the caller's coroutine; the caller owns launching, cancellation and clearing the
 * progress strip. Blocking gateway / transcode work hops to [ioDispatcher] itself.
 */
class ExportRunner @Inject constructor(
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    private val exportGateway: ExportGateway,
    private val transcoder: H264Transcoder,
    // MediaStore writes and the scratch folder the transcoder works in need paths, and the messages need
    // resource formatting: both arrive as [storageDirs] / [strings] rather than a Context, so this class
    // runs from a JVM test.
    private val storageDirs: AppStorageDirs,
    private val strings: UiStrings,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val tag = "ExportRunner"

    /**
     * Exports one local recording to [kind]'s destination, transcoding the raw MJPEG MKV to H.264 first when
     * the Settings toggle allows it so the result plays in other apps. Transcoding runs only here, on an
     * explicit user action, never during capture. SHARE opens the system sheet and persists nothing;
     * FOLDER writes one file per recording (the re-encode when it exists, otherwise the original), so
     * the lossless original stays in-app and the folder isn't cluttered with near-duplicates.
     * RAW_FOLDER always ships the original.
     */
    suspend fun exportLocal(recording: Recording, kind: RecordingExportKind, ctx: ExportContext) {
        // Read the settings when the action runs: which folder was picked and whether to re-encode
        // must not depend on whether anything happens to be subscribed to them.
        val settings = repository.getSettings()
        val src = File(recording.filePath)
        val goesToFolder = kind == RecordingExportKind.FOLDER || kind == RecordingExportKind.RAW_FOLDER
        val willTranscode = settings.transcodeOnExport && kind != RecordingExportKind.RAW_FOLDER &&
            src.extension.equals("mkv", ignoreCase = true)
        try {
            if (goesToFolder) {
                if (settings.exportFolderUri.isBlank()) {
                    say(ctx, R.string.recordings_toast_set_folder_first)
                    return
                }
                if (!src.exists()) {
                    say(ctx, R.string.recordings_toast_file_not_found, short = true)
                    return
                }
            }
            // Raw file straight to Share/Gallery: no progress strip, the deliver itself is on IO.
            if (!willTranscode && !goesToFolder) {
                deliver(src, recording.deviceName, recording.eventType, kind, settings.exportFolderUri, ctx, reportFolderProgress = true)
                return
            }
            if (willTranscode) ctx.onExportProgress(0, R.string.recordings_export_progress_body)
            val out = if (willTranscode) transcodeWithProgress(recording, ctx) else src
            deliver(out, recording.deviceName, recording.eventType, kind, settings.exportFolderUri, ctx, reportFolderProgress = true)
        } catch (e: CancellationException) {
            // The interrupted encode is this run's discard, not a file: the next export of the same
            // recording would otherwise hand out a truncated video that plays up to the cut.
            if (willTranscode) deletePartialTranscode(src)
            throw e
        } catch (e: Exception) {
            say(ctx, R.string.recordings_toast_export_failed, e.message ?: "")
        }
    }

    /**
     * Exports every checked recording with the same lazy-transcode policy as [exportLocal] and a k-of-n
     * progress. A per-file transcode failure falls back to that file's original instead of aborting the
     * batch; Share collects all files into one system sheet, Gallery and Folder report a final saved count.
     */
    suspend fun exportBatch(targets: List<Recording>, kind: RecordingExportKind, ctx: ExportContext) {
        if (targets.isEmpty()) {
            say(ctx, R.string.recordings_toast_nothing_selected)
            return
        }
        val settings = repository.getSettings()
        if ((kind == RecordingExportKind.FOLDER || kind == RecordingExportKind.RAW_FOLDER) &&
            settings.exportFolderUri.isBlank()
        ) {
            say(ctx, R.string.recordings_toast_set_folder_first)
            return
        }
        ctx.onBatchProgress(0, targets.size)
        val toShare = ArrayList<File>()
        var saved = 0
        var done = 0
        try {
            for (rec in targets) {
                val src = File(rec.filePath)
                val willTranscode = settings.transcodeOnExport &&
                    kind != RecordingExportKind.RAW_FOLDER &&
                    src.extension.equals("mkv", ignoreCase = true) && src.exists()
                val out = if (willTranscode) {
                    try {
                        transcoder.transcodeToH264(rec)
                    } catch (e: CancellationException) {
                        // Cancellation is not a failed encode: the fallback below would hand the batch
                        // a truncated file and keep going. Drop the unfinished output and unwind.
                        deletePartialTranscode(src)
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(tag).w(e, "Batch transcode failed for ${rec.id}, exporting raw")
                        src
                    }
                } else src

                when (kind) {
                    RecordingExportKind.SHARE -> toShare.add(out)
                    RecordingExportKind.GALLERY ->
                        withContext(ioDispatcher) {
                            if (exportGateway.saveFileToGallery(out, rec.deviceName, onMessage = { ctx.onMessage(it, false) })) saved++
                        }

                    RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                        // One file per recording (H.264 when produced, else original) — as single-item.
                        withContext(ioDispatcher) {
                            if (exportGateway.saveFileToSafFolder(settings.exportFolderUri, out, out.name)) saved++
                        }
                    }
                }
                ctx.onBatchProgress(++done, targets.size)
            }

            when (kind) {
                RecordingExportKind.SHARE ->
                    if (toShare.isNotEmpty()) ctx.onShare(toShare, null)

                RecordingExportKind.GALLERY -> {
                    sayPlural(ctx, R.plurals.recordings_toast_saved_gallery, targets.size, saved, targets.size)
                    if (saved > 0) ctx.onCelebrate()
                }

                RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                    sayPlural(ctx, R.plurals.recordings_toast_exported_folder, targets.size, saved, targets.size)
                    if (saved > 0) ctx.onCelebrate()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(ctx, R.string.recordings_toast_export_failed, e.message ?: "")
        }
    }

    /**
     * Downloads one server recording into the app cache, then delivers it like a local export. The server
     * file is already H.264, so there is no transcode step; SHARE/GALLERY/FOLDER just move the downloaded
     * copy; the download crosses the network, so it runs under the progress strip and can be cancelled.
     */
    suspend fun exportServer(source: ServerRecordingSource, kind: RecordingExportKind, ctx: ExportContext) {
        val settings = repository.getSettings()
        if (kind == RecordingExportKind.FOLDER && settings.exportFolderUri.isBlank()) {
            say(ctx, R.string.recordings_toast_set_folder_first)
            return
        }
        ctx.onServerDownload(0)
        val result = serverClient.downloadVideo(
            absoluteUrl = source.videoAbsoluteUrl,
            targetDir = storageDirs.transcodeScratchDir,
            baseName = "server_rec_${source.id}",
        ) { percent -> ctx.onServerDownload(percent) }
        result
            .onSuccess { file ->
                deliver(file, source.deviceName, source.eventType, kind, settings.exportFolderUri, ctx, reportFolderProgress = false)
            }
            .onFailure { error -> say(ctx, R.string.recordings_toast_download_failed, error.message ?: "") }
    }

    /**
     * The one delivery step shared by every kind and source. [reportFolderProgress] is true for a local
     * single export (the folder copy is worth a percent line) and false for a server row (its download
     * already drove the strip). [deviceName] / [eventType] come from a Recording or a [ServerRecordingSource];
     * only the SHARE label needs them.
     */
    private suspend fun deliver(
        file: File,
        deviceName: String,
        eventType: EventType,
        kind: RecordingExportKind,
        exportFolderUri: String,
        ctx: ExportContext,
        reportFolderProgress: Boolean,
    ) {
        when (kind) {
            RecordingExportKind.SHARE ->
                ctx.onShare(listOf(file), ExportMetadata(deviceName, eventType))

            RecordingExportKind.GALLERY -> if (saveSingleToGallery(file, deviceName, ctx)) ctx.onCelebrate()

            RecordingExportKind.FOLDER, RecordingExportKind.RAW_FOLDER -> {
                val ok = withContext(ioDispatcher) {
                    if (reportFolderProgress) {
                        exportGateway.saveFileToSafFolder(exportFolderUri, file, file.name) { percent ->
                            ctx.onExportProgress(percent, R.string.recordings_copy_progress_body)
                        }
                    } else {
                        exportGateway.saveFileToSafFolder(exportFolderUri, file, file.name)
                    }
                }
                if (ok) {
                    say(ctx, R.string.recordings_toast_exported_to, exportGateway.safFolderDisplayName(exportFolderUri))
                    ctx.onCelebrate()
                } else {
                    say(ctx, R.string.recordings_toast_export_folder_failed)
                }
            }
        }
    }

    private suspend fun transcodeWithProgress(recording: Recording, ctx: ExportContext): File =
        transcoder.transcodeToH264(recording) { done, total ->
            ctx.onExportProgress(
                if (total > 0) done * 100 / total else 0,
                R.string.recordings_export_progress_body,
            )
        }

    /** Drops the half-written H.264 a cancelled transcode leaves in the scratch folder. */
    private fun deletePartialTranscode(source: File) {
        transcoder.outputFor(source).delete()
    }

    /**
     * Saves one finished file into the public gallery. [ExportGateway]'s own success line is swallowed and
     * said here instead, exactly once and next to the celebration check: with the system's animation scale
     * at zero the check returns immediately, and a save that only flickered would leave no record that it
     * happened. Every other outcome - a vanished source, a rejected MediaStore insert, the pre-Q write
     * that reports the path it chose - still reaches the snack bar as it comes.
     */
    private suspend fun saveSingleToGallery(source: File, deviceName: String, ctx: ExportContext): Boolean {
        val successLine = strings(R.string.export_saved_gallery)
        var reportedSomething = false
        val saved = withContext(ioDispatcher) {
            exportGateway.saveFileToGallery(source, deviceName, onMessage = { text ->
                if (text != successLine) {
                    reportedSomething = true
                    ctx.onMessage(text, false)
                }
            })
        }
        if (saved && !reportedSomething) ctx.onMessage(successLine, false)
        return saved
    }

    private fun say(ctx: ExportContext, @StringRes id: Int, vararg args: Any, short: Boolean = false) {
        ctx.onMessage(strings(id, *args), short)
    }

    private fun sayPlural(ctx: ExportContext, @PluralsRes id: Int, quantity: Int, vararg args: Any) {
        ctx.onMessage(strings.quantity(id, quantity, *args), false)
    }
}
