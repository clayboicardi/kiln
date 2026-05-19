// LibFlacLoader — the JNA-bound LibFlacBinding singleton factory.
// Calls NativeLibraryLoader.ensureLibFlacLoaded() before Native.load() to
// guarantee the vendored libFLAC.dll is on jna.library.path.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Native

internal object LibFlacLoader {

    @Volatile
    private var cached: LibFlacBinding? = null

    @Synchronized
    fun load(): LibFlacBinding {
        cached?.let { return it }
        NativeLibraryLoader.ensureLibFlacLoaded()
        val instance = Native.load("FLAC", LibFlacBinding::class.java)
        cached = instance
        return instance
    }
}
