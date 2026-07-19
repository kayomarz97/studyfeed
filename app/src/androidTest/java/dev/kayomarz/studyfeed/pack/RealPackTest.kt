package dev.kayomarz.studyfeed.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reads the **REAL** `pack_MRCP_PART1.db` — the actual shipped artifact, not a fixture.
 *
 * **Why this exists alongside [PackDatabaseTest]'s synthetic packs:** a fixture only ever contains what
 * I thought to put in it, so it can only prove the logic is self-consistent. The real pack is the thing
 * the app must actually play: 141 MB, 32,957 feed items, built by a 14-stage compiler over three days,
 * with quirks nobody would invent — `schema_version` stored as TEXT, two different `checksum` fields,
 * three tables legitimately empty. Both kinds of test are needed; neither substitutes for the other.
 *
 * **SKIPS (not fails) when the pack is absent.** `compiler/dist/` is gitignored — the pack is derived
 * from copyrighted sources and must never enter git — so a fresh clone, another machine, or CI simply
 * will not have it. A test that FAILED there would train everyone to ignore a red suite. `assumeTrue`
 * reports it honestly as skipped instead.
 *
 * ⚠ **A SKIP IS NOT A PASS, and the tooling will tell you it is.** `am instrument` prints
 * `OK (3 tests)` for three *skipped* tests, and a naive JUnit-XML reader that only looks for
 * `<failure>`/`<error>` prints `[PASS]`. Both lied during A1. Always check the `skipped` count, or
 * `-r` raw output for `AssumptionViolatedException`, before believing this file verified anything.
 *
 * **Getting the pack onto the device — two traps, both hit for real (2026-07-17):**
 *  1. **Do NOT use `/sdcard/Android/data/<pkg>/`.** `adb push` writes there as the `shell` user, and on
 *     Android 11+ the app **cannot read it back** ("Permission denied" even via `run-as`).
 *  2. **Do NOT let Gradle install for you and then push.** `connectedAndroidTest` UNINSTALLS the old
 *     APK first, and uninstall **wipes app-scoped storage** — the fixture vanishes before the test runs.
 *
 * The procedure that works — push to `/data/local/tmp` (shell-writable), then hand it to the app's own
 * `filesDir` via `run-as`, and drive the runner directly so nothing reinstalls underneath you:
 * ```
 * ./gradlew installDebug installDebugAndroidTest
 * adb push compiler/dist/pack_MRCP_PART1.db /data/local/tmp/pack.db
 * adb push compiler/dist/manifest.json     /data/local/tmp/manifest.json
 * adb shell chmod 644 /data/local/tmp/pack.db /data/local/tmp/manifest.json
 * adb shell "run-as dev.kayomarz.studyfeed mkdir -p files/realpack"
 * adb shell "run-as dev.kayomarz.studyfeed cp /data/local/tmp/pack.db files/realpack/pack_MRCP_PART1.db"
 * adb shell "run-as dev.kayomarz.studyfeed cp /data/local/tmp/manifest.json files/realpack/manifest.json"
 * adb shell am instrument -w -e class dev.kayomarz.studyfeed.pack.RealPackTest \
 *   dev.kayomarz.studyfeed.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `filesDir` is also where an imported pack genuinely lives (plan D2), so this exercises the real path.
 *
 * ⚠ Assertions here are about the **shape of the contract**, never about MRCP. The exam's own values
 * come from the pack and are only checked for internal consistency (e.g. "the app-bar label is whatever
 * `pack_manifest` says") — hardcoding this exam's expectations would be the very bug §0 `multi_pack`
 * forbids, in a test instead of in the app.
 */
@RunWith(AndroidJUnit4::class)
class RealPackTest {

    /**
     * Looks in the app's own `filesDir` — NOT external storage. See the KDoc: `adb push` into
     * `/sdcard/Android/data/<pkg>/` produces shell-owned files the app cannot read on Android 11+.
     * `filesDir` is also where a real imported pack lives (plan D2), so this walks the real path.
     */
    private fun realPack(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "realpack")
        return dir.listFiles { f -> f.name.startsWith("pack_") && f.name.endsWith(".db") }?.firstOrNull()
    }

    @Test
    fun the_real_shipped_pack_loads_through_the_same_generic_path_as_any_other() {
        val f = realPack()
        assumeTrue("real pack not pushed to this device — skipping (see KDoc)", f != null)

        val result = PackDatabase.open(f!!)
        assertTrue("the real pack must load: $result", result is PackDatabase.Result.Ok)
        val pack = (result as PackDatabase.Result.Ok).pack

        // Contract shape, not exam identity: SOMETHING must fill each of these, or the UI has no labels.
        assertEquals("schema_version must parse from its TEXT storage", 1, pack.manifest.schemaVersion)
        assertTrue("exam_code must be non-empty — it namespaces the progress DB",
            pack.manifest.examCode.isNotBlank())
        assertTrue("exam_name must be non-empty — it IS the app-bar label",
            pack.manifest.examName.isNotBlank())
        assertTrue("a real pack must define subjects to filter by", pack.subjects.isNotEmpty())
        assertTrue("a real pack must define tags to chip", pack.tags.isNotEmpty())

        // The pack's own declaration must agree with its rows. A mismatch here means the compiler
        // shipped a pack whose flags lie about its contents — worth failing loudly over.
        assertEquals("the real pack's flags must match its rows",
            emptyList<String>(), pack.capabilities.declarationMismatches)
    }

    @Test
    fun the_real_pack_has_the_C11b_assets_that_the_spec_once_claimed_were_missing() {
        val f = realPack()
        assumeTrue("real pack not pushed to this device — skipping", f != null)
        val pack = (PackDatabase.open(f!!) as PackDatabase.Result.Ok).pack

        // PATCH 03: §7/§8 A1 used to say these "ship empty (trimmed at C11)". C11b + the figures tail
        // undid that trim. This test is what stops the stale claim quietly coming back — if someone
        // "optimises" the app to skip blob/figure code paths because a doc said they're empty, this
        // fails and says why.
        assertTrue("text_blobs are populated (C11b) — the 'ships empty' claim is stale",
            pack.capabilities.hasBlobs)
        assertTrue("interlink_quizzes are populated (C11b)", pack.capabilities.hasInterlink)
        assertTrue("figures are populated (figures tail)", pack.capabilities.hasFigures)
        assertTrue("flashcards are populated", pack.capabilities.hasFlashcards)
        assertTrue("dossiers are populated", pack.capabilities.hasDossiers)
        assertTrue("concept map is populated", pack.capabilities.hasConceptMap)
    }

    @Test
    fun the_two_checksum_fields_are_genuinely_different_values() {
        // Guards the trap in code rather than only in a comment: if a future change ever "tidies" these
        // into one field, or compares them, this fails and explains why. Verified 2026-07-17:
        // pack_manifest.checksum = 8551d0c4… (content hash over stable_keys, the §5j anchor)
        // manifest.json.checksum = 8005f7d7… (sha256 of the .db FILE, transfer integrity)
        val f = realPack()
        assumeTrue("real pack not pushed to this device — skipping", f != null)
        val pack = (PackDatabase.open(f!!) as PackDatabase.Result.Ok).pack

        val manifestJson = File(f.parentFile, "manifest.json")
        assumeTrue("manifest.json not pushed — skipping", manifestJson.isFile)
        val fileChecksum = org.json.JSONObject(manifestJson.readText()).getString("checksum")

        assertTrue("pack_manifest.checksum must be populated", pack.manifest.contentChecksum.isNotBlank())
        assertTrue("manifest.json.checksum must be populated", fileChecksum.isNotBlank())
        org.junit.Assert.assertNotEquals(
            "These two 'checksum' fields are DIFFERENT THINGS and must never be compared: " +
                "pack_manifest.checksum hashes the stable_key set; manifest.json.checksum hashes the " +
                "db FILE. If they are ever equal, something has conflated them.",
            fileChecksum, pack.manifest.contentChecksum,
        )
    }
}
