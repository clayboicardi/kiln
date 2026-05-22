# Phase 2a Track B — Android SAF Folder Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Track A's Android Toast stub with a real SAF (Storage Access Framework) folder picker. User taps "Add Folder" → system file picker opens → user picks any folder → URI persisted via `takePersistableUriPermission` → written to `settings.scanFolders` → scanner walks the picked tree alongside MediaStore on next scan. Closes the Session 10 H8 Pixel-discovery gap (MediaStore-canonical-only library).

**Architecture:**
- **SAF launcher:** `ActivityResultContracts.OpenDocumentTree` (Compose-friendly via `rememberLauncherForActivityResult`). Intent extra `EXTRA_INITIAL_URI` skipped — let the system default to its conventional starting point. On result: call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` to keep the grant across cold restarts.
- **Persistence:** the picked tree URI (`content://com.android.externalstorage.documents/tree/...`) is `.toString()`-serialized and appended to the existing `settings.scanFolders` list via `SettingsRepository.setScanFolders(...)`. Track A's repo is platform-neutral — the list field is `List<String>`, so URIs and filesystem paths share the same column; consumers parse per-platform.
- **Scanner extension:** `AndroidMediaStoreScanner` gets a `safTreeUrisFlow: Flow<List<String>>` constructor parameter. After the existing MediaStore pass completes, a new `scanSafTrees` pass walks each tree URI via `DocumentsContract.buildChildDocumentsUriUsingTree` recursively, opens each audio file via `contentResolver.openFileDescriptor` + `MediaMetadataRetriever`, and upserts into the same `track` table. File-path column carries the content URI string — schema unchanged.
- **Tag reading:** `MediaMetadataRetriever` for SAF files (no jaudiotagger on Android — that's a desktop dep). Reads title/artist/album/year/duration/bitrate/genre + computes sample-rate/channels from the MediaFormat as best-effort.
- **Dedup discipline:** if a SAF tree contains files that MediaStore already scanned (e.g., user picks the Music canonical dir), both rows land — distinct `file_path` strings, no UNIQUE collision. Track B accepts this; documented in CLAUDE.md as a known edge requiring a future Phase 2a follow-up dedup pass keyed on `file_size_bytes + mtime + display_name`.

**Tech Stack:** Android SAF (`androidx.documentfile` reference avoided — DocumentFile is convenient but slower than raw `DocumentsContract` queries for our walk-all-children pattern), `androidx.activity.compose` for the picker contract, `MediaMetadataRetriever` for tag reading, kotlinx-coroutines for the suspending walk.

---

## Pre-flight (must hold true at start)

- Track A merged or branched-from cleanly. Local branch: `phase-2a-track-b-saf-folder-picker` based on current `main` at `aab6627`.
- `kiln-verify-build` PASS (104+1 tests post-Track-A).
- Git tree clean.

## End-state (must hold true at completion)

- `:app-android:assembleDebug` + `:app-android:testDebugUnitTest` PASS
- `:data:library:testAndroidHostTest` PASS with new SAF walker tests
- Canonical verify-build PASS with all touched module tests counted
- Pixel 7 manual smoke (delegated to Clay): tap gear → SettingsScreen → "Add Folder" → SAF picker opens (no Toast) → pick `/sdcard/Music` (or any folder via picker) → folder URI appears in scan list → tap "Scan Library" on main screen → SAF tree files appear alongside MediaStore entries → cold-kill app → relaunch → folder still listed (persistent URI permission survived)
- CLAUDE.md gotcha entries added for: (a) SAF dedup acceptance, (b) `MediaMetadataRetriever` content-URI ParcelFileDescriptor lifecycle, (c) `takePersistableUriPermission` flag pairing requirement

---

## File Structure

**New files:**

| Path | Responsibility |
|------|---------------|
| `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTreeWalker.kt` | Pure walker: given a tree URI + ContentResolver, recursively enumerates `(documentUri, displayName, mimeType, size, lastModified)` for audio MIME types. Sequence-based for lazy iteration; no DB writes here. |
| `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTagReader.kt` | Given a content URI + ContentResolver, opens MediaMetadataRetriever and extracts `TrackTags`-shaped data. Catches retriever exceptions and returns null tags for unreadable files. |
| `data/library/src/androidHostTest/kotlin/com/clayworks/kiln/library/scan/SafTreeWalkerTest.kt` | Robolectric ShadowContentResolver-backed tests for tree walking. Verifies recursive enumeration + audio-MIME filtering. |
| `app-android/src/main/kotlin/com/clayworks/kiln/saf/SafFolderPicker.kt` | Composable hook exposing `rememberSafFolderPicker(onPicked: (String) -> Unit)`. Wraps `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree)` + `takePersistableUriPermission` call on success. Returns a `() -> Unit` launcher. |

**Modified files:**

| Path | Change |
|------|--------|
| `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` | Constructor adds `safTreeUrisFlow: Flow<List<String>>` parameter. `runScan` becomes `suspend`. After MediaStore pass, calls new `scanSafTrees(scanStartedMs)` method that iterates `safTreeUrisFlow.first()`, walks each via `SafTreeWalker`, reads tags via `SafTagReader`, upserts using the existing `upsertArtist`/`upsertAlbum`/`db.trackQueries.insert`-or-`updateForRescan` chain. |
| `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | `mediaStoreScanner` provider gains a `settings: SettingsRepository` parameter; derives `safTreeUrisFlow` via `settings.scanFolders` (no map needed — already `Flow<List<String>>` of URI strings). |
| `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` | `AndroidSettingsRoute`: replace the Toast stub in `onPickFolder` with a SAF picker invocation. Use `rememberSafFolderPicker(onPicked = { uri -> coroutineScope.launch { graph.settings.setScanFolders(scanFolders + uri) } })`. |
| `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt` | Update DI test if the scanner-provider param list change breaks the existing assertion (likely just verifying graph.scanner still resolves). |
| `CLAUDE.md` | Append three new gotcha entries to "Build/Dep Gotchas". |

---

## Tasks (one subagent dispatch per task)

### Task 1: `SafFolderPicker` composable in `:app-android`

**Why first:** isolates the Android-side picker contract into a testable, reusable composable hook BEFORE wiring into MainActivity. Doesn't change Track A's Toast stub yet — that swap happens in Task 5 after the rest of the chain is in place.

**Files:**
- Create: `app-android/src/main/kotlin/com/clayworks/kiln/saf/SafFolderPicker.kt`

**Steps:**

- [ ] **Step 1: Create the picker composable**

Path: `app-android/src/main/kotlin/com/clayworks/kiln/saf/SafFolderPicker.kt`

```kotlin
// SAF folder picker — Compose hook that wraps ActivityResultContracts.OpenDocumentTree
// and persists the URI permission so the grant survives cold restarts.
//
// Returns a () -> Unit "launch" function. Wire to the SettingsScreen's
// onPickFolder callback. On a successful pick, the URI is passed to onPicked
// AFTER takePersistableUriPermission has been called — ordering matters:
// once the activity result is dispatched, the granted permission is implicit
// and can be made persistent for at most a few hundred ms. Calling later may
// fail silently.

package com.clayworks.kiln.saf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

private val log = Logger.withTag("SafFolderPicker")

/**
 * Composable hook for the Android Storage Access Framework folder picker.
 *
 * Caller invokes the returned launcher when the user taps "Add Folder".
 * On a successful pick:
 *   1. takePersistableUriPermission is invoked with FLAG_GRANT_READ_URI_PERMISSION
 *      so the URI grant survives process death (matches Track B's spec).
 *   2. onPicked is invoked with the URI as a String (the same form
 *      SettingsRepository.scanFolders stores).
 *
 * Cancel or any other failure path is silent — the launcher is a no-op
 * fire-and-forget; no UI state to clean up.
 */
@Composable
fun rememberSafFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            log.w(e) { "takePersistableUriPermission failed for $uri" }
            return@rememberLauncherForActivityResult
        }
        onPicked(uri.toString())
    }
    return remember(launcher) { { launcher.launch(/* input = */ null) } }
}
```

- [ ] **Step 2: Build :app-android:assembleDebug to confirm it compiles**

```
pwsh -c "./gradlew :app-android:assembleDebug"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run :app-android:testDebugUnitTest**

