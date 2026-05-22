# kiln Style & Review Guide

Tells Gemini Code Assist what matters when reviewing changes to this Android Kotlin music player.

## Kotlin Idioms

- Coroutine discipline: `CoroutineScope` tied to a lifecycle owner (Activity, Fragment, ViewModel) — no scope leaked beyond owner.
- Structured concurrency: every `launch` lives inside a scope that will be cancelled.
- Prefer `Flow` over `LiveData` for new code. Keep `LiveData` only where required by existing observers.
- Sealed classes for finite state. No bare `Boolean` state where semantics matter.
- Null safety: avoid `!!` outside test code.

## MediaSession Lifecycle

- `MediaSessionCompat` (or `MediaSession` for Media3) creation, activation, deactivation, release ordered correctly.
- `setActive(false)` and `release()` always paired with creation.
- Callbacks debounced where rapid fire is expected.
- Metadata updates batched, not per-frame.

## Android Permissions

- Runtime permission flow follows the platform pattern: rationale → request → handle result.
- No permissions declared in the manifest that the app does not exercise.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34+) handled where applicable.
- Storage access uses Storage Access Framework (SAF) on modern Android; no broad `READ_EXTERNAL_STORAGE` requests unless legacy fallback.

## Intent + Service Security

- Explicit intents for sensitive operations. No implicit intents handling private data.
- `exported="false"` on internal services and receivers unless external invocation is intended.
- Pending intents use `FLAG_IMMUTABLE` on API 23+.

## Battery + Background Efficiency

- No wakelocks held outside of active playback.
- `WorkManager` for deferrable background work, not `Service` with sticky start.
- Doze + App Standby aware.

## Memory Leaks

- Activity / Fragment references not retained beyond their lifecycle by long-lived objects.
- BroadcastReceivers unregistered in matching lifecycle callback.
- `ContextCompat.getColor` and similar — no leak of full Activity context where Application context suffices.

## Material Design 3

- Token-based theming, no hard-coded colors.
- Dynamic color opt-in handled correctly.
- Density-independent units across layouts.

## Build System

- Gradle KTS conventions used throughout.
- Version Catalog (`libs.versions.toml`) for dependency coordinates.
- R8/ProGuard rules: every kept class justified by a comment.
- Build variants: debug vs release symmetry on signing config and shrinking flags.

## Tests

- ViewModel tests use `Dispatchers.setMain` + `runTest`.
- No `Thread.sleep` in tests.
- Robolectric only where instrumentation does not cover.

## Non-Goals

- Architecture-style debates on existing locked decisions (MVVM, repository pattern).
- Naming conventions for variables in existing files.
