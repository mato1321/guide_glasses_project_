plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.ocr"

    // 🔥 一定要 36（你錯誤訊息要求的）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ocr"
        minSdk = 24

        // 🔥 一起改（避免未來問題）
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // 🔥 OkHttp（你已經加對）
    implementation("com.squareup.okhttp3:okhttp:4.9.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}