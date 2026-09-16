import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

/*
 # 发布（2026-09-16 起，公网走 JitPack）

 三个 SDK 模块发成 `<IMRTC_GROUP>:<模块名>:<版本>`，Demo 不发。坐标与版本号只在 `gradle.properties` 写一处，
 `IMCallEngineVersion.VERSION` 与它对不上时单测直接红（`call-engine` 的 `SdkVersionTest`）——
 握手里报的版本和包管理器里的版本不许是两个数。

 **为什么不直接给 AAR**：AAR 不带 POM，宿主拿到后 OkHttp 与 `io.github.webrtc-sdk:android`
 两个传递依赖要自己一个个补，版本还得跟我们锁的对上。发 Maven 这两条随 POM 自动到位。

 - **公网**：在 GitHub 推 tag（与 IMRTC_VERSION 同名，不带 v）。第一次有人请求这个版本时 JitPack 按
   `jitpack.yml` 现场构建（跑的就是 `publishToMavenLocal`），第三方写 `maven("https://jitpack.io")` + 坐标。
   JitPack 也能按提交号构建（推 tag 前试水），那时版本号取它给的 `VERSION` 环境变量。
 - **本地**：`./gradlew publishToMavenLocal`，Demo 用 `-PimrtcSdk=local` 验（见 settings.gradle.kts）。
 - 私有仓（备用）：给出 `imrtcMavenUrl`（及可选的 `imrtcMavenUser` / `imrtcMavenPassword`）后跑 `./gradlew publish`；
   账号口令放 `~/.gradle/gradle.properties` 或 `ORG_GRADLE_PROJECT_*` 环境变量，**不进仓库**。
 */
val publishedModules = setOf("call-engine", "call-engine-webrtc", "call-uikit")

subprojects {
    if (name !in publishedModules) return@subprojects
    group = providers.gradleProperty("IMRTC_GROUP").get()
    // JitPack 按提交号构建时版本是那个提交号；按 tag 构建时它与 IMRTC_VERSION 同名，取哪个都一样。
    version = providers.environmentVariable("VERSION")
        .filter { System.getenv("JITPACK") == "true" }
        .orElse(providers.gradleProperty("IMRTC_VERSION"))
        .get()

    plugins.withId("com.android.library") {
        apply(plugin = "maven-publish")
        // 只发 release 变体，连源码包一起：宿主在 IDE 里点进 SDK 能看到带注释的源码，而不是反编译结果。
        extensions.configure<LibraryExtension> {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        // `components["release"]` 要等 AGP 在求值末尾建出来，提前取会抛「找不到组件」。
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.register<MavenPublication>("release") {
                    from(components["release"])
                    artifactId = project.name
                    pom {
                        name.set("im-rtc ${project.name}")
                        description.set("im-rtc Android SDK：${project.name}")
                        url.set("https://github.com/BLiYing/im-rtc-android")
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        scm { url.set("https://github.com/BLiYing/im-rtc-android") }
                    }
                }
                val url = providers.gradleProperty("imrtcMavenUrl").orNull ?: return@configure
                repositories.maven {
                    name = "imrtc"
                    setUrl(url)
                    val user = providers.gradleProperty("imrtcMavenUser").orNull
                    if (user != null) {
                        credentials {
                            username = user
                            password = providers.gradleProperty("imrtcMavenPassword").orNull
                        }
                    }
                }
            }
        }
    }
}
