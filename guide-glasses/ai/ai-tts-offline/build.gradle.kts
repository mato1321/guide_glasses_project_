plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.guideglasses.ai.tts"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ONNX 與 FST 都已經是壓縮過的格式。再壓一次省不到空間，
    // 卻會讓載入時多一次解壓 —— 眼鏡只有 2GB RAM，這個代價不划算。
    androidResources {
        noCompress += listOf("onnx", "fst")
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

    /*
     * sherpa-onnx 沒有發佈到 Maven Central（網路上流傳的
     * `com.k2fsa.sherpa.onnx:sherpa-onnx-android` 座標是錯的，那個 group
     * 在 Central 上根本不存在），只能用 GitHub release 的預編譯 AAR。
     *
     * 刻意選 static-link 版本：一般版的 AAR 內含自己的 `libonnxruntime.so`，
     * 會與 ai-face / ai-vision 已經在用的 ONNX Runtime **撞名**，
     * 打包時兩顆同名 .so 只會留下一顆，版本不合就在執行期炸掉。
     * static-link 版把 onnxruntime 靜態連進 `libsherpa-onnx-jni.so`，沒有這個問題。
     *
     * 只有 arm64-v8a 會進 APK —— app 模組的 `abiFilters` 已經鎖住。
     *
     * AAR 檔案就放在本模組的 `libs/`（maven 目錄佈局），
     * 由 settings.gradle.kts 宣告成 repository —— 為什麼不能用
     * `files(...)` 或 `flatDir`，那裡有完整說明。
     */
    api("com.k2fsa:sherpa-onnx-static-link-onnxruntime:1.13.4@aar")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