```
pwsh -c "./gradlew :app-android:testDebugUnitTest"
```
Expected: BUILD SUCCESSFUL with the existing 3 tests passing (no new tests added — Compose hook is hard to unit-test without instrumented tests; Pixel 7 smoke is the verification).

- [ ] **Step 4: Commit**

```bash
git add app-android/src/main/kotlin/com/clayworks/kiln/saf/SafFolderPicker.kt
git commit -m "$(cat <<'EOF'
feat(app-android): SAF folder picker composable hook — Phase 2a Track B

Wraps ActivityResultContracts.OpenDocumentTree in a Compose-friendly
rememberSafFolderPicker(onPicked) hook. Persists the URI grant via
takePersistableUriPermission(FLAG_GRANT_READ_URI_PERMISSION) before
invoking the onPicked callback — ordering matters because the implicit
grant from the activity result expires within a few hundred ms.

Hook isn't wired to MainActivity yet (Track A's Toast stub still in
place). Task 5 of the Track B plan swaps the stub once the scanner-side
SAF walker (Tasks 2-4) is functional. Splitting picker creation from
the wiring keeps each commit reviewable.

Phase 2a Track B Task 1 of 7.
EOF
)"
```

---

### Task 2: `SafTreeWalker` — recursive document enumeration

**Why standalone:** the walker is a pure function over `(ContentResolver, treeUri)` → `Sequence<SafDocument>`. Splitting from scanner integration makes it independently testable via Robolectric's ShadowContentResolver.

