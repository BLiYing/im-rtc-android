plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imrtc.uikit"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }   // 原生 View + ViewBinding，不用 Compose（CONVENTIONS §9）
}

dependencies {
    // api 而不是 implementation：`IMCallKit.start(context, engine: IMCallEngine)` 的签名里就有 Engine 的类型。
    // 发成 Maven 包后 implementation 会写成 POM 的 runtime 作用域，只引 call-uikit 的宿主编译不过。
    api(project(":call-engine"))
    testImplementation(libs.junit)
}
