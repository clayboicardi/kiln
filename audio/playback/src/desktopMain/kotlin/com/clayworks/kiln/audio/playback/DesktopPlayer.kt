// DesktopPlayer: the desktop PlatformPlayer, which also reports its output path and notices
// (#37). The graph binds this one instance to both interfaces by type (no casts).

package com.clayworks.kiln.audio.playback

interface DesktopPlayer : PlatformPlayer, PlaybackDiagnostics
