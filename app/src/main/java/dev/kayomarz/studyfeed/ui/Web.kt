package dev.kayomarz.studyfeed.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The author credit shown on the opening screen and at the foot of Settings (user request 2026-07-18). */
const val AUTHOR_CREDIT = "Made with love by Kayo (and clinically significant dose of Coca-Cola)"
const val AUTHOR_URL = "https://kayomarz.com"

/**
 * Open [url] in the user's browser via an implicit `ACTION_VIEW` intent.
 *
 * **This does not weaken the offline guarantee.** The intent hands the URL to a *separate* app (the
 * system browser); StudyFeed itself keeps **zero** network capability — no `INTERNET` permission, no
 * networking dependency — so `verifyOfflineGuarantees` still passes. Wrapped in `runCatching` so a device
 * with no browser (or a blocked intent) is a silent no-op, never a crash (`ActivityNotFoundException`).
 */
fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
