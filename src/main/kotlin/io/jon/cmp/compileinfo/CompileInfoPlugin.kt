@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class, org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package io.jon.cmp.compileinfo

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.Copy
import org.jetbrains.compose.resources.ResourcesExtension
import org.gradle.api.Transformer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader

abstract class CompileInfoExtension {
    abstract val resourcesDirectory: DirectoryProperty
}

class CompileInfoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("compileInfo", CompileInfoExtension::class.java)
        extension.resourcesDirectory.convention(project.layout.projectDirectory.dir("src/commonMain/composeResources"))
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configure(project)
        }
    }

    private fun configure(project: Project) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val gradleVersion = project.gradle.gradleVersion
        val api = project.tasks.register("generateCompileInfoApi", GenerateCompileInfoApiTask::class.java) {
            it.group = "compile info"
            it.description = "Generate the common CompileInfo.printCompileInfo() API."
            it.outputDirectory.set(project.layout.buildDirectory.dir("generated/compileInfo/common"))
        }
        kotlin.sourceSets.named("commonMain") { it.kotlin.srcDir(api) }

        project.pluginManager.withPlugin("org.jetbrains.compose") {
            val resources = (project.extensions.getByName("compose") as ExtensionAware)
                .extensions.getByType(ResourcesExtension::class.java)
            val timestamp = project.tasks.register("generateCompileInfoTime", GenerateCompileInfoTimeTask::class.java) {
                it.group = "compile info"
                it.description = "Write packaging time to a resource without changing Kotlin sources."
                it.outputDirectory.set(project.layout.buildDirectory.dir("generated/compileInfo/timeResources"))
            }
            val extension = project.extensions.getByType(CompileInfoExtension::class.java)
            val combinedResources = project.tasks.register("prepareCompileInfoResources", Sync::class.java) {
                it.from(extension.resourcesDirectory)
                it.from(timestamp.flatMap { task -> task.outputDirectory })
                it.into(project.layout.buildDirectory.dir("generated/compileInfo/composeResources"))
                it.duplicatesStrategy = DuplicatesStrategy.FAIL
            }
            // Merge in build/, leaving the user's resources and Kotlin source-set hierarchy unchanged.
            resources.customDirectory("commonMain", project.layout.dir(combinedResources.map { it.destinationDir }))
            val resourcePackage = project.provider {
                resources.packageOfResClass.ifEmpty {
                    fun identifier(value: String) = value.lowercase().replace('-', '_')
                        .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }
                    val prefix = listOf(identifier(project.group.toString()), identifier(project.name))
                        .filter { it.isNotEmpty() }.joinToString(".")
                    "$prefix.generated.resources"
                }
            }
            api.configure { task ->
                task.resourceClass.set(resourcePackage.map { "$it.${resources.nameOfResClass}" })
            }
            // Existing application tasks copy libkn.so and raw resources into the Harmony project.
            project.tasks.withType(Copy::class.java).configureEach { copy ->
                if (copy.name.matches(Regex("publish(?:Debug|Release)BinariesToHarmonyApp"))) {
                    copy.from(timestamp.flatMap { it.outputDirectory }) {
                        it.into(resourcePackage.map { pkg -> "entry/src/main/resources/rawfile/composeResources/$pkg" })
                    }
                }
            }
        }

        kotlin.targets.configureEach { target ->
            if (target.platformType == KotlinPlatformType.common) return@configureEach
            val platform = when {
                target is KotlinNativeTarget -> when {
                    target.konanTarget.name.startsWith("ohos_") -> "ohos"
                    target.konanTarget.name.startsWith("ios_") -> "ios"
                    else -> throw GradleException("CompileInfo does not support Native target ${target.konanTarget.name}.")
                }
                target.platformType == KotlinPlatformType.androidJvm -> "android"
                target.platformType == KotlinPlatformType.jvm -> "jvm"
                else -> throw GradleException("CompileInfo does not support target ${target.name} (${target.platformType}).")
            }
            target.compilations.configureEach compilation@{ compilation ->
                val name = compilation.name
                if (platform == "android") {
                    if (name == "test" || name.endsWith("UnitTest") || name.endsWith("AndroidTest")) return@compilation
                } else if (name != "main") return@compilation

                val suffix = target.name.replaceFirstChar { it.uppercaseChar() } + name.replaceFirstChar { it.uppercaseChar() }
                val configuration = project.configurations.named(compilation.compileDependencyConfigurationName)
                val coordinates = configuration.flatMap { it.incoming.resolutionResult.rootComponent }.map(ResolvedCoordinates())
                val generator = project.tasks.register("generate${suffix}CompileInfo", GenerateCompileInfoTask::class.java) { task ->
                    task.group = "compile info"
                    task.description = "Generate compile info for ${target.name}/$name."
                    task.platform.set(platform)
                    task.gradleVersion.set(gradleVersion)
                    task.resolvedCoordinates.set(coordinates)
                    task.outputDirectory.set(project.layout.buildDirectory.dir("generated/compileInfo/${target.name}/$name"))
                    if (target is KotlinNativeTarget) {
                        task.kotlinVersion.set(project.providers.gradleProperty("kotlin.native.version")
                            .orElse(project.providers.gradleProperty("org.jetbrains.kotlin.native.version"))
                            .orElse(NativeCompilerDownloader.DEFAULT_KONAN_VERSION))
                        // KGP gives konan.data.dir priority over a user-provided native home.
                        if (!project.providers.gradleProperty("konan.data.dir").isPresent) {
                            task.nativeHome.set(project.providers.gradleProperty("kotlin.native.home")
                                .orElse(project.providers.gradleProperty("org.jetbrains.kotlin.native.home")))
                        }
                    } else {
                        task.kotlinVersion.set(kotlin.compilerVersion)
                    }
                }
                compilation.defaultSourceSet.kotlin.srcDir(generator)
            }
        }
    }
}

/** Pass only coordinates into tasks; never keep a Project/Configuration in a task action. */
internal class ResolvedCoordinates : Transformer<List<String>, ResolvedComponentResult> {
    override fun transform(root: ResolvedComponentResult): List<String> {
        val result = sortedSetOf<String>()
        val seen = mutableSetOf<Any>()
        val pending = ArrayDeque<ResolvedComponentResult>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!seen.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { result += "${it.group}:${it.module}:${it.version}" }
            component.dependencies.forEach {
                when (it) {
                    is ResolvedDependencyResult -> pending.add(it.selected)
                    is UnresolvedDependencyResult -> throw GradleException("CompileInfo cannot resolve ${it.attempted.displayName}.", it.failure)
                }
            }
        }
        return result.toList()
    }
}
