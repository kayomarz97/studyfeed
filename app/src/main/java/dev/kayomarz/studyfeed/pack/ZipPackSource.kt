package dev.kayomarz.studyfeed.pack

import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A [PackSource] over a ZIP archive — the non-technical sharing path (2026-07-20). A pack is a *directory*
 * (db + manifest.json + figures/), which is awkward to send around; a single `.zip` is not. [PackImporter]
 * is source-agnostic, so it needs no change — this just reads the pack's files out of the archive.
 *
 * **Zip-slip safety:** [list] returns only DIRECT-child base names (never containing `/`), and the importer
 * writes entries by those names via `File(staging, name)` — so a crafted `../…` entry can never be a name
 * the importer writes. Lookups use the exact `prefix + name` path.
 *
 * The caller owns the [ZipFile] lifetime (it wraps a temp copy of the picked archive, deleted afterwards).
 */
class ZipPackSource(private val zip: ZipFile, private val prefix: String) : PackSource {

    private fun entries(): List<ZipEntry> = java.util.Collections.list(zip.entries())

    override fun list(): List<String> = entries()
        .filter { !it.isDirectory && it.name.startsWith(prefix) }
        .map { it.name.removePrefix(prefix) }
        .filter { it.isNotEmpty() && !it.contains('/') } // direct children only — no traversal

    override fun sizeOf(name: String): Long = zip.getEntry(prefix + name)?.size ?: -1L

    override fun open(name: String): InputStream? = zip.getEntry(prefix + name)?.let { zip.getInputStream(it) }

    override fun child(name: String): PackSource? {
        val childPrefix = "$prefix$name/"
        return if (entries().any { it.name.startsWith(childPrefix) }) ZipPackSource(zip, childPrefix) else null
    }

    companion object {
        /**
         * The path prefix inside [zip] where the pack lives: `""` if `manifest.json` sits at the archive
         * root, or `"<folder>/"` if it's one directory down (the common case — the user zipped the pack
         * FOLDER). Null when no `manifest.json` is found anywhere → not a pack archive.
         */
        fun rootPrefix(zip: ZipFile): String? {
            if (zip.getEntry("manifest.json") != null) return ""
            return java.util.Collections.list(zip.entries())
                .mapNotNull { e -> e.name.indexOf('/').takeIf { it > 0 }?.let { e.name.substring(0, it + 1) } }
                .distinct()
                .firstOrNull { zip.getEntry("${it}manifest.json") != null }
        }
    }
}
