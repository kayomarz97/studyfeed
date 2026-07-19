package dev.kayomarz.studyfeed.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a forbidden dependency appears anywhere on a variant's runtime classpath,
 * including transitively.
 *
 * This is the second half of §8 A0's guard, and it is deliberately a DENYLIST rather than an
 * allowlist — a stated compromise, not an oversight. An allowlist would be stricter, but it would
 * have to be edited for every legitimate AndroidX addition through A1–A7, and a guard that
 * obstructs ordinary work gets disabled. The merged-manifest check ([CheckMergedManifestTask]) is
 * the real backstop and IS an allowlist: a library cannot open a socket or run in the background
 * without a permission or component surfacing there. This check exists to catch the mistake early
 * and name it clearly, at the point where someone adds the dependency line.
 *
 * Config-cache safe: the task holds a lazy Provider<ResolvedComponentResult>, never a
 * Configuration object (Gradle forbids referencing resolved dependency results eagerly).
 */
abstract class CheckDependencyDenylistTask : DefaultTask() {

    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun check() {
        val seen = mutableSetOf<String>()
        val violations = mutableListOf<String>()

        fun walk(component: ResolvedComponentResult, path: List<String>) {
            val id = component.moduleVersion ?: return
            val coordinate = "${id.group}:${id.name}"
            if (!seen.add(coordinate)) return

            DENYLIST.entries.firstOrNull { (pattern, _) -> coordinate.matchesDenylist(pattern) }
                ?.let { (_, reason) ->
                    val via = if (path.size > 1) "\n      pulled in via: ${path.joinToString(" -> ")}" else ""
                    violations += "$coordinate:${id.version} — $reason$via"
                }

            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { walk(it.selected, path + coordinate) }
        }

        walk(rootComponent.get(), emptyList())

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("StudyFeed offline guarantee VIOLATED on the ${variantName.get()} runtime classpath.")
                    appendLine()
                    violations.forEach { appendLine("  ✗ $it") }
                    appendLine()
                    appendLine("The shipped app has no network stack and no background scheduler, by design")
                    appendLine("(BUILD_PROMPT §0 runtime: fully_offline_no_tokens, §8 A0, §12).")
                    appendLine("Nothing in this app should need any of the above — if you believe it does,")
                    appendLine("that is a design conversation with the user, not a dependency line.")
                }
            )
        }

        logger.lifecycle("✓ ${variantName.get()} runtime classpath: ${seen.size} modules, none forbidden.")
    }

    /** Supports a trailing `*` wildcard on the module segment, e.g. `com.google.firebase:*`. */
    private fun String.matchesDenylist(pattern: String): Boolean =
        if (pattern.endsWith(":*")) startsWith(pattern.removeSuffix("*")) else this == pattern

    private companion object {
        /**
         * group:module -> why it is forbidden. `:*` matches any module in the group.
         *
         * NOTE what is deliberately ABSENT: Jackson (com.fasterxml.jackson.*). It arrives as a
         * transitive of java-fsrs and is a serialization library, not a network one — denying it
         * would break FSRS scheduling for no security gain. Precision matters: a denylist that
         * flags innocent libraries trains people to ignore it.
         */
        val DENYLIST = mapOf(
            // ── HTTP / networking ──
            "com.squareup.okhttp3:*" to "HTTP client — the app must never reach a network",
            "com.squareup.retrofit2:*" to "HTTP client — the app must never reach a network",
            "io.ktor:*" to "HTTP client — the app must never reach a network",
            "com.android.volley:*" to "HTTP client — the app must never reach a network",
            "org.apache.httpcomponents:*" to "HTTP client — the app must never reach a network",
            "com.google.android.gms:*" to "Play Services — phones home; the app is fully offline",
            "com.google.firebase:*" to "Firebase — network + analytics; the app is fully offline",

            // ── Background execution ──
            "androidx.work:*" to "WorkManager — the app must do no background work (§8 A0)",

            // ── AI SDKs: everything is pre-compiled at build time; the app never generates (§12) ──
            "com.anthropic:*" to "AI SDK — no realtime generation in the app, ever (§12)",
            "com.openai:*" to "AI SDK — no realtime generation in the app, ever (§12)",
            "com.aallam.openai:*" to "AI SDK — no realtime generation in the app, ever (§12)",
        )
    }
}
