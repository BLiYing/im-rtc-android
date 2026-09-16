plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imrtc.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.imrtc.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        // 与 SDK 同号：读发布版本号（gradle.properties 的 IMRTC_VERSION），不再手动同步。
        versionName = providers.gradleProperty("IMRTC_VERSION").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

// 三档见 settings.gradle.kts 顶部注释。
val imrtcSdk: String = providers.gradleProperty("imrtcSdk")
    .orElse(providers.environmentVariable("IMRTC_SDK"))
    .getOrElse("source")

dependencies {
    if (imrtcSdk == "source") {
        implementation(project(":call-engine"))
        implementation(project(":call-engine-webrtc"))
        implementation(project(":call-uikit"))
    } else {
        // 与 /guide 给第三方的写法逐字一致。**故意不写 call-engine**：它要靠 uikit 的 api 依赖传递进来，
        // 这一条在 POM 里写错（runtime 作用域）时，这里就编不过。
        val group = providers.gradleProperty("IMRTC_GROUP").get()
        val version = providers.gradleProperty("IMRTC_VERSION").get()
        implementation("$group:call-uikit:$version")
        implementation("$group:call-engine-webrtc:$version")
    }

    // 日志回传那条路要真的验：手写的 JSON 序列化与攒批队列都是纯逻辑，
    // 跑在 JVM 上不需要设备（CONVENTIONS §测试）。
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
