plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imrtc.engine"
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

    // 单测跑在 JVM 上，不需要设备、不需要 Robolectric（CONVENTIONS §1、§10）。
    // 一致性向量的位置：优先环境变量，否则从工作目录逐级往上找 im-rtc-server/docs/conformance。
    testOptions {
        unitTests.all {
            it.systemProperty("rtc.conformance.dir", System.getenv("RTC_CONFORMANCE_DIR") ?: "")
            it.testLogging { events("failed", "skipped") }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
