plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mizulauncher.debugapi"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 路由分发、参数解析、错误映射都是纯逻辑，单测直接跑 JVM
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
