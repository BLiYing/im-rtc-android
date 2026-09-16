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
    kotlinOptions {
        jvmTarget = "17"
        // 监听器接口里的默认实现要编成**真正的 Java 默认方法**，
        // 否则 Java 宿主必须把 24 个回调一个不落地实现一遍（CONVENTIONS §4）。
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }

    // 单测跑在 JVM 上，不需要设备、不需要 Robolectric（CONVENTIONS §1、§10）。
    // 一致性向量的位置：优先环境变量，否则从工作目录逐级往上找 im-rtc-server/docs/conformance。
    testOptions {
        unitTests.all {
            it.systemProperty("rtc.conformance.dir", System.getenv("RTC_CONFORMANCE_DIR") ?: "")
            // 发布版本号，供 SdkVersionTest 核对 IMCallEngineVersion.VERSION（见根 build.gradle.kts）。
            it.systemProperty("imrtc.version", providers.gradleProperty("IMRTC_VERSION").get())
            it.testLogging { events("failed", "skipped") }
        }
    }
}

dependencies {
    // 信令用 OkHttp 的 WebSocket。**只有这一个第三方依赖**——
    // 媒体那层的 libwebrtc 也会传递带上它，宿主不会多背一份。
    api(libs.okhttp)

    testImplementation(libs.junit)
}
