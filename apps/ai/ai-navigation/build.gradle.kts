plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.guideglasses.ai.navigation"
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

    implementation(libs.kotlinx.coroutines.android)

    // 沿用團隊既有的 Flask/FastAPI 後端，與 ai-face 的 RemoteFaceIdentification 同一個模式。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 裝置本身有 GPS 時用（GlassesGpsLocationProvider）——單支手機測試、
    // 或未來眼鏡實測發現有 GPS_PROVIDER 時都走這條，不需要手機 companion。
    // 只用系統 LocationManager（經 androidx.core 的 LocationManagerCompat）。
    // 不要加 play-services-location：這個模組會進眼鏡 APK，而眼鏡沒有
    // Google Play Services，Fused 會安靜地永遠收不到座標。它只屬於 companion-app。
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
