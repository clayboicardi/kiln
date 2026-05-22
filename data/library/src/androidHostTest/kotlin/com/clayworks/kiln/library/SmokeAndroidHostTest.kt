// Verifies the androidHostTest source set is wired and can construct the
// bundled-SQLite test stack used by future Android-side test classes.
// Closes review P1-3 (Session 10 anti-pattern #2: 'do NOT defer androidTest
// source set past Phase 2a kickoff').
//
// AGP 9 KMP renamed androidUnitTest → androidHostTest (source set + test
// directory) and the matching Gradle task is testAndroidHostTest, NOT
// testDebugUnitTest. See build-logic/.../kiln.kmp.library.gradle.kts where
// withHostTest {} opts the convention plugin into host-side tests.

package com.clayworks.kiln.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class SmokeAndroidHostTest {
    @Test
    fun bundledSqliteSchemaCreatesCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val driver = AndroidSqliteDriver(
            schema = KilnDatabase.Schema,
            context = context,
            name = null,                                          // in-memory
            factory = RequerySQLiteOpenHelperFactory(),            // bundled SQLite (FTS5 guaranteed)
        )
        val db = KilnDatabase(driver)
        assertNotNull(db)
        driver.close()
    }
}
