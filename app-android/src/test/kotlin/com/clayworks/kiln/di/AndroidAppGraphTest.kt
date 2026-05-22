// Android DI graph wiring test. Verifies all @Provides chains resolve
// without exceptions when create() is called from a Robolectric context.
// Closes review P2-10 + provides regression coverage for Session 10
// Polish-1 (KilnApplication.onCreate eager-init of graph.player).
//
// Robolectric runs @Test methods on the main thread by default (the
// process's only thread under the host JVM), which satisfies the
// ExoPlayer single-thread-access invariant Media3ExoPlayerImpl's
// constructor relies on. No @LooperMode override needed — confirmed
// empirically: see Phase 8 Task 8.2 of pre-Phase-2a stabilization plan.
//
// Note: this test calls AndroidAppGraph::class.create(context) directly
// rather than going through KilnApplication.onCreate. Robolectric's
// default Application stub is android.app.Application (NOT manifest-
// declared KilnApplication), so we exercise the graph's eager-init
// contract here without spinning up the real Application — see also
// KilnApplicationSmokeTest's comment block on the same constraint.

package com.clayworks.kiln.di

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AndroidAppGraphTest {

    @Test
    fun graph_eager_inits_main_thread_required_providers() {
        // Regression for review P2-10 + Session 10 Polish-1: kotlin-inject
        // @Provides are lazy by default. The Media3ExoPlayer provider
        // requires construction on the main thread (ExoPlayer's
        // single-thread-access rule). KilnApplication.onCreate eagerly
        // touches `graph.player` to materialize the lazy provider on the
        // main thread. This test verifies the eager-init path doesn't
        // throw.

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val graph = AndroidAppGraph::class.create(context)
        assertNotNull(graph.musicSource)
        assertNotNull(graph.scanner)
        assertNotNull(graph.player)   // touches the eager-init path
    }

    @Test
    fun graph_exposes_settings_repository() {
        // Track A surface contract: graph.settings resolves to the singleton
        // SettingsRepository instance backed by the SQLDelight settings table.
        // Mirrors DesktopAppGraphTest's pattern; pins the runtime binding so
        // a future graph regression surfaces as a test failure (KSP would
        // catch missing providers at compile time, but the assertion also
        // pins that the interface→impl wiring resolves at runtime).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val graph = AndroidAppGraph::class.create(context)
        assertNotNull(graph.settings)
    }
}