**Files:**
- Create: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTreeWalker.kt`
- Create: `data/library/src/androidHostTest/kotlin/com/clayworks/kiln/library/scan/SafTreeWalkerTest.kt`

**Steps:**

- [ ] **Step 1: Write the failing tests FIRST (TDD)**

Path: `data/library/src/androidHostTest/kotlin/com/clayworks/kiln/library/scan/SafTreeWalkerTest.kt`

```kotlin
package com.clayworks.kiln.library.scan

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.clayworks.kiln.library.scan.internal.SafTreeWalker
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafTreeWalkerTest {

    private lateinit var contentResolver: ContentResolver
    private val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        contentResolver = app.contentResolver
    }

    @Test
    fun walks_empty_tree() {
        // No documents registered → empty walk
        val results = SafTreeWalker.walk(contentResolver, treeUri).toList()
        assertEquals(emptyList(), results)
    }

    @Test
    fun walks_single_audio_file() {
        registerDocuments(
            parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            ),
            children = listOf(
                FakeDoc(
                    documentId = "primary:Music/song.flac",
                    displayName = "song.flac",
                    mimeType = "audio/flac",
                    size = 12_345_678L,
                    lastModified = 1_716_336_000_000L,
                ),
            ),
        )

        val results = SafTreeWalker.walk(contentResolver, treeUri).toList()
        assertEquals(1, results.size)
        val doc = results.first()
        assertEquals("song.flac", doc.displayName)
        assertEquals("audio/flac", doc.mimeType)
        assertEquals(12_345_678L, doc.size)
        assertEquals(1_716_336_000_000L, doc.lastModified)
    }

    @Test
    fun filters_non_audio_mime_types() {
        registerDocuments(
            parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            ),
            children = listOf(
                FakeDoc("primary:Music/song.flac", "song.flac", "audio/flac", 100L, 0L),
                FakeDoc("primary:Music/photo.jpg", "photo.jpg", "image/jpeg", 200L, 0L),
                FakeDoc("primary:Music/notes.txt", "notes.txt", "text/plain", 300L, 0L),
            ),
        )

        val results = SafTreeWalker.walk(contentResolver, treeUri).toList()
        assertEquals(1, results.size)
        assertEquals("song.flac", results.first().displayName)
    }

    @Test
    fun recurses_into_subdirectories() {
        val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val subdirUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            "primary:Music/Subdir",
        )
        registerDocuments(
            parentUri = parentUri,
            children = listOf(
                FakeDoc("primary:Music/Subdir", "Subdir", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L),
                FakeDoc("primary:Music/top.mp3", "top.mp3", "audio/mpeg", 100L, 0L),
            ),
        )
        registerDocuments(
            parentUri = subdirUri,
            children = listOf(
                FakeDoc("primary:Music/Subdir/nested.flac", "nested.flac", "audio/flac", 200L, 0L),
            ),
        )

        val results = SafTreeWalker.walk(contentResolver, treeUri).toList()
        val displayNames = results.map { it.displayName }.toSet()
        assertEquals(setOf("top.mp3", "nested.flac"), displayNames)
    }

    @Test
    fun handles_extension_based_audio_detection_when_mime_unknown() {
        registerDocuments(
            parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            ),
            children = listOf(
                // MIME reported as application/octet-stream but extension is .flac
                FakeDoc("primary:Music/x.flac", "x.flac", "application/octet-stream", 100L, 0L),
            ),
        )

        val results = SafTreeWalker.walk(contentResolver, treeUri).toList()
        assertEquals(1, results.size)
        assertEquals("x.flac", results.first().displayName)
    }

    // ---------- test helpers ----------

    private data class FakeDoc(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
    )

    private fun registerDocuments(parentUri: Uri, children: List<FakeDoc>) {
        val shadow = org.robolectric.Shadows.shadowOf(contentResolver)
        val cursor = MatrixCursor(arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ))
        for (doc in children) {
            cursor.addRow(arrayOf(doc.documentId, doc.displayName, doc.mimeType, doc.size, doc.lastModified))
        }
        shadow.setCursor(parentUri, cursor)
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure (SafTreeWalker doesn't exist yet)**

```
pwsh -c "./gradlew :data:library:testAndroidHostTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: SafTreeWalker / SafTreeWalker.walk`. Failing-test gate.

- [ ] **Step 3: Implement SafTreeWalker**

Path: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTreeWalker.kt`

```kotlin
// SafTreeWalker — pure walker over a Storage Access Framework tree URI.
// Recursively enumerates child documents, filters for audio MIME types or
// well-known audio file extensions, and yields a lazy Sequence<SafDocument>.
//
// Phase 2a Track B Task 2 — sibling to AndroidMediaStoreScanner. Scanner
// composition: MediaStore pass first (system-wide), then SAF tree pass for
// each user-picked tree URI from settings.scanFolders. Files in trees that
// MediaStore already knows about will land twice with distinct file_path
// values — acceptable for Track B; dedup is a Phase 2a follow-up.

package com.clayworks.kiln.library.scan.internal

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import co.touchlab.kermit.Logger

private val log = Logger.withTag("SafTreeWalker")

private val AUDIO_MIME_PREFIXES = setOf("audio/")
private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "wav", "alac", "ogg", "opus", "m4a", "aac")

data class SafDocument(
    val documentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
)

object SafTreeWalker {

    /**
     * Walks the SAF tree rooted at [treeUri] recursively, yielding documents
     * whose MIME type starts with "audio/" OR whose display name extension is
     * in the well-known audio set. Directories are recursed; non-audio leaves
     * skipped.
     *
     * Returns a lazy Sequence — large trees stream without materializing the
     * whole listing in memory. Each cursor is closed as the recursion unwinds.
     */
    fun walk(contentResolver: ContentResolver, treeUri: Uri): Sequence<SafDocument> = sequence {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        yieldAll(walkChildren(contentResolver, treeUri, rootDocId))
    }

    private fun walkChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
    ): Sequence<SafDocument> = sequence {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val cursor: Cursor = try {
            contentResolver.query(childrenUri, projection, null, null, null) ?: return@sequence
        } catch (e: SecurityException) {
            log.w(e) { "SAF tree walk denied: $childrenUri (permission revoked?)" }
            return@sequence
        }

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = c.getString(idIdx)
                val name = c.getString(nameIdx) ?: continue
                val mime = c.getString(mimeIdx) ?: ""
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val mtime = if (mtimeIdx >= 0 && !c.isNull(mtimeIdx)) c.getLong(mtimeIdx) else 0L

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    yieldAll(walkChildren(contentResolver, treeUri, docId))
                } else if (isAudioDocument(name, mime)) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    yield(SafDocument(
                        documentUri = documentUri,
                        displayName = name,
                        mimeType = mime,
                        size = size,
                        lastModified = mtime,
                    ))
                }
            }
        }
    }

    private fun isAudioDocument(displayName: String, mimeType: String): Boolean {
        if (AUDIO_MIME_PREFIXES.any { mimeType.startsWith(it) }) return true
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }
}
```

- [ ] **Step 4: Run tests; expect 5 SafTreeWalkerTest cases to pass**

```
pwsh -c "./gradlew :data:library:testAndroidHostTest"
```
Expected: BUILD SUCCESSFUL with 5 new tests passing. `:data:library:testAndroidHostTest` count: 44 → 49.

Note: if Robolectric's ShadowContentResolver doesn't provide a `setCursor(uri, cursor)` hook directly, the test setup may need to register a `ContentProvider` via `Robolectric.buildContentProvider`. Empirically verify and adjust — the helper `registerDocuments(parentUri, children)` may need a different shadow API. The intent of the test (5 audio-discovery scenarios) stays the same.

- [ ] **Step 5: Canonical verify-build**

```
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 109+1 tests (was 104+1 — +5 SafTreeWalker tests).

- [ ] **Step 6: Commit**

```bash
git add data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTreeWalker.kt data/library/src/androidHostTest/kotlin/com/clayworks/kiln/library/scan/SafTreeWalkerTest.kt
git commit -m "$(cat <<'EOF'
feat(data:library): SafTreeWalker — Phase 2a Track B's SAF enumerator

Pure walker over a Storage Access Framework tree URI. Recursively
enumerates child documents via DocumentsContract.buildChildDocumentsUriUsingTree,
filters for audio (MIME prefix "audio/" OR known audio extensions like
.flac/.mp3/.wav/.m4a/.ogg/.opus/.aac), yields a lazy Sequence<SafDocument>
so large trees stream without full materialization. Each cursor is
closed as recursion unwinds.

5 Robolectric-backed tests cover: empty tree, single audio file,
non-audio filtering, subdirectory recursion, extension-fallback when
MIME is application/octet-stream. Brings :data:library:testAndroidHostTest
to 49 tests (was 44).

Sibling to AndroidMediaStoreScanner — Task 4 wires the walker into the
scanner's scanSafTrees pass. Files in SAF trees that MediaStore already
knows about will land twice with distinct file_path values; documented
in CLAUDE.md as a Phase 2a follow-up dedup target.

Phase 2a Track B Task 2 of 7.
EOF
)"
```

