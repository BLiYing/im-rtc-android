plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imrtc.engine.webrtc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":call-engine"))

    // libwebrtc 预编译包，版本锁在 libs.versions.toml（M150）。
    // **只有这个模块引它**——call-engine 不依赖它，所以「跑一次单测」不用先拉几十 MB。
    implementation(libs.webrtc)

    testImplementation(libs.junit)
}
