package dev.kayomarz.studyfeed.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The ZIP import source (2026-07-20) — proves a pack can be shared as a single `.zip`. Runs on the JVM
 * (`java.util.zip` needs no device). The two shapes that matter are covered: a colleague who zipped the
 * pack FOLDER (manifest one level down) and one who zipped its CONTENTS (manifest at the root).
 */
class ZipPackSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zipFile(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile("pack.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    @Test fun rootPrefix_finds_manifest_at_the_zip_root() {
        ZipFile(zipFile("manifest.json" to "{}", "pack_X.db" to "db")).use { zip ->
            assertEquals("", ZipPackSource.rootPrefix(zip))
        }
    }

    @Test fun rootPrefix_finds_manifest_one_folder_down() {
        ZipFile(zipFile("pack_bundle/manifest.json" to "{}", "pack_bundle/pack_X.db" to "db")).use { zip ->
            assertEquals("pack_bundle/", ZipPackSource.rootPrefix(zip))
        }
    }

    @Test fun a_zip_without_a_manifest_is_not_a_pack() {
        ZipFile(zipFile("random.txt" to "hi")).use { zip ->
            assertNull(ZipPackSource.rootPrefix(zip))
        }
    }

    @Test fun list_returns_direct_children_only_and_open_reads_them() {
        ZipFile(zipFile(
            "pack/manifest.json" to "{}", "pack/pack_X.db" to "DB", "pack/figures/a.png" to "IMG",
        )).use { zip ->
            val src = ZipPackSource(zip, "pack/")
            assertEquals(setOf("manifest.json", "pack_X.db"), src.list().toSet()) // figures/a.png is deeper
            assertEquals("DB", src.open("pack_X.db")!!.readBytes().decodeToString())
            assertEquals(2L, src.sizeOf("pack_X.db"))
            assertNull(src.open("missing.db"))
        }
    }

    @Test fun child_scopes_into_the_figures_folder() {
        ZipFile(zipFile(
            "pack/manifest.json" to "{}", "pack/figures/a.png" to "A", "pack/figures/b.png" to "B",
        )).use { zip ->
            val figures = ZipPackSource(zip, "pack/").child("figures")!!
            assertEquals(setOf("a.png", "b.png"), figures.list().toSet())
            assertEquals("A", figures.open("a.png")!!.readBytes().decodeToString())
            assertNull(ZipPackSource(zip, "pack/").child("nope"))
        }
    }
}