---

### Task 3: `SafTagReader` — MediaMetadataRetriever-backed tag reader

**Why standalone:** the tag reader's MediaMetadataRetriever lifecycle is fiddly (release on every code path, even exceptions). Isolating into its own file keeps the scanner's main flow readable.

**Files:**
- Create: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTagReader.kt`

**Steps:**

- [ ] **Step 1: Create SafTagReader**

Path: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTagReader.kt`

```kotlin
// SafTagReader — opens a content:// URI via the system ContentResolver,
// wraps it in a MediaMetadataRetriever, and extracts a TrackTags-shaped
// metadata bundle. Best-effort; failures yield null fields rather than
// crashing the scan.
//
// MediaMetadataRetriever lifecycle: release() MUST run on every code path,
// even on exception. This file uses try/finally pattern. Failure to release
// leaks a native MediaExtractor instance per failed read; on a multi-thousand
// track library this matters.
//
// Sample-rate / bit-depth / channel-count are NOT in MediaMetadataRetriever's
// METADATA_KEY_* set. We default sample_rate to 44100 and channels to 2 —
// same defaults as AndroidMediaStoreScanner's MediaStore pass. A per-file
// MediaFormat introspection pass is Phase 2a polish.

package com.clayworks.kiln.library.scan.internal

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import co.touchlab.kermit.Logger

private val log = Logger.withTag("SafTagReader")

internal const val DEFAULT_SAMPLE_RATE_HZ = 44100L
internal const val DEFAULT_CHANNELS = 2L

data class SafTrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Long?,
    val year: Long?,
    val genre: String?,
    val composer: String?,
    val bitrateKbps: Long?,
)

object SafTagReader {

    /**
     * Reads metadata from a SAF document URI. Returns null if the retriever
     * cannot open the file or the document has no parseable audio header.
     *
     * Caller is responsible for using the returned [SafTrackMetadata] as a
     * read-through layer over the file's [displayName] — if any field is
     * empty/null, fall back to the display name (e.g., title defaults to
     * the filename minus extension).
     */
    fun read(
        contentResolver: ContentResolver,
        documentUri: Uri,
        displayName: String,
    ): SafTrackMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(contentResolver.openFileDescriptor(documentUri, "r")?.fileDescriptor)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: displayName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()
                ?.takeIf { it > 1000 }
                ?.toLong()
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() }
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.takeIf { it.isNotBlank() }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            val bitrateKbps = if (bitrate != null && bitrate > 0) bitrate / 1000 else null

            SafTrackMetadata(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                durationMs = durationMs,
                trackNumber = trackNumber,
                year = year,
                genre = genre,
                composer = composer,
                bitrateKbps = bitrateKbps,
            )
        } catch (e: Exception) {
            log.w(e) { "SafTagReader failed for $documentUri ($displayName)" }
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                log.w(e) { "MediaMetadataRetriever.release() threw for $documentUri" }
            }
        }
    }
}
```

- [ ] **Step 2: Build :data:library:assemble to confirm compile**

```
pwsh -c "./gradlew :data:library:build"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Canonical verify-build**

```
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 109+1 tests (no test count change — SafTagReader is hard to unit-test without instrumented tests; its correctness is verified by Pixel 7 smoke).

- [ ] **Step 4: Commit**

```bash
git add data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTagReader.kt
git commit -m "$(cat <<'EOF'
feat(data:library): SafTagReader — MediaMetadataRetriever-backed metadata extractor

Reads title/artist/album/albumArtist/duration/trackNumber/year/genre/
composer/bitrate from a content:// URI via MediaMetadataRetriever.
Returns SafTrackMetadata on success or null when the retriever can't
open the file (corrupt header, ContentResolver permission revoked
between scan-start and read). MediaMetadataRetriever.release() runs
on every code path via try/finally — critical to avoid native
MediaExtractor leaks on multi-thousand-track libraries.

Sample-rate / bit-depth / channel-count default to (44100, null, 2) —
same as AndroidMediaStoreScanner's MediaStore pass. Per-file MediaFormat
introspection is deferred to Phase 2a polish.

No tests — instrumented tests would need a real file descriptor;
Pixel 7 smoke + AndroidMediaStoreScanner's integration test (Task 4)
exercise this transitively.

Phase 2a Track B Task 3 of 7.
EOF
)"
```

---

### Task 4: AndroidMediaStoreScanner — add SAF tree pass

**Why standalone:** this is the largest task. The scanner gets a new constructor parameter, `runScan` becomes suspend, and a new `scanSafTrees(scanStartedMs)` method threads through the existing upsert chain. Splitting from Task 5 (the DI rewire that wires settings into this provider) keeps the scanner change focused.

