package dev.kayomarz.studyfeed.dossier

import java.security.MessageDigest

/**
 * Shared line normalisation for the Library's per-line stars + approximate PYQ/5★ highlighting
 * (A7 cycle 3, P3). A line mark anchors to the topic's `stable_key` + a **hash of the normalised line
 * text** — NOT a character offset — so a rebuilt/re-imported pack keeps the mark attached even if the
 * body reflows. The renderer and the toggle hash the *same* trimmed raw line, so they always agree.
 */
object LineText {

    /** Lower-cased, whitespace-collapsed — the canonical form both hashing and matching use. */
    fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Stable content hash of a line (sha1 hex, first 16 chars) — the [dev.kayomarz.studyfeed.progress.LineMark] anchor. */
    fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(normalize(s).toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Salient tokens (≥4 chars, not a stopword) — for approximate matching to high-yield phrases. */
    fun tokens(s: String): Set<String> =
        normalize(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    private val STOP = setOf(
        "with", "that", "this", "from", "which", "were", "have", "their", "there", "these", "those",
        "than", "then", "when", "what", "will", "your", "about", "into", "also", "such", "most", "more",
        "some", "each", "other", "between", "because", "following", "patient", "patients", "years",
        "year", "presents", "history", "given", "shows", "cause", "causes", "caused", "associated",
    )
}
