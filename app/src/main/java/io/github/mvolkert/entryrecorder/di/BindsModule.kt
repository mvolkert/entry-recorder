package io.github.mvolkert.entryrecorder.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.sip.SipRegistrar
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallLauncher
import io.github.mvolkert.entryrecorder.video.ExportTranscoder
import io.github.mvolkert.entryrecorder.video.H264Transcoder
import io.github.mvolkert.entryrecorder.video.RecordingController
import io.github.mvolkert.entryrecorder.video.RtspStreamRecorder
import javax.inject.Singleton

/**
 * The device-backed classes behind the interfaces their ViewModel consumers use: each interface has
 * exactly one implementation — the class that talks to Media3, OkHttp, Linphone or MediaCodec — and
 * every binding delegates to that class's own `@Singleton` scope, so the graph still holds one of each.
 *
 * A separate abstract module because `@Binds` needs one; [AppModule] keeps everything that is built by
 * a provider method.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {

    @Binds
    @Singleton
    abstract fun bindRecordingController(impl: RtspStreamRecorder): RecordingController

    @Binds
    @Singleton
    abstract fun bindServerClient(impl: ServerRecordingClient): ServerClient

    @Binds
    @Singleton
    abstract fun bindH264Transcoder(impl: ExportTranscoder): H264Transcoder

    @Binds
    @Singleton
    abstract fun bindSipRegistrar(impl: SipCallManager): SipRegistrar

    @Binds
    @Singleton
    abstract fun bindCallScreenLauncher(impl: IncomingCallLauncher): CallScreenLauncher
}
