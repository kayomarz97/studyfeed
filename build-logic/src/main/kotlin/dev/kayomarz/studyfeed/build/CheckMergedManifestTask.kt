package dev.kayomarz.studyfeed.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails the build if the MERGED manifest contains a network permission or a background component.
 *
 * This is the load-bearing half of BUILD_PROMPT §8 A0's "CI/lint check fails the build if a
 * networking dep/permission or a background component appears". It reads the *merged* manifest —
 * not the one we wrote — so a dependency that injects a component transitively cannot slip past.
 * That is not hypothetical: androidx.profileinstaller injects an exported BroadcastReceiver into
 * every AndroidX app, and it took a merged-manifest read to see it (see app/src/main/AndroidManifest.xml).
 *
 * It parses XML rather than grepping text, on purpose. The first draft of this check was a grep,
 * and it "found" INTERNET in a build that had none — it had matched the warning comment at the top
 * of our own manifest. A guard that cries wolf gets switched off, so: real parser, elements only,
 * comments invisible.
 */
abstract class CheckMergedManifestTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun check() {
        val manifestFile = mergedManifest.get().asFile
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(manifestFile)

        val violations = mutableListOf<String>()

        // ── 1. Permissions ────────────────────────────────────────────────────────────────────
        doc.elementsNamed("uses-permission").forEach { element ->
            val name = element.getAttribute("android:name")
            val reason = FORBIDDEN_PERMISSIONS[name]
                ?: FORBIDDEN_PERMISSION_PREFIXES.entries.firstOrNull { name.startsWith(it.key) }?.value
            if (reason != null) {
                violations += "permission $name — $reason"
            }
        }

        // ── 2. Background components ──────────────────────────────────────────────────────────
        // <service>: never. This is what a WorkManager/JobScheduler/foreground-service addition
        // looks like in a merged manifest, whoever added it.
        doc.elementsNamed("service").forEach { element ->
            violations += "<service> ${element.nameAttr()} — the app must do no background work (§12)"
        }

        // <receiver>: never. Not even a benign one; see the manifest note on ProfileInstallReceiver.
        doc.elementsNamed("receiver").forEach { element ->
            violations += "<receiver> ${element.nameAttr()} — no component may run while the app is closed (§12)"
        }

        // <provider>: only the AndroidX startup initializer, which is not background work — it runs
        // in-process at app launch and schedules nothing. Anything else is a real finding.
        doc.elementsNamed("provider").forEach { element ->
            val name = element.nameAttr()
            if (name !in ALLOWED_PROVIDERS) {
                violations += "<provider> $name — not on the allowlist; providers can start the app process"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("StudyFeed offline/no-background guarantee VIOLATED in the merged manifest.")
                    appendLine("Variant : ${variantName.get()}")
                    appendLine("Manifest: $manifestFile")
                    appendLine()
                    violations.forEach { appendLine("  ✗ $it") }
                    appendLine()
                    appendLine("This app ships with no network access and no background execution — that is a")
                    appendLine("product guarantee (BUILD_PROMPT §0, §8 A0, §12), not a lint preference.")
                    appendLine()
                    appendLine("If a DEPENDENCY injected this, you can strip it in app/src/main/AndroidManifest.xml:")
                    appendLine("    <service android:name=\"the.offending.Name\" tools:node=\"remove\" />")
                    appendLine("To find who pulls it in:")
                    appendLine("    ./gradlew :app:dependencies --configuration ${variantName.get()}RuntimeClasspath")
                    appendLine("If you genuinely intend to add it, that is a conversation with the user first.")
                }
            )
        }

        logger.lifecycle(
            "✓ ${variantName.get()} merged manifest: no network permission, no background component."
        )
    }

    private fun Element.nameAttr(): String =
        getAttribute("android:name").ifEmpty { "(unnamed)" }

    private fun org.w3c.dom.Document.elementsNamed(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private companion object {
        /**
         * Exact-match permissions. Each carries WHY, because a guard that only says "denied"
         * teaches nobody and gets deleted by the next person in a hurry.
         */
        val FORBIDDEN_PERMISSIONS = mapOf(
            "android.permission.INTERNET"
                to "the shipped app has zero network — everything is pre-compiled into the pack (§0)",
            "android.permission.ACCESS_NETWORK_STATE"
                to "nothing may depend on network state in an offline app",
            "android.permission.ACCESS_WIFI_STATE"
                to "nothing may depend on network state in an offline app",
            "android.permission.WAKE_LOCK"
                to "the app must never hold the device awake (§8 A0)",
            "android.permission.RECEIVE_BOOT_COMPLETED"
                to "no boot receiver — the app only runs when opened (§8 A0)",
            "android.permission.SCHEDULE_EXACT_ALARM"
                to "no alarms; resurfacing happens in-app on next open (§12)",
            "android.permission.USE_EXACT_ALARM"
                to "no alarms; resurfacing happens in-app on next open (§12)",
            "android.permission.POST_NOTIFICATIONS"
                to "re-reads resurface in-app, never as a notification (§8 A2)",
        )

        /** Prefix matches — FOREGROUND_SERVICE has a growing family of typed variants. */
        val FORBIDDEN_PERMISSION_PREFIXES = mapOf(
            "android.permission.FOREGROUND_SERVICE"
                to "no services of any kind (§8 A0)",
        )

        /**
         * The ONLY tolerated provider, with its justification. Do not add to this list without the
         * user's say-so — every entry is a hole in the guarantee.
         *
         * androidx.startup.InitializationProvider: arrives transitively via emoji2 and
         * lifecycle-process. It is exported="false", runs only when the app process is already
         * starting, and schedules nothing — it is app startup, not background execution. Removing
         * it would also take ProcessLifecycleOwner with it, which A2 needs to detect "next session"
         * for re-read scheduling.
         */
        val ALLOWED_PROVIDERS = setOf(
            "androidx.startup.InitializationProvider",
        )
    }
}
