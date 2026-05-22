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
