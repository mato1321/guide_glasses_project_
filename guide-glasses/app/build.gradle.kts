import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * `local.properties` 的內容。
 *
 * Gradle **不會**把 local.properties 載入成 Gradle property —— 它只被 AGP
 * 拿去讀 `sdk.dir`。但這個檔案已被 .gitignore 排除，是放機器本地設定
 * （後端位址）最自然的地方，文件也一直是這樣寫的，所以這裡明確支援它。
 *
 * 用 `providers.fileContents` 而不是 `File(...).readText()`，這樣檔案會被
 * 登記成建置輸入，configuration cache 才會在內容變更時正確失效。
 */
val localProperties: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text ->
        Properties()
            .apply { load(StringReader(text)) }
            .entries
            .associate { it.key.toString() to it.value.toString() }
    }
    .getOrElse(emptyMap())

/**
 * 讀取設定值：優先 Gradle property（`-P` 或 gradle.properties），
 * 找不到才退回 local.properties。兩處都沒有時回傳空字串。
 *
 * 之所以要同時支援兩者：設定寫錯地方時**不會有任何錯誤訊息**，
 * BuildConfig 只會拿到空字串，App 執行時播報「人臉辨識不可用」，
 * 而使用者完全無從得知是設定沒被讀到。
 */
fun configValue(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties[name]?.trim().orEmpty()

/** 包成 Java 字串常值。跳脫反斜線與雙引號，避免值裡有特殊字元時產生壞掉的原始碼。 */
fun stringLiteral(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.guideglasses"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.guideglasses"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // BFF 位址。留空時退回離線閘道，App 仍可用本地快捷指令。
        // 設定方式：在 local.properties 或 ~/.gradle/gradle.properties 加入
        //   guideglasses.llmEndpoint=https://your-bff.run.app/route
        buildConfigField(
            "String",
            "LLM_ENDPOINT",
            stringLiteral(configValue("guideglasses.llmEndpoint")),
        )

        // 人臉辨識後端（選用）。留空時只走端側，需要模型檔。
        //   guideglasses.faceEndpoint=http://192.168.1.100:8000/recognize
        buildConfigField(
            "String",
            "FACE_ENDPOINT",
            stringLiteral(configValue("guideglasses.faceEndpoint")),
        )

        // 人臉註冊工具的位址（選用）。說「同步人臉」時從這裡抓照片。
        // 啟動 tools/face_enroll_server.py 時它會把這一行印出來。
        //   guideglasses.photoEndpoint=http://192.168.1.100:8100
        buildConfigField(
            "String",
            "PHOTO_ENDPOINT",
            stringLiteral(configValue("guideglasses.photoEndpoint")),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Rokid Glasses 是 ARM64。不打包 x86/x86_64，避免 APK 無謂膨脹。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            /*
             * sherpa-onnx 的 static-link AAR 把 onnxruntime 靜態連進去了，
             * 唯獨 x86 那顆仍然附帶自己的 `libonnxruntime.so`，會與
             * ai-face / ai-vision 用的 onnxruntime-android 撞名，
             * 在 mergeNativeLibs 直接失敗。
             *
             * `abiFilters` 擋不住這個 —— 合併發生在 ABI 過濾之前。
             * 反正眼鏡是 arm64，x86 一開始就不該進來。
             */
            excludes += "lib/x86/**"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 開發工具跑在區網 HTTP 上，見 AndroidManifest。
            manifestPlaceholders["cleartextTraffic"] = true
        }
        release {
            isMinifyEnabled = true
            manifestPlaceholders["cleartextTraffic"] = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":glasses:glasses-camerax"))
    // GuideGlassesApplication 需要直接用 CameraXConfig.Provider 修掉
    // 眼鏡假宣告前鏡頭導致的 init 失敗。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(project(":glasses:glasses-sensors"))
    implementation(project(":ai:ai-speech"))
    implementation(project(":ai:ai-tts-offline"))
    implementation(project(":ai:ai-agent"))
    implementation(project(":ai:ai-ocr"))
    implementation(project(":ai:ai-face"))
    implementation(project(":ai:ai-translate"))
    implementation(project(":ai:ai-vision"))
    implementation(project(":core:core-database"))
    implementation(project(":feature:feature-assistant"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.junit)
}