**Files:**
- Modify: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt`

**Steps:**

- [ ] **Step 1: Modify AndroidMediaStoreScanner constructor + signatures**

Edit the file. Add `safTreeUrisFlow: Flow<List<String>>` as the second constructor param (after `context`, before `db`):

```kotlin
class AndroidMediaStoreScanner(
    private val context: Context,
    private val safTreeUrisFlow: Flow<List<String>>,
    private val db: KilnDatabase,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {

    override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = false) }

    override suspend fun scanFull(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = true) }

    private suspend fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> = ...
}
```

Make `runScan` `suspend`. Read `safTreeUris = safTreeUrisFlow.first()` at the top of the function body (before any DB work). Add imports for `kotlinx.coroutines.flow.Flow` + `kotlinx.coroutines.flow.first`.

- [ ] **Step 2: Add scanSafTrees method**

Append (inside the class, after `scanOneTrack`):

```kotlin
private fun scanSafTrees(
    safTreeUris: List<String>,
    scanStartedMs: Long,
): Triple<Int, Int, Int> {
    if (safTreeUris.isEmpty()) return Triple(0, 0, 0)

    var added = 0
    var updated = 0
    var parseErrors = 0

    for (uriString in safTreeUris) {
        val treeUri = try {
            android.net.Uri.parse(uriString)
        } catch (e: Exception) {
            log.w(e) { "skipped malformed SAF URI: $uriString" }
            continue
        }
        // Skip non-SAF URIs (filesystem paths from Desktop wouldn't appear on
        // Android, but defensive in case a shared scan_folders setting is set
        // by some future cross-platform sync flow).
        if (treeUri.scheme != "content") continue

        for (doc in com.clayworks.kiln.library.scan.internal.SafTreeWalker.walk(context.contentResolver, treeUri)) {
            val filePath = doc.documentUri.toString()
            val mtime = doc.lastModified.takeIf { it > 0 } ?: scanStartedMs
            val size = doc.size.coerceAtLeast(0L)

            val existing = db.trackQueries.selectByFilePath(filePath).executeAsOneOrNull()
            if (existing != null &&
                existing.file_mtime_ms == mtime &&
                existing.file_size_bytes == size &&
                existing.deleted_at_ms == null
            ) {
                db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = filePath)
                continue
            }

            val metadata = com.clayworks.kiln.library.scan.internal.SafTagReader
                .read(context.contentResolver, doc.documentUri, doc.displayName)
            if (metadata == null) {
                parseErrors++
                log.w { "SafTagReader returned null for ${doc.displayName} ($filePath); skipping" }
                continue
            }

            val codec = com.clayworks.kiln.library.scan.detectCodecFromMime(doc.mimeType)

            val artistId = upsertArtist(metadata.artist, com.clayworks.kiln.library.scan.internal.toSortName(metadata.artist), mbid = null)
            val albumArtistId = metadata.albumArtist?.let { albumArtist ->
                upsertArtist(albumArtist, com.clayworks.kiln.library.scan.internal.toSortName(albumArtist), mbid = null)
            } ?: artistId
            val albumId = metadata.album?.let { albumName ->
                upsertAlbum(
                    albumArtistId = albumArtistId,
                    albumName = albumName,
                    albumNameSort = com.clayworks.kiln.library.scan.internal.toSortName(albumName),
                    year = metadata.year,
                )
            }

            if (existing == null) {
                db.trackQueries.insert(
                    album_id = albumId,
                    artist_id = artistId,
                    title = metadata.title,
                    title_sort = com.clayworks.kiln.library.scan.internal.toSortName(metadata.title),
                    duration_ms = metadata.durationMs,
                    track_number = metadata.trackNumber,
                    disc_number = null,
                    year = metadata.year,
                    date = null,
                    genre = metadata.genre,
                    composer = metadata.composer,
                    bpm = null,
                    codec = codec,
                    bitrate_kbps = metadata.bitrateKbps,
                    sample_rate_hz = com.clayworks.kiln.library.scan.internal.DEFAULT_SAMPLE_RATE_HZ,
                    bit_depth = null,
                    channels = com.clayworks.kiln.library.scan.internal.DEFAULT_CHANNELS,
                    file_path = filePath,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
                    replay_gain_track_db = null,
                    replay_gain_album_db = null,
                    replay_gain_track_peak = null,
                    replay_gain_album_peak = null,
                    has_embedded_art = 0L,
                    art_path = null,
                    source = "local",
                    date_added_ms = scanStartedMs,
                    date_modified_ms = scanStartedMs,
                    last_scanned_ms = scanStartedMs,
                )
                added++
            } else {
                db.trackQueries.updateForRescan(
                    album_id = albumId,
                    artist_id = artistId,
                    title = metadata.title,
                    title_sort = com.clayworks.kiln.library.scan.internal.toSortName(metadata.title),
                    duration_ms = metadata.durationMs,
                    track_number = metadata.trackNumber,
                    disc_number = null,
                    year = metadata.year,
                    date = null,
                    genre = metadata.genre,
                    composer = metadata.composer,
                    bpm = null,
                    codec = codec,
                    bitrate_kbps = metadata.bitrateKbps,
                    sample_rate_hz = com.clayworks.kiln.library.scan.internal.DEFAULT_SAMPLE_RATE_HZ,
                    bit_depth = null,
                    channels = com.clayworks.kiln.library.scan.internal.DEFAULT_CHANNELS,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
                    replay_gain_track_db = null,
                    replay_gain_album_db = null,
                    replay_gain_track_peak = null,
                    replay_gain_album_peak = null,
                    has_embedded_art = 0L,
                    art_path = null,
                    modifiedAtMs = scanStartedMs,
                    scannedAtMs = scanStartedMs,
                    filePath = filePath,
                )
                updated++
            }
        }
    }

    return Triple(added, updated, parseErrors)
}
```

Note the `detectCodecFromMime` reference — the existing helper is already top-level-internal in the file. Imports needed: SafTreeWalker, SafTagReader, toSortName, DEFAULT_SAMPLE_RATE_HZ, DEFAULT_CHANNELS — adjust to short imports (`import com.clayworks.kiln.library.scan.internal.SafTreeWalker` etc.) at the top of the file.

- [ ] **Step 3: Call scanSafTrees inside runScan**

After the existing MediaStore loop finishes (around line 98, after `cursor.use { }` block ends), and BEFORE the soft-delete block (around line 100), insert:

```kotlin
// Phase 2a Track B: walk SAF-picked tree URIs from settings.scanFolders.
// These augment the system-wide MediaStore pass above — Android users
// who picked a folder via the SAF picker want files MediaStore doesn't
// know about (Downloads, sideloaded SD, etc.). Same upsert + soft-delete
// semantics; counts accumulate.
val safTreeUris = safTreeUrisFlow.first()
val (safAdded, safUpdated, safParseErrors) = scanSafTrees(safTreeUris, scanStartedMs)
added += safAdded
updated += safUpdated
parseErrors += safParseErrors
```

Promote `added`, `updated`, `parseErrors` to `var` outside the cursor block if not already (they are in the existing code — verify).

- [ ] **Step 4: Build to confirm compile**

```
pwsh -c "./gradlew :data:library:build"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run :data:library:testAndroidHostTest to confirm existing tests still pass**

