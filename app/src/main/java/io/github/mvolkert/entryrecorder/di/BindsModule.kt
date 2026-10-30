package io.github.mvolkert.entryrecorder.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.data.alert.ServerRelayMotionAlertTransport
import io.github.mvolkert.entryrecorder.data.local.prefs.AndroidMotionAlertPushStore
import io.github.mvolkert.entryrecorder.data.local.prefs.MotionAlertPushStore
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlertPresenter
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.push.MotionAlertRegistrar
import io.github.mvolkert.entryrecorder.push.RemoteMotionAlertNotifier
import io.github.mvolkert.entryrecorder.push.UnifiedPushMotionAlertRegistrar
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

    @Binds
    @Singleton
    abstract fun bindMotionAlertTransport(impl: ServerRelayMotionAlertTransport): MotionAlertTransport

    // The receiving half of the motion alert (change `add-motion-notification-to-other-phone`): the push
    // service, the settings ViewModel and the boot receiver all reach the device through these three seams,
    // so none of them holds a Context of its own.
    @Binds
    @Singleton
    abstract fun bindMotionAlertPushStore(impl: AndroidMotionAlertPushStore): MotionAlertPushStore

    @Binds
    @Singleton
    abstract fun bindMotionAlertRegistrar(impl: UnifiedPushMotionAlertRegistrar): MotionAlertRegistrar

    @Binds
    @Singleton
    abstract fun bindRemoteMotionAlertPresenter(impl: RemoteMotionAlertNotifier): RemoteMotionAlertPresenter
}
