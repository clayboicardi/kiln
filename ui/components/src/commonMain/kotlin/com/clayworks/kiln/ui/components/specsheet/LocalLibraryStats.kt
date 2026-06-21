// CompositionLocals that supply non-serializable runtime dependencies to the
// Voyager Screens hosted by the inner Navigators. Voyager's `Screen` extends
// `java.io.Serializable` on Android; the Navigator saves its back-stack across
// process death / config change by serializing the Screen objects. A Screen
// that holds a non-serializable dependency (LibraryStatsSource → KilnDatabase +
// CoroutineDispatcher, or PlatformPlayer → native ExoPlayer/JavaSound handles)
// throws NotSerializableException on save. The fix: Screens stay
// serializable-only (they hold just Strings) and pull their dependencies from
// these CompositionLocals at composition time.
//
// Provided once at each app root (MainActivity / Main.kt) around the
// KilnHomeScreen call; the values flow down through TabNavigator → tabs →
// inner Navigator → Screen.

package com.clayworks.kiln.ui.components.specsheet

import androidx.compose.runtime.staticCompositionLocalOf
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.LibraryStatsSource

/**
 * Supplies the [LibraryStatsSource] to [SpecSheetScreen]. `static` because the
 * value is stable for the process lifetime (one graph instance) — a static
 * local skips the read-tracking machinery a dynamic local would impose.
 */
val LocalLibraryStats = staticCompositionLocalOf<LibraryStatsSource> {
    error("LocalLibraryStats not provided")
}

/**
 * Supplies the [PlatformPlayer] to the Now Playing Screens (NowPlayingHomeScreen).
 * Lives here alongside [LocalLibraryStats] so the two app-root providers sit in
 * one import; same process-lifetime stability rationale.
 */
val LocalPlayer = staticCompositionLocalOf<PlatformPlayer> {
    error("LocalPlayer not provided")
}
