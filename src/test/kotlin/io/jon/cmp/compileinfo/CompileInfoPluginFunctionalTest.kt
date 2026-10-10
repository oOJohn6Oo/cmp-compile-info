package io.jon.cmp.compileinfo

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime
import java.util.jar.JarOutputStream

class CompileInfoPluginFunctionalTest {
    @get:Rule val temporary = TemporaryFolder()

    private val kotlinVersion = System.getProperty("fixture.kotlinVersion")
    private val gradleVersion = GradleVersion.current().version

    private fun fixture(script: String, properties: String = ""): File {
        val dir = temporary.newFolder()
        dir.resolve("settings.gradle").writeText("rootProject.name = 'compile-info-fixture'\n")
        dir.resolve("gradle.properties").writeText("""
            org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
            org.gradle.workers.max=2
            kotlin.stdlib.default.dependency=false
            kotlin.native.ignoreDisabledTargets=true
            kotlin.mpp.applyDefaultHierarchyTemplate=false
            $properties
        """.trimIndent())
        dir.resolve("build.gradle").writeText(script)
        return dir
    }

    private fun runner(dir: File, vararg tasks: String): GradleRunner {
        val cp = System.getProperty("fixture.pluginClasspath").split(File.pathSeparator).map(::File)
        return GradleRunner.create().withProjectDir(dir).withPluginClasspath(pluginClasspath() + cp)
            .withArguments(*tasks, "--stacktrace", "--console=plain", "--max-workers=2")
    }

    private fun pluginClasspath(): List<File> {
        val properties = java.util.Properties()
        javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { properties.load(it) }
        return properties.getProperty("implementation-classpath").split(File.pathSeparator).map(::File)
    }

