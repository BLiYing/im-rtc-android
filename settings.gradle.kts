pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

/*
 # Demo 用哪份 SDK：`imrtcSdk`（`-PimrtcSdk=…` 或环境变量 `IMRTC_SDK`）

   source（默认）  三个 SDK 模块以源码进本次构建，Demo 用 `project(":…")` 引
   local           Demo 引 `mavenLocal()` 里的包——先跑 `./gradlew publishToMavenLocal`
   public          Demo 引 JitPack 上的包——要先在 GitHub 推了 tag

 后两档**只 include `:demo`**：SDK 模块根本不在这次构建里，Demo 想退回源码也退不回去，
 于是「Demo 编过」就等于「第三方只写这几行坐标也编得过」。
 两档版本号都是 IMRTC_VERSION，App 里看不出差别，只能看构建开头那行「Demo 用的 SDK」。
 */
val imrtcSdk: String = providers.gradleProperty("imrtcSdk")
    .orElse(providers.environmentVariable("IMRTC_SDK"))
    .getOrElse("source")
require(imrtcSdk in setOf("source", "local", "public")) {
    "imrtcSdk 只认 source / local / public，收到的是「$imrtcSdk」"
}
val imrtcGroup: String = providers.gradleProperty("IMRTC_GROUP").get()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 只让自家坐标走这两个仓：别的依赖从 mavenLocal / JitPack 拉到，排查起来是另一场灾难。
        when (imrtcSdk) {
            "local" -> mavenLocal { content { includeGroup(imrtcGroup) } }
            "public" -> maven("https://jitpack.io") { content { includeGroup(imrtcGroup) } }
        }
    }
}

rootProject.name = "im-rtc-android"

if (imrtcSdk == "source") {
    include(":call-engine")          // 无 UI 核心，不依赖 org.webrtc
    include(":call-engine-webrtc")   // 媒体实现（org.webrtc）
    include(":call-uikit")           // 整套通话 UI
}
include(":demo")                     // Demo App（本仓的一个模块，不是独立工程）

val imrtcVersion: String = providers.gradleProperty("IMRTC_VERSION").get()
logger.lifecycle(
    when (imrtcSdk) {
        "local" -> "Demo 用的 SDK：本地包 mavenLocal() 里的 $imrtcGroup:*:$imrtcVersion"
        "public" -> "Demo 用的 SDK：公网包 jitpack.io 上的 $imrtcGroup:*:$imrtcVersion"
        else -> "Demo 用的 SDK：源码"
    }
)
