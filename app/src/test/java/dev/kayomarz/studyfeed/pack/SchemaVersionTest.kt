package dev.kayomarz.studyfeed.pack

import dev.kayomarz.studyfeed.pack.SchemaVersion.GateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the schema-version gate (BUILD_PROMPT §8 A1, spec/SPEC.md §4).
 *
 * These are written against the traps found in the REAL pack on 2026-07-17, not against the happy path:
 * the three representations of `schema_version`, and the "refuse newer / accept older" asymmetry that
 * §8 A1's shorthand ("newer/unknown") states less precisely than SPEC.md §4 does.
 *
 * Pure JVM — no device, no emulator. That is deliberate (plan D7): the gate's *decision* is pure logic,
 * so it stays provable forever, on any machine, in milliseconds.
 */
class SchemaVersionTest {

    // ---- parse(): the three-representations trap -------------------------------------------------

    @Test
    fun `parses the TEXT form that pack_manifest actually stores`() {
        // Verified against the shipped pack: typeof(schema_version) = 'text', value '1'.
        assertEquals(1, SchemaVersion.parse("1"))
    }

    @Test
    fun `parses the INTEGER form that PRAGMA user_version actually returns`() {
        // PRAGMA user_version comes back as an Int; callers stringify it at the edge.
        assertEquals(1, SchemaVersion.parse(1.toString()))
    }

    @Test
    fun `tolerates surrounding whitespace rather than refusing a good pack over it`() {
        assertEquals(1, SchemaVersion.parse("  1  "))
    }

    @Test
    fun `returns null for absent or unreadable versions instead of guessing a default`() {
        // The dangerous failure would be defaulting to 1 and opening an unknown pack.
        assertEquals(null, SchemaVersion.parse(null))
        assertEquals(null, SchemaVersion.parse(""))
        assertEquals(null, SchemaVersion.parse("   "))
        assertEquals(null, SchemaVersion.parse("not-a-number"))
        assertEquals(null, SchemaVersion.parse("1.0"))
    }

    @Test
    fun `TEXT and INTEGER representations of the same version agree after parsing`() {
        // THE trap, stated as a test: pack_manifest gives TEXT "1", user_version gives INTEGER 1.
        // A naive == across the raw forms refuses a valid pack. This asserts they reconcile.
        val fromPackManifestText = SchemaVersion.parse("1")
        val fromUserVersionInt = SchemaVersion.parse(1.toString())
        assertEquals(fromPackManifestText, fromUserVersionInt)
        assertTrue(SchemaVersion.evaluate(fromPackManifestText, fromUserVersionInt) is GateResult.Ok)
    }

    // ---- evaluate(): the actual gate -------------------------------------------------------------

    @Test
    fun `todays real pack passes the gate`() {
        // The shipped pack_MRCP_PART1.db: pack_manifest.schema_version="1", PRAGMA user_version=1.
        val result = SchemaVersion.evaluate(packManifestVersion = 1, userVersion = 1)
        assertTrue("the real pack must load", result is GateResult.Ok)
        assertEquals(1, (result as GateResult.Ok).schemaVersion)
    }

    @Test
    fun `a newer pack is refused with a message naming BOTH versions`() {
        // The case the gate exists for: a v2 pack must be refused, never half-loaded, never crashed.
        val result = SchemaVersion.evaluate(packManifestVersion = 2, userVersion = 2)

        assertTrue("a future pack must be refused", result is GateResult.TooNew)
        result as GateResult.TooNew
        assertEquals(2, result.packVersion)
        assertEquals(SchemaVersion.SUPPORTED_MAX, result.supportedMax)
        // §8 A1 requires the refusal to NAME BOTH versions — "unsupported pack" helps nobody.
        assertTrue("message must name the pack's version", result.message.contains("2"))
        assertTrue("message must name what we support", result.message.contains("1"))
    }

    @Test
    fun `an older compatible pack still loads`() {
        // SPEC.md §4: refuse NEWER; "a compatible older pack still loads". Asserted so a future
        // tightening of the gate cannot silently start rejecting old packs.
        val result = SchemaVersion.evaluate(packManifestVersion = 0, userVersion = 0)
        assertTrue("an older pack must still load", result is GateResult.Ok)
    }

    @Test
    fun `a self-contradicting pack is refused rather than resolved by picking a side`() {
        // SPEC.md §4: user_version MIRRORS pack_manifest.schema_version. If they disagree the pack is
        // malformed; choosing either could be the wrong one, and both mis-read the data.
        val result = SchemaVersion.evaluate(packManifestVersion = 1, userVersion = 2)
        assertTrue(result is GateResult.Malformed)
        assertTrue((result as GateResult.Malformed).message.contains("1"))
        assertTrue(result.message.contains("2"))
    }

    @Test
    fun `a pack with no manifest row is refused, not assumed fine`() {
        assertTrue(SchemaVersion.evaluate(null, 1) is GateResult.Malformed)
        assertTrue(SchemaVersion.evaluate(1, null) is GateResult.Malformed)
        assertTrue(SchemaVersion.evaluate(null, null) is GateResult.Malformed)
    }

    @Test
    fun `the gate never throws - refusal is a value, not an exception`() {
        // §8 A1: "never a crash, never a partial load". A gate that throws on hostile input IS a crash.
        val hostile = listOf(null, -1, 0, 1, 2, 99, Int.MAX_VALUE, Int.MIN_VALUE)
        for (a in hostile) for (b in hostile) {
            SchemaVersion.evaluate(a, b) // must not throw for any combination
        }
    }

    // ---- the no-hardcoded-exam contract ----------------------------------------------------------

    @Test
    fun `the gate holds no exam-specific knowledge`() {
        // §0 multi_pack: the gate must not know MRCP from USMLE. It compares integers; that is all.
        // A future pack for any exam at schema 1 loads through exactly the same path.
        assertTrue(SchemaVersion.evaluate(1, 1) is GateResult.Ok)
        assertEquals(1, SchemaVersion.SUPPORTED_MAX)
    }
}
