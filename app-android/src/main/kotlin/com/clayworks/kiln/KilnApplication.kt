// KilnApplication — Android Application subclass that owns the kotlin-inject
// AndroidAppGraph for the process lifetime. Per Session 8 handoff H3.
//
// onCreate is invoked on the main thread, which satisfies the
// Media3ExoPlayerImpl constructor's main-thread requirement.

package com.clayworks.kiln

import android.app.Application
import com.clayworks.kiln.di.AndroidAppGraph
import com.clayworks.kiln.di.create

class KilnApplication : Application() {

    lateinit var graph: AndroidAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AndroidAppGraph::class.create(applicationContext)
        // Eagerly force Media3ExoPlayer instantiation. kotlin-inject's @Singleton
        // providers are LAZY — without this read, the player would be created on
        // first access (typically PlayFirstTrackScreen's collectAsState during
        // Compose composition). Compose composition runs on the main thread so
        // in practice this works, but ExoPlayer's single-thread-access invariant
        // means ANY other access path (background service, instrumented test,
        // future code) constructing the player off-main-thread would crash.
        // Application.onCreate is guaranteed main-thread; reading graph.player
        // here forces construction in the safe context.
        graph.player
    }
}
