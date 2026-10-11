buildscript {
    if (providers.gradleProperty("mavenCentralPublishing").getOrElse("false").toBoolean()) {
        repositories {
            mavenCentral()
        }
        dependencies {
            classpath("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.20-0.1.0-02"
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.oojohn6oo"
version = "0.1.1"

if (providers.gradleProperty("mavenCentralPublishing").getOrElse("false").toBoolean()) {
    apply(plugin = "com.vanniktech.maven.publish")
    apply(from = "maven-central.gradle")
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
}

kotlin { jvmToolchain(21) }

val kotlinPluginVersion = "2.4.20-0.1.0-02"
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinPluginVersion")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:1.12.0")
    testImplementation("org.jetbrains.compose:compose-gradle-plugin:1.12.0")
    testImplementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinPluginVersion")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinPluginVersion")
    testRuntimeOnly("com.android.tools.build:gradle:9.1.0")
}

gradlePlugin {
    plugins {
        create("compileInfo") {
            id = "io.github.oojohn6oo.cmp.compile-info"
            implementationClass = "io.jon.cmp.compileinfo.CompileInfoPlugin"
            displayName = "Multiplatform compile info"
            description = "Generates a single printCompileInfo API with target-specific build information."
        }
    }
}

tasks.test {
    useJUnit()
    exclude("**/*FunctionalTest.class")
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register<Test>("functionalTest") {
    group = "verification"
    description = "Run full Gradle integration tests, including compilation and resource packaging."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*FunctionalTest.class")
    useJUnit()
    maxParallelForks = 1
    maxHeapSize = "1g"
    systemProperty("fixture.kotlinVersion", kotlinPluginVersion)
    // Real Kotlin/Android plugins are applied by TestKit; they are not published with this plugin.
    systemProperty("fixture.pluginClasspath", configurations.testRuntimeClasspath.get().asPath)
    testLogging { events("passed", "skipped", "failed") }
}
