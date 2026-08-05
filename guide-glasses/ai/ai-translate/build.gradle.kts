plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.guideglasses.ai.translate"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    // 和 OCR 的中文模型不同，翻譯的語言包**沒有 bundled 版** ——
    // ML Kit 一律在執行期下載（每種語言約 30MB）。首次使用需要網路，
    // 下載後完全離線。這是 ML Kit 的限制，不是這裡的設計選擇。
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
