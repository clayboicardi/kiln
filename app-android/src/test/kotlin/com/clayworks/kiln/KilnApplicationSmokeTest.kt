// Phase 5 scaffold smoke test for :app-android (legacy
// com.android.application plugin — NOT KMP). Closes review P1-3 by
// proving the host-side unit-test source set compiles and runs under
// Robolectric. Real KilnApplication graph + DI coverage lands in Phase 8.
//
// Note: :app-android uses src/test/kotlin/... (legacy convention) +
// testImplementation deps + Gradle task testDebugUnitTest. The KMP
// modules (:data:library, :audio:playback) use the AGP 9 KMP form:
// src/androidHostTest/kotlin/... + getByName("androidHostTest") {} +
// testAndroidHostTest task.

package com.clayworks.kiln

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KilnApplicationSmokeTest {
    @Test
    fun applicationContextLoads() {
        // Phase 5 scaffolding: this test exists to prove the legacy
        // src/test/kotlin/... source set + Robolectric runner compile and
        // execute. We do NOT cast to KilnApplication — Robolectric's default
        // Application stub (android.app.Application) is what gets returned
        // here, NOT the manifest-declared subclass, because KilnApplication's
        // onCreate constructs a real Media3ExoPlayer DI graph that needs
        // main-thread invariants Robolectric doesn't satisfy out of the box.
        // Real KilnApplication graph + DI coverage lands in Phase 8 with the
        // necessary @Config(application = ...) shim.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context)
    }
}
