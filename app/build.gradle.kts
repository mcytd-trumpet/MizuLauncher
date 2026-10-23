plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appId = providers.gradleProperty("mizu.applicationId").getOrElse("com.mizulauncher")
val appName = providers.gradleProperty("mizu.appName").getOrElse("Mizu Launcher")
val verName = providers.gradleProperty("mizu.versionName").getOrElse("1.0.0")
val verCode = providers.gradleProperty("mizu.versionCode").getOrElse("1").toInt()

/**
 * 产品风味 = 一个 ABI。
 *
 * 为什么不像原版 Pojav 那样打成一个包：
 *  1. **体积**：每个 ABI 的 natives + JRE 加起来 80~120 MB，五个架构塞一个包直接 400 MB+，
 *     而用户实际只需要其中一份；
 *  2. **JRE 对不上**：JRE 是按架构编译的，arm64 的 `libjvm.so` 在 x86 手机上必定崩，
 *     与其运行时判断，不如分架构打，从根上杜绝「装错包」；
 *  3. **对齐 ZalithLauncher2**：它同样发 arm32 / arm64 / x86 / x86_64 / universal 五个 APK，
 *     用户迁移过来找包的习惯不用变。
 *
 * universal 是兜底：四个架构都打进去，给分不清自己机型、或者要一个包到处装的人。
 */
android {
    namespace = "com.mizulauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = verCode
        versionName = verName
        resValue("string", "app_name", appName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 具体 ABI 由各 flavor 决定，见下
    }

    flavorDimensions += "abi"

    productFlavors {
        create("arm32") {
            dimension = "abi"
            versionNameSuffix = "-arm32"
            ndk { abiFilters += "armeabi-v7a" }
            buildConfigField("String", "FLAVOR_ABI", "\"armeabi-v7a\"")
        }
        create("arm64") {
            dimension = "abi"
            versionNameSuffix = "-arm64"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "FLAVOR_ABI", "\"arm64-v8a\"")
        }
        create("x86") {
            dimension = "abi"
            versionNameSuffix = "-x86"
            ndk { abiFilters += "x86" }
            buildConfigField("String", "FLAVOR_ABI", "\"x86\"")
        }
        create("x64") {
            dimension = "abi"
            versionNameSuffix = "-x64"
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "FLAVOR_ABI", "\"x86_64\"")
        }
        create("universal") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
            // 多架构包里运行时就近取设备首选 ABI，构建期无法确定
            buildConfigField("String", "FLAVOR_ABI", "\"universal\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // 签名：配了 keystore 才签，否则产出未签名包（CI 上先出未签，再由 secrets 覆盖）
    val storePath = providers.gradleProperty("mizu.storeFile").orNull
    if (!storePath.isNullOrBlank() && file(storePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("mizu.storePassword").orNull
                keyAlias = providers.gradleProperty("mizu.keyAlias").orNull
                keyPassword = providers.gradleProperty("mizu.keyPassword").orNull
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
        // 内核 so 体积大，保持不压缩以加快加载
        jniLibs.useLegacyPackaging = true
    }
    // JRE 以 zip 形式放在 assets 里，绝不能压缩——否则每次首启都要多花一遍 CPU 解压，
    // 而且拿不到真实字节长度、算不出解压进度。
    androidResources {
        noCompress += listOf("zip", "jar")
    }
}

dependencies {
    implementation(project(":core:download"))
    implementation(project(":core:minecraft"))
    implementation(project(":core:instance"))
    implementation(project(":core:launcher-bridge"))
    implementation(project(":core:runtime"))
    implementation(project(":core:auth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
