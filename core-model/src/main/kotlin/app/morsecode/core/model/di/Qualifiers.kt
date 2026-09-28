package app.morsecode.core.model.di

import javax.inject.Qualifier

/*
 * Injection qualifiers shared by every module.
 *
 * They live here because both the pure JVM modules (core-transfer,
 * webshare-server) and the Android modules need the *same* annotation types:
 * Hilt matches providers to injection sites by annotation identity, so a
 * per-module copy would silently fail to bind. javax.inject is a compile-time
 * only dependency and adds no runtime cost on API 23.
 */

/**
 * Process-wide scope for work that must outlive the caller: log writes, history
 * rows, pruning, notification updates. Backed by a supervisor job so one failed
 * write cannot cancel the scope crash reporting depends on.
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
public annotation class ApplicationScope

/** Blocking file, socket and database work. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
public annotation class IoDispatcher

/** Main thread; injected rather than referenced so view models stay testable. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
public annotation class MainDispatcher

/** Short CPU-bound computations (checksums, sorting, formatting). */
@Retention(AnnotationRetention.BINARY)
@Qualifier
public annotation class DefaultDispatcher

/**
 * Dispatcher used by the transfer engine's byte pumps. Separate from [IoDispatcher]
 * so a saturating transfer cannot starve ordinary database writes.
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
public annotation class TransferDispatcher
