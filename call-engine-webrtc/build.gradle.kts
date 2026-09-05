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
    implementation(project(":call-engine"))
    // 第四刀才真正接入 libwebrtc（版本已锁在 libs.versions.toml）：
    //   implementation(libs.webrtc)
    // 现在不引，是为了让「跑一次单测」不必先拉几十 MB 的预编译包。
    testImplementation(libs.junit)
}
