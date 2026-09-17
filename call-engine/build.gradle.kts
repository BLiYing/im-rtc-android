plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * 一致性向量目录：设了 `RTC_CONFORMANCE_DIR` 就只认它（不存在由测试抛错，**不退回去猜**）；
 * 否则按「主检出 / `<主检出>/.claude/worktrees/<分支>`」两种布局算出同级 im-rtc-server **唯一**该在的位置。
 *
 * **不要改回「往上逐级找到根」**：同级缺失时它会爬出本仓，碰上任何一份 im-rtc-server
 * （比如家目录里的旧克隆）就拿去用——拿旧向量跑绿，比抛错更糟（web `e58ec8e` 修过同一个问题）。
 * 别的位置的 worktree 设 `RTC_CONFORMANCE_DIR`；走 `scripts/test.sh` 会问 git 算好再传进来。
 */
val conformanceFromEnv: String? = System.getenv("RTC_CONFORMANCE_DIR")?.takeIf { it.isNotEmpty() }
val conformanceSource: String = if (conformanceFromEnv != null) "RTC_CONFORMANCE_DIR" else "同级布局"
// 相对路径按仓根解析（人是在仓根敲 ./gradlew 的；测试 JVM 的工作目录却是 call-engine/）。
val conformanceDir: File = conformanceFromEnv?.let { rootProject.file(it) } ?: run {
    val repoRoot = rootProject.projectDir
    val parent = repoRoot.parentFile
    val inWorktree = parent?.name == "worktrees" && parent.parentFile?.name == ".claude"
    val checkout = if (inWorktree) parent.parentFile.parentFile else repoRoot
    File(checkout.parentFile, "im-rtc-server/docs/conformance")
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
    // 一致性向量的位置在这里算好一次交给测试（见 [conformanceDir]），测试代码自己不再猜。
    testOptions {
        unitTests.all {
            it.systemProperty("rtc.conformance.dir", conformanceDir.path)
            it.systemProperty("rtc.conformance.source", conformanceSource)
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
