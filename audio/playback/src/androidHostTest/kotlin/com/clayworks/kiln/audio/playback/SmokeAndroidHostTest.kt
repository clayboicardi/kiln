// Phase 5 scaffold smoke test: verifies the androidHostTest source set
// is wired before the Media3 instantiation test lands in Phase 7.
//
// AGP 9 KMP renamed androidUnitTest → androidHostTest; the matching
// Gradle task is testAndroidHostTest (NOT testDebugUnitTest). See the
// convention plugin's withHostTest {} opt-in at
// build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts.

package com.clayworks.kiln.audio.playback

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmokeAndroidHostTest {
    @Test
    fun sourceSetIsWired() {
        // Trivial assertion — the real value of this test is its presence:
        // a passing testAndroidHostTest task proves the build can compile
        // and run Robolectric tests on this module. Real Media3 coverage
        // lands in Phase 7.
        assertTrue(true)
    }
}