```
pwsh -c "./gradlew :data:library:testAndroidHostTest"
```
Expected: BUILD SUCCESSFUL. All 49 tests (44 existing + 5 SafTreeWalker) pass.

- [ ] **Step 6: Canonical verify-build**

```
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 109+1 tests.

- [ ] **Step 7: Commit**

```bash
git add data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt
git commit -m "$(cat <<'EOF'
feat(data:library): AndroidMediaStoreScanner adds SAF tree pass — Phase 2a Track B

Scanner constructor gains safTreeUrisFlow: Flow<List<String>> as the
second param. runScan becomes suspend, reads safTreeUrisFlow.first() at
the top. After the existing MediaStore pass completes, scanSafTrees
walks each tree URI via SafTreeWalker, reads tags via SafTagReader,
upserts into the same track table using the existing
upsertArtist/upsertAlbum/insert-or-updateForRescan chain. File-path
column carries the content URI string for SAF entries — schema
unchanged; UNIQUE constraint on file_path keeps SAF and MediaStore
entries co-existent.

Empty-guard: if safTreeUris is empty, scanSafTrees returns immediately
without touching the DB. No behavioral regression from Track A — the
MediaStore-only path remains the default for users who haven't picked
a SAF folder via the Settings UI yet.

Phase 2a Track B Task 4 of 7. DI rewire (Task 5) injects the flow.
EOF
)"
```

---

### Task 5: AndroidAppGraph DI rewire + MainActivity SAF picker wiring

**Why combined:** AppGraph + MainActivity changes are tightly coupled — the graph exposes a new scanner-provider parameter shape, and MainActivity replaces the Toast stub with the SAF launcher. Splitting them would leave `main` in a state where MainActivity compiles but uses the wrong stub.

**Files:**
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt`
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`
- Modify: `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt` (only if existing tests break)

**Steps:**

- [ ] **Step 1: Modify AndroidAppGraph — inject SettingsRepository into scanner provider**

Edit the `mediaStoreScanner` provider (around line 80 of the post-Task-8-Track-A file). The provider currently takes `(context, db, driver)`; add `settings: SettingsRepository`:

```kotlin
@Singleton
@Provides
protected fun mediaStoreScanner(
    context: Context,
    settings: SettingsRepository,
    db: KilnDatabase,
    driver: SqlDriver,
): LibraryScanner = AndroidMediaStoreScanner(
    context = context,
    safTreeUrisFlow = settings.scanFolders,
    db = db,
    driver = driver,
    ioDispatcher = Dispatchers.IO,
)
```

`settings.scanFolders` is already `Flow<List<String>>` — perfect shape for the scanner's new parameter. No map needed.

- [ ] **Step 2: Run :app-android:testDebugUnitTest to confirm graph tests still pass**

```
pwsh -c "./gradlew :app-android:testDebugUnitTest"
```
Expected: BUILD SUCCESSFUL. The existing `graph_exposes_settings_repository` test already verifies the SettingsRepository is provided; no new DI test needed.

If the existing test asserts a specific scanner-provider parameter list (it doesn't, per Task 8 review) then update; otherwise leave as-is.

- [ ] **Step 3: Modify MainActivity — replace Toast stub with SAF picker**

In `AndroidSettingsRoute` (around lines 100-150 of MainActivity.kt post-Track-A), replace the `onPickFolder` Toast call with the SAF picker hook:

```kotlin
@Composable
private fun AndroidSettingsRoute(
    graph: AndroidAppGraph,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())

    val launchSafPicker = rememberSafFolderPicker(onPicked = { uri ->
        if (uri !in scanFolders) {
            coroutineScope.launch {
                graph.settings.setScanFolders(scanFolders + uri)
            }
        }
    })

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        SettingsScreen(
            state = SettingsState(themeMode, scanOnLaunch, scanFolders),
            onThemeModeChange = { coroutineScope.launch { graph.settings.setThemeMode(it) } },
            onScanOnLaunchChange = { coroutineScope.launch { graph.settings.setScanOnLaunch(it) } },
            onPickFolder = launchSafPicker,
            onRemoveFolder = { folder ->
                coroutineScope.launch {
                    graph.settings.setScanFolders(scanFolders - folder)
                }
            },
        )
    }
}
```

Add the import: `import com.clayworks.kiln.saf.rememberSafFolderPicker`.

Remove the now-dead Toast import (`import android.widget.Toast`) — it's only referenced in the stub being replaced.

- [ ] **Step 4: Build :app-android:assembleDebug**

```
pwsh -c "./gradlew :app-android:assembleDebug"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Canonical verify-build**

```
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 109+1 tests.

- [ ] **Step 6: Commit**

```bash
git add app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat(app-android): wire SAF folder picker — Phase 2a Track B closes Track A stub

AndroidAppGraph's mediaStoreScanner provider gains a SettingsRepository
param and constructs AndroidMediaStoreScanner with
safTreeUrisFlow = settings.scanFolders. The scanner now picks up
user-configured SAF tree URIs alongside its existing MediaStore pass.

MainActivity's AndroidSettingsRoute replaces the Toast stub with a
rememberSafFolderPicker(onPicked) hook. Tapping "Add Folder" in Settings
now opens the system file picker; the picked tree URI is persisted via
takePersistableUriPermission (inside the hook) and appended to
settings.scanFolders. On the next library scan, the SAF tree walker
finds new files; existing entries are touched (incremental); removed
files soft-delete via the existing scanStartedMs gate.

Dedup: SAF + MediaStore for the same physical file land as two rows
with distinct file_path schemes — accepted Track B limitation,
follow-up Phase 2a dedup pass keyed on (file_size_bytes, mtime,
display_name) tracked in CLAUDE.md.

