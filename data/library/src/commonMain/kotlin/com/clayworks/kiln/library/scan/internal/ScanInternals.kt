// Internal scanner helpers shared between platform-specific scanners
// (JvmFilesystemScanner on desktop, AndroidMediaStoreScanner on Android).
// Module-internal — not part of the LibraryScanner public surface.

package com.clayworks.kiln.library.scan.internal

import app.cash.sqldelight.db.SqlDriver
import com.clayworks.kiln.data.library.db.KilnDatabase

/**
 * Strip a leading "The "/"An "/"A " article and lowercase. Fallback when the
 * file tag doesn't carry a MusicBrainz-style sort-name field.
 */
internal fun toSortName(name: String): String {
    val trimmed = name.trim()
    val withoutArticle = when {
        trimmed.startsWith("The ", ignoreCase = true) -> trimmed.substring(4)
        trimmed.startsWith("An ", ignoreCase = true) -> trimmed.substring(3)
        trimmed.startsWith("A ", ignoreCase = true) -> trimmed.substring(2)
        else -> trimmed
    }
    return withoutArticle.lowercase()
}

/**
 * Parse jaudiotagger-style channel descriptors into a numeric channel count.
 * Falls back to 2 (stereo) for unparseable or unknown values.
 */
internal fun parseChannels(channels: String?): Long = when {
    channels == null -> 2L
    channels.equals("Mono", ignoreCase = true) -> 1L
    channels.equals("Stereo", ignoreCase = true) -> 2L
    channels.contains("5.1") -> 6L
    channels.contains("7.1") -> 8L
    else -> channels.trim().toLongOrNull() ?: 2L
}

/**
 * Parse the leading integer from a string like "5/12", "5", " 5 ". Used for
 * track number, disc number, BPM, year — formats vary across tag flavors.
 */
internal fun String.parseLeadingLong(): Long? =
    takeIf { it.isNotBlank() }?.substringBefore('/')?.trim()?.toLongOrNull()

/**
 * Parse a ReplayGain dB value from its Vorbis-comment string form ("-6.42 dB",
 * "-6.42dB", "-6.42"). Strips the optional "dB" suffix then parses Double.
 */
internal fun String.parseReplayGainDb(): Double? =
    takeIf { it.isNotBlank() }
        ?.replace("dB", "", ignoreCase = true)
        ?.trim()
        ?.toDoubleOrNull()

/**
 * Bulk-rebuild the contentless track_search FTS5 index from the live track
 * table. Called at the tail of every scan pass.
 *
 * Atomicity: the 'delete-all' + per-row INSERTs all run inside a single
 * [db.transaction] block. Concurrent readers therefore see EITHER the pre-scan
 * FTS state OR the post-scan FTS state — never an empty index during the
 * rebuild window, and a process kill mid-rebuild rolls back to the pre-scan
 * state instead of leaving FTS permanently empty.
 *
 * The 'delete-all' control command is invoked via raw [SqlDriver.execute] —
 * SQLDelight's .sq parser doesn't accept FTS5 control syntax (Session 6
 * discovery #2). SQLDelight's sticky-connection transaction model means the
 * raw `driver.execute` participates in the enclosing `db.transaction { }`.
 *
 * Performance: 40k rows ~ a few hundred ms on SQLite for the bulk insert pass.
 */
internal fun rebuildFtsIndex(db: KilnDatabase, driver: SqlDriver) {
    val rows = db.trackQueries.selectAllForFtsRebuild().executeAsList()
    db.transaction {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO track_search(track_search) VALUES('delete-all')",
            parameters = 0,
        )
        rows.forEach { row ->
            db.track_searchQueries.insertSearchIndex(
                rowid = row.track_id,
                title = row.title,
                album_name = row.album_name,
                artist_name = row.artist_name,
                album_artist_name = row.album_artist_name,
            )
        }
    }
}
