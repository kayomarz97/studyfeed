package dev.kayomarz.studyfeed.pack

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

/**
 * A place a pack can be copied FROM — a directory of named files, and nothing more.
 *
 * **Why this abstraction exists (it is not speculative generality):** in production a pack arrives as a
 * SAF tree `content://` URI, which cannot be driven from a test — it needs a human tapping a file
 * picker. Without this seam, the genuinely risky logic (hash verification, cancellation, partial-import
 * cleanup, atomic rename) could only ever be tested by hand. With it, [PackImporter] is source-agnostic
 * and every one of those paths is provable against a plain directory.
 *
 * It also keeps `DocumentsContract`'s awkwardness in exactly one file.
 */
interface PackSource {
    /** File names directly inside this directory. Not recursive. */
    fun list(): List<String>

    /** Byte length of [name], or -1 when the source doesn't know (SAF may legitimately not). */
    fun sizeOf(name: String): Long

    /** Opens [name] for reading, or null if absent/unreadable. */
    fun open(name: String): InputStream?

    /** A sub-directory (e.g. `figures/`), or null if it isn't there. Absent is normal, not an error. */
    fun child(name: String): PackSource?
}

/**
 * A [PackSource] over a SAF tree URI (`ACTION_OPEN_DOCUMENT_TREE`).
 *
 * **Uses `DocumentsContract` directly, NOT `DocumentFile`** — deliberate. `DocumentFile`'s own javadoc
 * warns it "has substantial overhead" because every property access is a separate ContentResolver query.
 * Across 1,121 figures that overhead, not the bytes, would dominate the import. Here one cursor with an
 * explicit projection lists the whole directory.
 */
class SafPackSource(
    private val context: Context,
    private val treeUri: Uri,
    private val documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : PackSource {

    private data class Entry(val docId: String, val name: String, val mime: String, val size: Long)

    /** One query for the whole directory, cached — the point of not using DocumentFile. */
    private val entries: List<Entry> by lazy {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Entry(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                            if (c.isNull(3)) -1L else c.getLong(3)))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun uriFor(docId: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    override fun list(): List<String> =
        entries.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }.map { it.name }

    override fun sizeOf(name: String): Long = entries.firstOrNull { it.name == name }?.size ?: -1L

    override fun open(name: String): InputStream? = entries.firstOrNull { it.name == name }
        ?.let { runCatching { context.contentResolver.openInputStream(uriFor(it.docId)) }.getOrNull() }

    override fun child(name: String): PackSource? = entries
        .firstOrNull { it.name == name && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
        ?.let { SafPackSource(context, treeUri, it.docId) }
}

/**
 * A [PackSource] over an ordinary directory.
 *
 * Production use: none. Test use: everything — it is what makes the importer's hash-verification,
 * cancellation and cleanup paths testable without a file picker.
 */
class FilePackSource(private val dir: File) : PackSource {
    override fun list(): List<String> = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    override fun sizeOf(name: String): Long = File(dir, name).let { if (it.isFile) it.length() else -1L }
    override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()
    override fun child(name: String): PackSource? = File(dir, name).takeIf { it.isDirectory }?.let { FilePackSource(it) }
}
