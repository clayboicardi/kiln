package com.clayworks.kiln.library.scan

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.clayworks.kiln.library.scan.internal.SafTreeWalker
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafTreeWalkerTest {

    private lateinit var contentResolver: ContentResolver
    private val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    @BeforeTest
    fun setUp() {
        // Reset the static fixture map between tests, then register a fresh
        // TestDocumentsProvider for the SAF authority. ContentProvider lookups
        // by ContentResolver.query() find this provider via the AUTHORITY constant.
        // The fallback to Robolectric.buildContentProvider(...).create(authority)
        // is the canonical approach when ShadowContentResolver.setCursor only
        // accepts BaseCursor (Robolectric 4.16's API doesn't take MatrixCursor).
        TestDocumentsProvider.reset()
        val app = ApplicationProvider.getApplicationContext<Application>()
        contentResolver = app.contentResolver

        val info = ProviderInfo().apply {
            authority = TestDocumentsProvider.AUTHORITY
        }
        Robolectric.buildContentProvider(TestDocumentsProvider::class.java)
            .create(info)
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

    private fun registerDocuments(parentUri: Uri, children: List<FakeDoc>) {
        TestDocumentsProvider.registerChildren(parentUri, children)
    }

    data class FakeDoc(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
    )

    /**
     * Minimal in-test ContentProvider for the SAF authority. Used as a fallback
     * because Robolectric 4.16's ShadowContentResolver.setCursor accepts only
     * BaseCursor, not MatrixCursor. The provider intercepts SafTreeWalker's
     * children-URI queries and returns prebuilt MatrixCursors from the static
     * fixture map.
     */
    class TestDocumentsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val children = childrenByUri[uri.toString()] ?: return EMPTY_CURSOR
            val cursor = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ))
            for (doc in children) {
                cursor.addRow(arrayOf<Any?>(doc.documentId, doc.displayName, doc.mimeType, doc.size, doc.lastModified))
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "com.android.externalstorage.documents"

            private val childrenByUri = mutableMapOf<String, List<FakeDoc>>()

            // An empty MatrixCursor with the SAF projection — distinguishes
            // "registered but empty" from "unregistered" (null) so the walker
            // can iterate without NPE on a known-empty tree.
            private val EMPTY_CURSOR: Cursor = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ))

            fun reset() {
                childrenByUri.clear()
            }

            fun registerChildren(parentUri: Uri, children: List<FakeDoc>) {
                childrenByUri[parentUri.toString()] = children
            }
        }
    }
}