    private fun artifact(dir: File, name: String, version: String) {
        val base = dir.resolve("repo/org/jetbrains/skiko/$name/$version")
        base.mkdirs()
        base.resolve("$name-$version.pom").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>org.jetbrains.skiko</groupId>
            <artifactId>$name</artifactId><version>$version</version></project>
        """.trimIndent())
        JarOutputStream(base.resolve("$name-$version.jar").outputStream()).close()
    }

    private fun actual(dir: File, target: String, compilation: String = "main") =
        dir.resolve("build/generated/compileInfo/$target/$compilation/io/jon/cmp/compileinfo/PlatformCompileInfo.kt")

    @Test fun `OHOS substitution and force use resolved versions and do not resolve iOS`() {
        val dir = fixture("""
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'io.github.oojohn6oo.cmp.compile-info'
            }
            repositories { maven { url = uri('repo') } }
            kotlin {
                ohosArm64()
                iosArm64()
                sourceSets {
                    ohosArm64Main.dependencies {
                        implementation 'org.jetbrains.skiko:skiko-ohosarm64:1'
                        implementation 'org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:1'
                    }
                    iosArm64Main.dependencies { implementation 'missing:unresolvable-ios-only:1' }
                }
            }
            configurations.configureEach {
                if (name.toLowerCase().contains('ohos')) {
                    resolutionStrategy.dependencySubstitution {
                        substitute module('org.jetbrains.skiko:skiko-ohosarm64') using module('org.jetbrains.skiko:skiko-ohosarm64-api:2')
                    }
                    resolutionStrategy.force 'org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:2'
                }
            }
        """.trimIndent())
        artifact(dir, "skiko-ohosarm64-api", "2")
        artifact(dir, "skiko-ohosarm64-fusionrenderer", "2")
        val result = runner(dir, "generateOhosArm64MainCompileInfo").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateOhosArm64MainCompileInfo")!!.outcome)
        val source = actual(dir, "ohosArm64").readText()
        assertTrue(source.contains("\"skiko\" to \"2\""))
        assertTrue(source.contains("\"gradle\" to \"$gradleVersion\""))
        assertTrue(source.contains("\"renderer\" to \"fusion-renderer\""))
        assertFalse(actual(dir, "iosArm64").exists())
    }

    @Test fun `Overlay mismatch fails the generation task`() {
        val dir = fixture("""
            plugins { id 'org.jetbrains.kotlin.multiplatform'; id 'io.github.oojohn6oo.cmp.compile-info' }
            repositories { maven { url = uri('repo') } }
            kotlin {
                ohosArm64()
                sourceSets.ohosArm64Main.dependencies {
                    implementation 'org.jetbrains.skiko:skiko-ohosarm64-api:1'
                    implementation 'org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:2'
                }
            }
        """.trimIndent())
        artifact(dir, "skiko-ohosarm64-api", "1")
        artifact(dir, "skiko-ohosarm64-fusionrenderer", "2")
        val result = runner(dir, "generateOhosArm64MainCompileInfo").buildAndFail()
        assertTrue(result.output.contains("Skiko versions differ for ohos"))
    }

    @Test fun `native override and iOS targets have independent generated sources`() {
        val dir = fixture("""
            plugins { id 'org.jetbrains.kotlin.multiplatform'; id 'io.github.oojohn6oo.cmp.compile-info' }
            repositories { maven { url = uri('repo') } }
            kotlin {
                iosArm64()
                iosSimulatorArm64()
                sourceSets {
                    iosArm64Main.dependencies { implementation 'org.jetbrains.skiko:skiko-iosarm64:1' }
                    iosSimulatorArm64Main.dependencies { implementation 'org.jetbrains.skiko:skiko-iossimulatorarm64:2' }
                }
            }
            tasks.register('verifyWiring') {
                doLast {
                    kotlin.targets.findAll { it.name.startsWith('ios') }.each { target ->
                        def main = target.compilations.getByName('main')
                        assert main.defaultSourceSet.kotlin.srcDirs.any { it.path.endsWith("generated/compileInfo/" + target.name + "/main") }
                        assert main.compileTaskProvider.get().taskDependencies.getDependencies(main.compileTaskProvider.get()).any { it.name.endsWith('CompileInfo') }
                    }
                }
            }
        """.trimIndent(), "kotlin.native.version=2.4.20-custom-test")
        artifact(dir, "skiko-iosarm64", "1")
        artifact(dir, "skiko-iossimulatorarm64", "2")
        runner(dir, "generateIosArm64MainCompileInfo", "generateIosSimulatorArm64MainCompileInfo", "verifyWiring").build()
        val device = actual(dir, "iosArm64").readText()
        val simulator = actual(dir, "iosSimulatorArm64").readText()
        assertTrue(device.contains("\"kotlin\" to \"2.4.20-custom-test\""))
        assertTrue(device.contains("\"gradle\" to \"$gradleVersion\""))
        assertFalse(device.contains("kotlin.gradlePlugin"))
        assertTrue(device.contains("\"skiko\" to \"1\""))
        assertTrue(simulator.contains("\"skiko\" to \"2\""))
    }

    @Test fun `configuration cache reuse refreshes time and generated API compiles and prints`() {
        val dir = fixture("""
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'org.jetbrains.compose'
                id 'org.jetbrains.kotlin.plugin.compose'
                id 'io.github.oojohn6oo.cmp.compile-info'
            }
            compose.resources { packageOfResClass = 'fixture.resources'; nameOfResClass = 'DemoResources' }
            compileInfo { resourcesDirectory = layout.projectDirectory.dir('originalResources') }
            tasks.register('publishDebugBinariesToHarmonyApp', Copy) { into(layout.buildDirectory.dir('harmony')) }
            repositories {
                maven { url = uri('https://maven.eazytec-cloud.com/nexus/repository/maven-public/') }
                google()
                mavenCentral()
            }
            kotlin {
                jvm()
                sourceSets.commonMain.dependencies {
                    implementation 'org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion'
                    implementation compose.components.resources
                    implementation compose.runtime
                    implementation 'org.jetbrains.compose.ui:ui-text:1.12.0'
                }
            }
            tasks.register('runSample', JavaExec) {
                def main = kotlin.targets.jvm.compilations.main
                classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
                mainClass = 'MainKt'
            }
        """.trimIndent())
        dir.resolve("originalResources/files/existing.txt").apply {
            parentFile.mkdirs()
            writeText("existing resource")
        }
        dir.resolve("src/jvmMain/kotlin/Main.kt").apply {
            parentFile.mkdirs()
            writeText("""
                suspend fun main() {
                    val message: androidx.compose.ui.text.AnnotatedString = io.jon.cmp.compileinfo.CompileInfo.printCompileInfo()
                    check(message.text.startsWith("kotlin=$kotlinVersion\n"))
                    check(message.text.contains("\ngradle=$gradleVersion\n"))
                    check(!message.text.contains("kotlin.gradlePlugin"))
                    check(message.text.contains("\nskiko="))
                    check(message.text.contains("\npackageTime="))
                    check(!message.text.contains("platform="))
                    check(!message.text.contains("[CompileInfo]"))
                    val lines = message.text.lines()
                    check(message.spanStyles.size == lines.size)
                    var offset = 0
                    lines.forEachIndexed { index, line ->
                        val span = message.spanStyles[index]
                        check(span.start == offset)
                        check(span.end == offset + line.indexOf('='))
                        check(span.item == androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        offset += line.length + 1
                    }
                    check(fixture.resources.DemoResources.readBytes("files/existing.txt").decodeToString() == "existing resource")
                }
            """.trimIndent())
        }
        val first = runner(dir, "runSample", "publishDebugBinariesToHarmonyApp", "--configuration-cache").build()
        assertFalse(first.output.contains("[CompileInfo] platform="))
        assertTrue(first.output.contains("[CompileInfo] kotlin=$kotlinVersion"))
        assertTrue(first.output.contains("[CompileInfo] skiko="))
        val before = actual(dir, "jvm").readText()
        val second = runner(dir, "runSample", "publishDebugBinariesToHarmonyApp", "--configuration-cache").build()
        assertTrue(second.output.contains("Reusing configuration cache."))
        val after = actual(dir, "jvm").readText()
        assertEquals(before, after)
        val firstTimestamp = Regex("\\[CompileInfo] packageTime=(.+)").find(first.output)!!.groupValues[1].trim()
        assertNotEquals(firstTimestamp, Regex("\\[CompileInfo] packageTime=(.+)").find(second.output)!!.groupValues[1].trim())
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileKotlinJvm")!!.outcome)
        val timestamp = Regex("\\[CompileInfo] packageTime=(.+)").find(second.output)!!.groupValues[1].trim()
        OffsetDateTime.parse(timestamp)
        val harmonyTime = dir.resolve("build/harmony/entry/src/main/resources/rawfile/composeResources/fixture.resources/files/compile-info/jon-cmp-package-time.txt")
        assertEquals(timestamp, harmonyTime.readText().trim())
        assertEquals("existing resource", dir.resolve("originalResources/files/existing.txt").readText())
        assertFalse(dir.resolve("originalResources/files/compile-info").exists())
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateJvmMainCompileInfo")!!.outcome)
    }

    @Test fun `iOS resources include time without changing default hierarchy`() {
        val dir = fixture("""
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'org.jetbrains.compose'
                id 'org.jetbrains.kotlin.plugin.compose'
                id 'io.github.oojohn6oo.cmp.compile-info'
            }
            compose.resources { packageOfResClass = 'fixture.ios' }
            kotlin { iosArm64(); iosSimulatorArm64() }
            tasks.register('verifyHierarchy') {
                doLast {
                    assert kotlin.sourceSets.getByName('iosArm64Main').dependsOn.contains(kotlin.sourceSets.getByName('iosMain'))
                    assert kotlin.sourceSets.getByName('iosSimulatorArm64Main').dependsOn.contains(kotlin.sourceSets.getByName('iosMain'))
                }
            }
        """.trimIndent(), "kotlin.mpp.applyDefaultHierarchyTemplate=true")
        dir.resolve("src/commonMain/composeResources/files/existing.txt").apply {
            parentFile.mkdirs()
            writeText("iOS resource")
        }
        runner(dir, "assembleIosArm64MainResources", "assembleIosSimulatorArm64MainResources", "verifyHierarchy").build()
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val files = dir.resolve("build/generated/compose/resourceGenerator/assembledResources/${target}Main/composeResources/fixture.ios/files")
            OffsetDateTime.parse(files.resolve("compile-info/jon-cmp-package-time.txt").readText().trim())
            assertEquals("iOS resource", files.resolve("existing.txt").readText())
        }
    }

    @Test fun `real Android plugin wires debug and release separately and excludes tests`() {
        val sdk = System.getenv("ANDROID_HOME") ?: File(System.getProperty("user.home"), "Library/Android/sdk").path
        check(File(sdk, "platforms/android-36").isDirectory) { "Android SDK 36 is required for the Android integration test (no APK is built)." }
        val dir = fixture("""
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'com.android.library'
                id 'org.jetbrains.compose'
                id 'org.jetbrains.kotlin.plugin.compose'
                id 'io.github.oojohn6oo.cmp.compile-info'
            }
            compose.resources { packageOfResClass = 'fixture.android' }
            kotlin { androidTarget() }
            android { namespace = 'fixture.compileinfo'; compileSdk = 36 }
            tasks.register('verifyAndroidWiring') {
                doLast {
                    ['debug', 'release'].each { name ->
                        def main = kotlin.targets.android.compilations.getByName(name)
                        assert main.defaultSourceSet.kotlin.srcDirs.any { it.path.endsWith('generated/compileInfo/android/' + name) }
                        assert main.compileTaskProvider.get().taskDependencies.getDependencies(main.compileTaskProvider.get()).any { it.name.endsWith('CompileInfo') }
                    }
                    assert !tasks.names.any { it.endsWith('UnitTestCompileInfo') || it.endsWith('AndroidTestCompileInfo') }
                }
            }
        """.trimIndent(), "android.builtInKotlin=false\nandroid.newDsl=false")
        dir.resolve("local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        runner(dir, "generateAndroidDebugCompileInfo", "generateAndroidReleaseCompileInfo", "verifyAndroidWiring",
            "copyDebugComposeResourcesToAndroidAssets", "copyReleaseComposeResourcesToAndroidAssets").build()
        val packagedTimes = dir.resolve("build").walkTopDown().filter {
            it.isFile && it.name == "jon-cmp-package-time.txt" && it.path.contains("composeResources/fixture.android/")
        }.toList()
        assertEquals(2, packagedTimes.size)
        packagedTimes.forEach { OffsetDateTime.parse(it.readText().trim()) }
        for (variant in listOf("debug", "release")) {
            val source = actual(dir, "android", variant).readText()
            assertFalse(source.contains("\"platform\" to"))
            assertTrue(source.contains("\"skiko\" to \"not-used\""))
        }
    }
}
