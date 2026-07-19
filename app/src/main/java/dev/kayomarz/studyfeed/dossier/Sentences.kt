package dev.kayomarz.studyfeed.dossier

/**
 * Split a dossier body line into display **sentences**, so the reader can star/highlight ONE sentence
 * rather than the whole paragraph (user request 2026-07-19).
 *
 * **Conservative by design.** It only breaks on `.`/`?`/`!` **followed by a space and then a capital
 * letter or digit**, and NOT after a known abbreviation, a single-letter initial, or inside a decimal.
 * Everything that could be medical notation is safe here: `10^9/L`, `DRB1*04:01`, `1.5 mg`, `< 50` have
 * no `". "` boundary, and `e.g.`/`i.e.`/`etc.` are guarded. **When in doubt it does NOT split** — a
 * paragraph staying whole is the old behaviour, never a corruption (mirrors the PackMarkdown stance).
 */
object Sentences {

    /** Lower-cased tokens that must not end a sentence when followed by `.`. */
    private val ABBREV = setOf(
        "e.g", "i.e", "eg", "ie", "etc", "vs", "no", "cf", "viz", "al", "approx", "dr", "prof", "mr",
        "mrs", "ms", "st", "fig", "ref", "min", "max", "sec", "wk", "mo", "yr", "hr", "mg", "kg", "ml",
        "mmol", "iu", "bd", "od", "tds", "qds", "im", "iv", "po", "sc", "gi", "cns", "ecg", "mri", "ct",
    )

    fun split(line: String): List<String> {
        val s = line.trim()
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            sb.append(ch)
            if (ch == '.' || ch == '?' || ch == '!') {
                var j = i + 1
                while (j < s.length && s[j] == ' ') j++
                // A boundary needs: at least one space, then an upper-case letter or digit, and (for '.')
                // the preceding token must not be a guarded abbreviation / initial / decimal.
                val hasGap = j > i + 1
                val nextStarts = j < s.length && (s[j].isUpperCase() || s[j].isDigit())
                if (hasGap && nextStarts && !(ch == '.' && guarded(s, i))) {
                    out += sb.toString().trim()
                    sb.setLength(0)
                    i = j
                    continue
                }
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** True if the `.` at [dot] should NOT end a sentence (decimal, single initial, or known abbreviation). */
    private fun guarded(s: String, dot: Int): Boolean {
        if (dot > 0 && s[dot - 1].isDigit() && dot + 1 < s.length && s[dot + 1].isDigit()) return true
        var k = dot - 1
        while (k >= 0 && s[k].isLetter()) k--
        val token = s.substring(k + 1, dot).lowercase()
        return token.length == 1 || token in ABBREV
    }
}
