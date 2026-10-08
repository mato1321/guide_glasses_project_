plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.guideglasses.ai.asr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ONNX 已經是壓縮過的格式，再壓一次省不到空間卻要多解壓一次。
    androidResources {
        noCompress += "onnx"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:core-domain"))
    implementation(project(":core:core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 與 ai-tts-offline 同一顆 .so（static-link 版，避免 libonnxruntime.so 撞名）。
    // 座標與 repository 的說明見 settings.gradle.kts。
    api("com.k2fsa:sherpa-onnx-static-link-onnxruntime:1.13.4@aar")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