Phase 2a Track B Task 5 of 7. Tasks 6-7 are CLAUDE.md gotchas + final
verification.
EOF
)"
```

---

### Task 6: CLAUDE.md gotchas

**Why standalone:** three new SAF gotchas are worth capturing for future sessions. Bundle into one docs commit, not folded into Task 5 (which is feature work).

**Files:**
- Modify: `CLAUDE.md`

**Steps:**

- [ ] **Step 1: Append three SAF gotchas to the Build/Dep Gotchas section**

Find the existing SQLDelight gotchas added in Task 4 of Track A (commit `01848a5`). Append after them:

```
- **Android SAF + MediaStore can co-exist as duplicate rows in `track`.** A file in a SAF-picked tree that's ALSO in MediaStore's index lands twice: once with `file_path = "/storage/emulated/0/Music/song.flac"` (MediaStore filesystem path) and once with `file_path = "content://com.android.externalstorage.documents/tree/.../song.flac"` (SAF document URI). UNIQUE constraint on file_path doesn't catch this — the strings differ. Track B (2026-05-22) accepts this; a Phase 2a follow-up should dedupe by `(file_size_bytes, file_mtime_ms, display_name)` triple after both passes.
- **MediaMetadataRetriever requires `release()` on every code path.** Native MediaExtractor leaks if the retriever is GC'd without an explicit release. The `try/finally` pattern around the retriever lifecycle is non-negotiable. Reading 27k tracks through SAF would leak 27k native extractors without it. See `SafTagReader` in `data/library/src/androidMain/.../scan/internal/`.
- **`takePersistableUriPermission` must be called BEFORE returning from the activity result lambda.** The implicit grant from `ACTION_OPEN_DOCUMENT_TREE` is alive for ~hundreds of ms; once the activity result has propagated, the un-persisted permission is gone and `take...` fails with SecurityException. Calling synchronously inside the `rememberLauncherForActivityResult { uri -> }` lambda is the only safe pattern. Pair with `Intent.FLAG_GRANT_READ_URI_PERMISSION` only — write isn't needed for read-only scanning. See `SafFolderPicker.kt`.
```

- [ ] **Step 2: Confirm nothing else needs documentation**

Read through CLAUDE.md once. The Track B work doesn't introduce new project conventions, just SAF-specific gotchas. The "Hard Rules — Never Do These" + "Workflow" + "Named Patterns" sections need no updates.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(claude): SAF gotchas — Phase 2a Track B discoveries

Three new entries in Build/Dep Gotchas:

1. SAF + MediaStore for the same physical file co-exist as duplicate
   rows in the track table — UNIQUE(file_path) doesn't catch it because
   the URI schemes differ. Phase 2a follow-up dedup by (size, mtime,
   display_name) tracked.
2. MediaMetadataRetriever.release() is mandatory on every code path —
   try/finally is non-negotiable. Leaks native MediaExtractor instances
   per failed read.
3. takePersistableUriPermission must run synchronously inside the
   ActivityResult lambda — the implicit grant expires after the result
   propagates. FLAG_GRANT_READ_URI_PERMISSION only (no write needed
   for scanning).

Phase 2a Track B Task 6 of 7.
EOF
)"
```

---

### Task 7: Final verification + Pixel 7 smoke handoff

**Why:** Track B completes here. The canonical gates have been green through every task; this final task confirms the integrated end state + writes the Pixel 7 smoke checklist for Clay to execute at session-close.

**Files:**
- (No code changes; final verification + brief docs update if needed)

**Steps:**

- [ ] **Step 1: Run the full test surface across all touched modules**

```
pwsh -c "./gradlew :data:library:desktopTest :data:library:testAndroidHostTest :data:library:verifyCommonMainKilnDatabaseMigration :ui:components:desktopTest :app-android:testDebugUnitTest :app-android:assembleDebug :app-desktop:test :audio:playback:desktopTest --console=plain"
```
Expected: BUILD SUCCESSFUL across all 8 tasks.

- [ ] **Step 2: Canonical verify-build**

```
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 109+1 tests.

- [ ] **Step 3: Install on Pixel 7 + run autonomous launch smoke**

```
pwsh -c "./gradlew :app-android:installDebug"
pwsh -c "C:\Users\chawo\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 2A261FDH300B1P shell monkey -p com.clayworks.kiln -c android.intent.category.LAUNCHER 1"
```
Expected: APK installs cleanly, app launches, no crash in logcat. The autonomous portion stops here — manual GUI smoke (Step 4) is delegated to Clay.

- [ ] **Step 4: Document the Pixel 7 manual smoke checklist for Clay**

Append to the bottom of the Track B plan file (this file) a "Pixel 7 manual smoke checklist" section so Clay has a copy-pastable list to execute on the device:

```markdown
## Pixel 7 manual smoke checklist (delegated to Clay at session-close)

Device: Pixel 7 Pro / Tensor G2 / Android 14 / serial 2A261FDH300B1P

