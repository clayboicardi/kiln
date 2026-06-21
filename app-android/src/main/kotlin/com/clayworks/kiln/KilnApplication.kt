// KilnApplication — Android Application subclass that owns the kotlin-inject
// AndroidAppGraph for the process lifetime. Per Session 8 handoff H3.
//
// onCreate is invoked on the main thread, which satisfies the
// Media3ExoPlayerImpl constructor's main-thread requirement.

package com.clayworks.kiln

import android.app.Application
import com.clayworks.kiln.di.AndroidAppGraph
import com.clayworks.kiln.di.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KilnApplication : Application() {

    lateinit var graph: AndroidAppGraph
        private set

    /**
     * Process-lifetime scope for one-shot background work not tied to any
     * Activity — currently the scan-on-launch trigger, which must survive
     * config-change Activity recreation (lifecycleScope would cancel it). (codex #3)
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
