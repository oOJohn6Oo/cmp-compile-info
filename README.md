# CMP Compile Info

Compose Multiplatform 编译信息插件，支持 Android、iOS、OHOS 和 JVM。收集构建版本和打包时间，用于日志输出和应用内展示。

## 接入

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}
```

KMP 模块的 `build.gradle.kts`：

```kotlin
plugins {
    id("io.github.oojohn6oo.cmp.compile-info") version "0.1.0"
}
```

依赖：`org.jetbrains.compose` 插件、`compose.ui`、`compose.components.resources`。

## 使用

`CompileInfo.printCompileInfo()` 输出 `[CompileInfo] key=value` 日志，返回 `AnnotatedString`，key 加粗，value 使用默认样式。

```kotlin
@Composable
fun CompileInfoText() {
    var info by remember { mutableStateOf(AnnotatedString("")) }

    LaunchedEffect(Unit) {
        info = CompileInfo.printCompileInfo()
    }

    Text(info)
}
```

## 收集信息

| 字段 | 内容 |
| --- | --- |
| `renderer` | OHOS 渲染后端，单独显示在首行。 |
| `kotlin` | Kotlin 编译器版本。 |
| `gradle` | Gradle 版本。 |
| `skiko` | 当前平台解析到的 Skiko 版本；未使用时为 `not-used`。 |
| `packageTime` | 打包时间，包含时区。 |

## 资源目录

`resourcesDirectory` 指定项目资源的读取目录，默认为 `src/commonMain/composeResources`，可自定义：

```kotlin
compileInfo {
    resourcesDirectory.set(layout.projectDirectory.dir("my-resources"))
}
```

打包时间以 `jon-cmp-package-time.txt` 独立保存，生成于模块的 `build/generated/compileInfo/`，随 Compose 资源打包。

OHOS 的 `publishDebugBinariesToHarmonyApp`、`publishReleaseBinariesToHarmonyApp` Copy 任务将时间文件复制到 Harmony 工程的 `rawfile` 目录。

## 测试

```bash
# 版本解析与后端识别
./gradlew test

# 集成测试：编译、资源打包、缓存复用（Android SDK 36）
./gradlew functionalTest
```

## 本地发布

```bash
./gradlew publishToMavenLocal
```

## Maven Central 发布

发布开关：`gradle.properties` 中的 `mavenCentralPublishing=true`。POM 配置：`maven-central.gradle`。

`~/.gradle/gradle.properties`：

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signing.keyId=<GPG key ID>
signing.password=<私钥密码>
signing.secretKeyRingFile=<secring.gpg 的绝对路径>
```

发布：

```bash
./gradlew publishAndReleaseToMavenCentral
```