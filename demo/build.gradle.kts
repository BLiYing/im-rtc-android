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
        versionName = "0.1"
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

dependencies {
    implementation(project(":call-engine"))
    implementation(project(":call-engine-webrtc"))
    implementation(project(":call-uikit"))

    // 日志回传那条路要真的验：手写的 JSON 序列化与攒批队列都是纯逻辑，
    // 跑在 JVM 上不需要设备（CONVENTIONS §测试）。
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