1. Launch Kiln (LAUNCHER intent already fired by autonomous Step 3).
2. Tap the gear icon (top-right of PlayFirstTrackScreen).
3. SettingsScreen renders; "Library folders" section is empty.
4. Tap "Add Folder".
5. **Expected:** Android SAF system file picker opens (NOT a Toast — the Toast was Track A's stub, replaced in Task 5).
6. Navigate to a folder containing audio files (e.g., `/sdcard/Music`).
7. Tap "Use this folder" / "Allow" / equivalent.
8. **Expected:** the folder URI (e.g., `content://com.android.externalstorage.documents/tree/primary:Music`) appears in the SettingsScreen's folder list.
9. Tap "Close" to return to PlayFirstTrackScreen.
10. Tap "Scan Library".
11. **Expected:** scan completes; the result counter shows `+N added` where N includes both MediaStore entries AND files from the SAF tree.
12. Cold-kill the app via `adb -s 2A261FDH300B1P shell am force-stop com.clayworks.kiln`.
13. Relaunch via the launcher.
14. Tap gear icon → Settings → **Expected:** the previously-picked folder URI is STILL in the list (persistent URI permission survived).
15. Tap the delete icon next to the URI → **Expected:** folder removed from list.
16. Tap "Scan Library" → **Expected:** scan completes; files previously found via SAF are now soft-deleted (next scan would re-find them if a new picker invocation re-adds the tree).

If any step fails as something other than its expected behavior, that's the gap to surface in the session-close handoff for the next Track A/B/C session.
```

- [ ] **Step 5: Commit if any docs were added**

```bash
git add docs/superpowers/plans/2026-05-22-phase-2a-track-b-saf-folder-picker.md
git commit -m "$(cat <<'EOF'
docs(plan): Phase 2a Track B — Pixel 7 manual smoke checklist appendix

Appends a copy-pastable 16-step smoke checklist for Clay to execute on
the Pixel 7 at session-close. Verifies: SAF picker opens (not Toast),
URI persists via takePersistableUriPermission across cold restart,
scanner finds SAF tree files alongside MediaStore, delete-folder
removes from list (next scan soft-deletes).

Phase 2a Track B Task 7 of 7. Track B is complete locally; PR opens
after Track A's PR merges (Track B is stacked on Track A's branch).
EOF
)"
```

---

## Final verification (run after all 7 tasks ship)

Same gates as Task 7 Steps 1-3.

## PR-opening notes

- Branch: `phase-2a-track-b-saf-folder-picker` based on `phase-2a-track-a-settings-ui`
- After Track A's PR merges, rebase Track B onto the new `main` (or, if Track A is squash-merged, cherry-pick Track B commits onto fresh-from-origin main)
- PR description should reference both Track A's PR #4 as the dependency and the Session 11 handoff's H8 Pixel-discovery gap closure

## Risk register (Track B specifics)

| Risk | Mitigation |
|------|-----------|
| Robolectric ShadowContentResolver doesn't fully simulate DocumentsContract queries | Task 2 Step 4 notes the empirical adjustment if the planned `setCursor(uri, cursor)` API isn't a direct match. Worst case: tests use a custom ContentProvider via `Robolectric.buildContentProvider` instead of shadow registration. The 5 test scenarios remain valid regardless. |
| MediaMetadataRetriever throws on uncommon FLAC files (e.g., 24-bit/192kHz audiophile rips) | SafTagReader catches all `Exception`s and returns null. The scanner logs + skips. A Phase 2a follow-up could use jaudiotagger as a fallback for FLAC-specific tag reading (jaudiotagger has more permissive parsing). |
| User picks a deeply-nested tree (e.g., /sdcard) with thousands of subdirs and non-audio content | SafTreeWalker's audio-MIME + extension filter discards non-audio leaves immediately. The walk is recursive but lazy — files are yielded as discovered. Memory pressure scales with depth, not breadth. |
| SAF permission revoked between scan-start and tag read | SafTreeWalker + SafTagReader catch SecurityException + log + continue. Affected files appear as soft-deletes on the next scan; no crash. |
| `Flow<List<String>>.first()` could hang if the SQLDelight asFlow doesn't emit | SQLDelight `asFlow().mapToOneOrNull()` emits the current value (or null) immediately on collection. Worst case: 50ms read on cold DB cache; never hangs. Tested in Track A's SettingsRepositoryImplTest. |
| Track A's `setScanFolders` call in MainActivity's onPickFolder lambda triggers a recompose that loses the SAF picker's launcher reference | `rememberLauncherForActivityResult` keys its retention on the contract identity; recompose is safe. The hook's returned launcher closure captures the launcher by reference. |
| Pixel 7 user picks a folder under `/storage/emulated/0/Music` where MediaStore ALREADY has those files | Duplicates land in the track table per the documented dedup limitation. User-visible symptom: every track appears twice in the library list. Track B accepts this; Phase 2a follow-up dedup pass resolves. |

---

## Out of scope (deliberate for Track B)

- **Dedup pass for SAF/MediaStore overlap.** Documented as a follow-up.
- **Per-file MediaFormat introspection for sample-rate/bit-depth/channels** — SafTagReader defaults to (44100, null, 2) same as MediaStore pass.
- **SAF tree REMOVAL via Settings UI does NOT revoke the persistent URI permission.** Removing a folder from `settings.scanFolders` only stops the scanner from walking it; the OS-level grant remains. To revoke, the user uses Android's Files app or `releasePersistableUriPermission(uri)`. A future polish task could add a "Forget Folder" action that pairs the list-remove with the permission revoke.
- **`scan_on_launch` automatic scanner invocation** — Track A's toggle exists but the launch-time hook is NOT wired in Track A or Track B. A trivial follow-up adds `if (settings.scanOnLaunch.first()) graph.scanner.scanIncremental()` to `KilnApplication.onCreate` / Desktop `Main.kt` after the graph is constructed.
- **`compose-material-icons-extended`** — the gear icon and delete icon come from the core set already bundled. No new icon dep needed.

End of Phase 2a Track B plan. Total: 7 logical tasks, ~7 commits, ~4-7 wall-clock hours with subagent dispatch + two-stage review.

---

## Pixel 7 manual smoke checklist (delegated to Clay at session-close)

Device: Pixel 7 Pro / Tensor G2 / Android 14 / serial 2A261FDH300B1P

1. Launch Kiln (LAUNCHER intent already fired by autonomous Step 3).
2. Tap the gear icon (top-right of PlayFirstTrackScreen).
3. SettingsScreen renders; "Library folders" section is empty.
4. Tap "Add Folder".
5. **Expected:** Android SAF system file picker opens (NOT a Toast — the Toast was Track A's stub, replaced in Task 5).
6. Navigate to a folder containing audio files (e.g., `/sdcard/Music`).
7. Tap "Use this folder" / "Allow" / equivalent.
8. **Expected:** the folder URI (e.g., `content://com.android.externalstorage.documents/tree/primary:Music`) appears in the SettingsScreen's folder list.
9. Tap "Close" to return to PlayFirstTrackScreen.
10. Tap "Scan Library".
11. **Expected:** scan completes; the result counter shows `+N added` where N includes both MediaStore entries AND files from the SAF tree.
12. Cold-kill the app via `adb -s 2A261FDH300B1P shell am force-stop com.clayworks.kiln`.
13. Relaunch via the launcher.
14. Tap gear icon → Settings → **Expected:** the previously-picked folder URI is STILL in the list (persistent URI permission survived).
15. Tap the delete icon next to the URI → **Expected:** folder removed from list.
16. Tap "Scan Library" → **Expected:** scan completes; files previously found via SAF are now soft-deleted (next scan would re-find them if a new picker invocation re-adds the tree).

If any step fails as something other than its expected behavior, that's the gap to surface in the session-close handoff for the next Track A/B/C session.
