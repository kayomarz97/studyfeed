package dev.kayomarz.studyfeed.build

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Wires the offline/no-background guard into the build (BUILD_PROMPT §8 A0).
 *
 * For every variant it registers two checks — merged manifest and dependency denylist — and makes
 * that variant's `assemble` depend on them. The guarantee is therefore not something you have to
 * remember to run: you cannot produce an APK that violates it. `./gradlew verifyOfflineGuarantees`
 * runs the lot, and `check` depends on it.
 *
 * The manifest artifact is wired via `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`, which
 * carries its own implicit task dependency — AGP knows which task produces it. Never reintroduce a
 * hardcoded `dependsOn("processDebugManifest")`: those task names are AGP-internal and change
 * between versions.
 */
class OfflineGuaranteesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val umbrella = project.tasks.register("verifyOfflineGuarantees") {
            group = "verification"
            description =
                "Fails if the app could reach a network or run in the background (BUILD_PROMPT §8 A0)."
        }

        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }

            val manifestCheck = project.tasks.register(
                "check${suffix}MergedManifest",
                CheckMergedManifestTask::class.java,
            ) {
                group = "verification"
                description = "Verifies the ${variant.name} merged manifest declares no network/background."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                variantName.set(variant.name)
            }

            val denylistCheck = project.tasks.register(
                "check${suffix}DependencyDenylist",
                CheckDependencyDenylistTask::class.java,
            ) {
                group = "verification"
                description = "Verifies no forbidden dependency is on the ${variant.name} runtime classpath."
                variantName.set(variant.name)
                rootComponent.set(
                    project.configurations
                        .named("${variant.name}RuntimeClasspath")
                        .flatMap { it.incoming.resolutionResult.rootComponent },
                )
            }

            umbrella.configure { dependsOn(manifestCheck, denylistCheck) }

            // An APK cannot be produced without passing. Scoped to THIS variant so building debug
            // doesn't drag the release manifest/classpath in behind it.
            project.tasks
                .matching { it.name == "assemble$suffix" }
                .configureEach { dependsOn(manifestCheck, denylistCheck) }
        }

        project.tasks
            .matching { it.name == "check" }
            .configureEach { dependsOn(umbrella) }
    }
}
