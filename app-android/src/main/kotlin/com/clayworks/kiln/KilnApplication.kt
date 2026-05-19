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
    }
}
