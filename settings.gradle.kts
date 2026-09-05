pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "im-rtc-android"

include(":call-engine")          // 无 UI 核心，不依赖 org.webrtc
include(":call-engine-webrtc")   // 媒体实现（org.webrtc）
include(":call-uikit")           // 整套通话 UI
include(":demo")                 // Demo App（本仓的一个模块，不是独立工程）
