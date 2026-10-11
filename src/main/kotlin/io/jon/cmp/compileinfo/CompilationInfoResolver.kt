package io.jon.cmp.compileinfo

import java.io.File
import java.util.jar.JarFile

internal data class SkikoInfo(val version: String, val renderer: String? = null)

internal object CompilationInfoResolver {
    fun cmpPluginVersion(classLoader: ClassLoader): String =
        classLoader.loadClass("org.jetbrains.compose.ComposeBuildConfig")
            .getField("composeGradlePluginVersion").get(null) as String

    fun skiko(platform: String, coordinates: List<String>): SkikoInfo {
        val modules = coordinates.map { it.split(':', limit = 3) }
            .filter { it.size == 3 && (it[0] == "org.jetbrains.skiko" || it[0].startsWith("org.jetbrains.skiko.")) }
        val platformModules = modules.filter {
            when (platform) {
                "ohos" -> it[1].contains("ohos")
                "ios" -> it[1].contains("ios")
                else -> true
            }
        }
        // KMP root modules can redirect to target artifacts. Prefer those concrete artifacts.
        val selected = platformModules.ifEmpty { modules.filter { it[1] == "skiko" } }
        if (selected.isEmpty()) return SkikoInfo("not-used")
        val versions = selected.map { it[2] }.distinct()
        require(versions.size == 1) {
            "Skiko versions differ for $platform: ${selected.joinToString { it.joinToString(":") }}"
        }
        val renderer = if (platform == "ohos") {
            val fusion = selected.any { it[1].contains("fusion", ignoreCase = true) || it[0].contains("fusion") }
            val api = selected.any { it[1].endsWith("-api") }
            val full = selected.any { it[0] == "org.jetbrains.skiko" && it[1].matches(Regex("skiko-ohos(?:arm64|x64)")) }
            require(!(fusion && full)) { "Both Skia full and Fusion are selected: ${selected.joinToString { it.joinToString(":") }}" }
            when {
                fusion -> "fusion-renderer"
                full -> "skia"
                api -> error("Only the Skiko API is selected; no OHOS renderer implementation was resolved.")
                else -> error("Cannot identify the OHOS renderer from: ${selected.joinToString { it.joinToString(":") }}")
            }
        } else null
        return SkikoInfo(versions.single(), renderer)
    }

    fun nativeCompilerVersion(home: File): String {
        val lib = home.resolve("konan/lib")
        val candidates = listOf("kotlin-native-compiler-embeddable.jar", "kotlin-native.jar")
        for (name in candidates) {
            val jar = lib.resolve(name)
            if (!jar.isFile) continue
            JarFile(jar).use {
                val entry = it.getJarEntry("META-INF/compiler.version")
                val version = if (entry != null) it.getInputStream(entry).bufferedReader().use { reader -> reader.readText().trim() }
                else it.manifest?.mainAttributes?.getValue("Implementation-Version")?.trim()
                if (!version.isNullOrBlank()) return version
            }
        }
        error("Cannot read the Kotlin/Native compiler version from $home. Expected konan/lib compiler JAR with META-INF/compiler.version or Implementation-Version.")
    }
}
