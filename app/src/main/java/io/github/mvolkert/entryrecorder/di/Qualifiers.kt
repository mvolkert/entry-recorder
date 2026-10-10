package io.github.mvolkert.entryrecorder.di

import javax.inject.Qualifier

/**
 * The IO dispatcher. Everything that blocks on a socket, a file or the database is launched on this,
 * injected rather than reached for `Dispatchers.IO` directly so a test can substitute a controlled one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** The computation dispatcher, for work that is CPU-bound rather than waiting on a resource. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * The process-lifetime scope, for work that has to outlive any single screen and stop with the app:
 * observing the enabled-camera set, or a settings row that must exist once the database is created.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Control-plane HTTP: the Python server API and the device REST endpoints. Deliberately not one client
 * for everything — the three profiles below share one connection pool and differ only in timeouts, so a
 * slow video download cannot inherit the 2 s budget a snapshot poll needs to stay inside its frame slot.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ControlClient

/** Snapshot polling: the tightest budget in the app, because one slot is 1/frameRate seconds wide. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SnapshotClient

/** Long-lived response bodies: video downloads and SSE streams, which no read timeout may cut short. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamClient
