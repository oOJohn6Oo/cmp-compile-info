package io.jon.cmp.compileinfo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal object CompileInfoSource {
    fun common(resourceClass: String): String = buildString {
        require(resourceClass.isNotBlank()) { "CompileInfo requires the org.jetbrains.compose plugin." }
        appendLine("package io.jon.cmp.compileinfo")
        appendLine()
        appendLine("import androidx.compose.ui.text.AnnotatedString")
        appendLine("import androidx.compose.ui.text.SpanStyle")
        appendLine("import androidx.compose.ui.text.buildAnnotatedString")
        appendLine("import androidx.compose.ui.text.font.FontWeight")
        appendLine("import androidx.compose.ui.text.withStyle")
        appendLine()
        appendLine("object CompileInfo {")
        appendLine("    suspend fun printCompileInfo(): AnnotatedString {")
        appendLine("        val info = platformCompileInfo().toMutableMap()")
        appendLine("""
            info["packageTime"] = try {
                $resourceClass.readBytes("files/compile-info/jon-cmp-package-time.txt").decodeToString().trim()
            } catch (_: org.jetbrains.compose.resources.MissingResourceException) {
                "unavailable (resource not packaged)"
            }
        """.trimIndent().prependIndent("        "))
        appendLine("""
            info.forEach { (key, value) ->
                println("[CompileInfo] ${'$'}key=${'$'}value")
                if (key == "renderer") println()
            }
        """.trimIndent().prependIndent("        "))
        appendLine("""
            return buildAnnotatedString {
                info.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append("\n")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(key) }
                    append("=")
                    append(value)
                    if (key == "renderer") append("\n")
                }
            }
        """.trimIndent().prependIndent("        "))
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("internal expect fun platformCompileInfo(): Map<String, String>")
    }

    fun literal(value: String): String = buildString {
        append('"')
        value.forEach {
            append(when (it) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '$' -> "\\${'$'}"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (it.code < 32) "\\u%04x".format(it.code) else it.toString()
            })
        }
        append('"')
    }

    fun actual(values: Map<String, String>): String = buildString {
        append("package io.jon.cmp.compileinfo\n\n")
        append("internal actual fun platformCompileInfo(): Map<String, String> = linkedMapOf(\n")
        values.forEach { (key, value) -> append("    ${literal(key)} to ${literal(value)},\n") }
        append(")\n")
    }
}

@DisableCachingByDefault(because = "Small stable generated API")
abstract class GenerateCompileInfoApiTask : DefaultTask() {
    @get:Input abstract val resourceClass: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { resourceClass.convention("") }

    @TaskAction fun generate() {
        val file = outputDirectory.file("io/jon/cmp/compileinfo/CompileInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeIfChanged(CompileInfoSource.common(resourceClass.get()))
    }
}

@DisableCachingByDefault(because = "Small generated version information")
abstract class GenerateCompileInfoTask : DefaultTask() {
    @get:Input abstract val platform: Property<String>
    @get:Input abstract val kotlinVersion: Property<String>
    @get:Input abstract val gradleVersion: Property<String>
    @get:Input abstract val cmpVersion: Property<String>
    @get:Input abstract val resolvedCoordinates: ListProperty<String>
    @get:Input @get:Optional abstract val nativeHome: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    // Resolve a custom compiler home on each invocation, but leave unchanged source files intact.
    init { outputs.upToDateWhen { !nativeHome.isPresent } }

    @TaskAction fun generate() {
        val skiko = CompilationInfoResolver.skiko(platform.get(), resolvedCoordinates.get())
        val compiler = nativeHome.orNull?.let { CompilationInfoResolver.nativeCompilerVersion(File(it)) }
            ?: kotlinVersion.get()
        val values = linkedMapOf<String, String>()
        skiko.renderer?.let { values["renderer"] = it }
        values["kotlin"] = compiler
        values["gradle"] = gradleVersion.get()
        values["cmp"] = cmpVersion.get()
        values["skiko"] = skiko.version
        val file = outputDirectory.file("io/jon/cmp/compileinfo/PlatformCompileInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeIfChanged(CompileInfoSource.actual(values))
    }
}

@DisableCachingByDefault(because = "The packaging timestamp must be fresh for every resource build")
abstract class GenerateCompileInfoTimeTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun generate() {
        val file = outputDirectory.file("files/compile-info/jon-cmp-package-time.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n")
    }
}

private fun File.writeIfChanged(content: String) {
    if (!exists() || readText() != content) writeText(content)
}
