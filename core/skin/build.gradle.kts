plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // 本模块会产出 @Composable（SkinPreview3D），必须有 Compose 编译器插件，
    // 否则 remember/mutableStateOf 这类 inline 函数无法内联，报
    // "couldn't find inline method remember(...)" 这种看似无关的底层错误。
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mizulauncher.skin"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true }
}

dependencies {
    // 动效强度分级来自 compat：皮肤预览的自转要跟着「移除动画」设置走
    api(project(":core:compat"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.animation.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
