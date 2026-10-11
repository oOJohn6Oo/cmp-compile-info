package io.jon.cmp.compileinfo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CompilationInfoResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `CMP version comes from the loaded Compose plugin`() {
        assertEquals("1.12.0", CompilationInfoResolver.cmpPluginVersion(
            org.jetbrains.compose.ComposePlugin::class.java.classLoader
        ))
    }

    @Test fun `Android without Skiko does not invent a dependency`() {
        assertEquals(SkikoInfo("not-used"), CompilationInfoResolver.skiko("android", listOf("androidx.compose.ui:ui:1.12.0")))
    }

    @Test fun `iOS uses concrete target version instead of metadata root`() {
        assertEquals(SkikoInfo("0.9.2"), CompilationInfoResolver.skiko("ios", listOf(
            "org.jetbrains.skiko:skiko:0.9.1", "org.jetbrains.skiko:skiko-iosarm64:0.9.2")))
    }

    @Test fun `OHOS full and Overlay select appropriate renderer`() {
        assertEquals(SkikoInfo("0.150.1", "skia"), CompilationInfoResolver.skiko("ohos", listOf("org.jetbrains.skiko:skiko-ohosarm64:0.150.1")))
        assertEquals(SkikoInfo("0.150.1", "fusion-renderer"), CompilationInfoResolver.skiko("ohos", listOf(
            "org.jetbrains.skiko:skiko-ohosarm64-api:0.150.1", "org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:0.150.1")))
    }

    @Test fun `Overlay version mismatch fails with both coordinates`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CompilationInfoResolver.skiko("ohos", listOf(
                "org.jetbrains.skiko:skiko-ohosarm64-api:1", "org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:2"))
        }
        assertTrue(error.message!!.contains("skiko-ohosarm64-api:1"))
        assertTrue(error.message!!.contains("skiko-ohosarm64-fusionrenderer:2"))
    }

    @Test fun `API only or two backends must not print a misleading renderer`() {
        assertThrows(IllegalStateException::class.java) {
            CompilationInfoResolver.skiko("ohos", listOf("org.jetbrains.skiko:skiko-ohosarm64-api:1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompilationInfoResolver.skiko("ohos", listOf(
                "org.jetbrains.skiko:skiko-ohosarm64:1", "org.jetbrains.skiko:skiko-ohosarm64-fusionrenderer:1"))
        }
    }

    @Test fun `custom Native home reads compiler not folder name`() {
        val home = temporary.newFolder("not-a-version")
        val jar = home.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        jar.parentFile.mkdirs()
        JarOutputStream(jar.outputStream()).use {
            it.putNextEntry(JarEntry("META-INF/compiler.version"))
            it.write("2.4.20-custom".toByteArray())
            it.closeEntry()
        }
        assertEquals("2.4.20-custom", CompilationInfoResolver.nativeCompilerVersion(home))
        assertThrows(IllegalStateException::class.java) {
            CompilationInfoResolver.nativeCompilerVersion(temporary.newFolder())
        }
    }

    @Test fun `generated Kotlin literals escape interpolation and controls`() {
        assertEquals("\"a\\\"b\\\\c\\${'$'}name\\n\\r\\t\\u0001\"", CompileInfoSource.literal("a\"b\\c${'$'}name\n\r\t\u0001"))
    }
}
