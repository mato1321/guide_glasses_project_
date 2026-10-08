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

/**
 * 各 flavor 專屬的後端設定：`guideglasses.<flavor>.<name>`，例如
 * `guideglasses.cloudflare.busApiEndpoint`。
 *
 * **刻意不退回共用的 `guideglasses.<name>`**：Cloudflare 版與 AWS 版指向
 * 不同的後端，退回共用值的話，AWS 版會安靜地連到 Cloudflare 的後端，
 * 而且完全看不出來。舊的共用 key 由 [LEGACY_BACKEND_KEYS] 偵測並警告。
 */
fun flavorConfigValue(flavor: String, name: String): String =
    configValue("guideglasses.$flavor.$name")

/** 拆 flavor 之前使用的共用 key。還設著的話建置時警告，避免以為有生效。 */
val LEGACY_BACKEND_KEYS = listOf("llmEndpoint", "faceEndpoint", "photoEndpoint", "busApiEndpoint")
    .map { "guideglasses.$it" }

/**
 * 後端相關的 BuildConfig 欄位，每個 flavor 各自一組。
 *
 * @param phoneDirectPort 手機直連的區網埠，必須與手機 companion 同名 flavor 的
 *   GLASSES_DIRECT_PORT 相同。
 */
fun com.android.build.api.dsl.ApplicationProductFlavor.backendEndpoints(flavor: String, phoneDirectPort: Int) {
    // 眼鏡連手機熱點時，直接跟手機拿位置（手機 IP 自動從預設閘道取得）。
    buildConfigField("int", "PHONE_DIRECT_PORT", phoneDirectPort.toString())
    // 手動指定手機直連的網址（選用）。留空＝自動用熱點閘道；USB 測試時設成
    // http://127.0.0.1:<埠>，並做 adb forward（手機）＋ adb reverse（眼鏡）。
    buildConfigField("String", "PHONE_LOCATION_ENDPOINT", stringLiteral(flavorConfigValue(flavor, "phoneLocationEndpoint")))
    // 後端的共用金鑰（X-Api-Key，見 backend/.env 的 GUIDEGLASSES_API_KEY 與 di/ApiKeyInterceptor）。
    // 沒填時後端會對所有請求回 401／503。
    buildConfigField("String", "API_KEY", stringLiteral(flavorConfigValue(flavor, "apiKey")))
    // LLM 意圖解析（/route）。留空時退回離線閘道，App 仍可用本地快捷指令。
    buildConfigField("String", "LLM_ENDPOINT", stringLiteral(flavorConfigValue(flavor, "llmEndpoint")))
    // 人臉辨識後端（選用）。留空時只走端側，需要模型檔。
    buildConfigField("String", "FACE_ENDPOINT", stringLiteral(flavorConfigValue(flavor, "faceEndpoint")))
    // 人臉註冊照片來源。說「同步人臉」時從這裡抓照片。
    buildConfigField("String", "PHOTO_ENDPOINT", stringLiteral(flavorConfigValue(flavor, "photoEndpoint")))
    // 公車／步行路線與定位轉傳。留空時「查公車路線」會播報功能不可用。
    // 手機 companion 的同名 flavor 讀同一個 key，兩邊本該指到同一個後端。
    buildConfigField("String", "BUS_API_ENDPOINT", stringLiteral(flavorConfigValue(flavor, "busApiEndpoint")))
}

/**
 * 三版共用的簽章（`keystore.properties`，不進版控，範本見 `keystore.properties.example`）。
 *
 * 同一個 applicationId 只能用同一把簽章更新。各自用自己電腦的 debug key，
 * 換一台電腦建置就裝不上去，只能解除安裝重裝 —— 眼鏡上用 Keystore 加密的
 * 人臉資料會一起消失（v1 的 com.guideglasses 就是這樣被卡住的）。
 */
val sharedKeystore: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText
    .map { text ->
        Properties()
            .apply { load(StringReader(text)) }
            .entries
            .associate { it.key.toString() to it.value.toString().trim() }
    }
    .getOrElse(emptyMap())

android {
    namespace = "com.guideglasses"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.guideglasses"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // 後端位址（LLM_ENDPOINT、FACE_ENDPOINT、PHOTO_ENDPOINT、BUS_API_ENDPOINT）
        // 依 flavor 而不同，定義在下方 productFlavors。

        // 公車路線的固定目的地（選用，短指令沒有畫面可選，先用設定檔固定一個）。
        // 預設值是團隊測試用的台北市座標，正式使用前務必換成真正的目的地。
        //   guideglasses.busDestinationLat=25.049066
        //   guideglasses.busDestinationLng=121.510058
        //   guideglasses.busDestinationLabel=測試地點
        buildConfigField(
            "String",
            "BUS_DESTINATION_LAT",
            stringLiteral(configValue("guideglasses.busDestinationLat").ifBlank { "25.049066" }),
        )
        buildConfigField(
            "String",
            "BUS_DESTINATION_LNG",
            stringLiteral(configValue("guideglasses.busDestinationLng").ifBlank { "121.510058" }),
        )
        buildConfigField(
            "String",
            "BUS_DESTINATION_LABEL",
            stringLiteral(configValue("guideglasses.busDestinationLabel").ifBlank { "測試地點" }),
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

    signingConfigs {
        if (!sharedKeystore["storeFile"].isNullOrBlank()) {
            create("shared") {
                storeFile = file(sharedKeystore.getValue("storeFile"))
                storePassword = sharedKeystore["storePassword"]
                keyAlias = sharedKeystore["keyAlias"]
                keyPassword = sharedKeystore["keyPassword"]
            }
        }
    }

    /*
     * 連網版分兩個 flavor，applicationId 不同，可以與端側版（edge/，
     * com.guideglasses）三者同時裝在眼鏡上。
     *
     * 後端位址寫在 local.properties 或 ~/.gradle/gradle.properties：
     *   guideglasses.cloudflare.llmEndpoint=https://api.<網域>/route
     *   guideglasses.cloudflare.busApiEndpoint=https://api.<網域>
     *   guideglasses.cloudflare.photoEndpoint=http://127.0.0.1:8100   # 走 USB：adb reverse tcp:8100 tcp:8100
     *   guideglasses.aws.llmEndpoint=https://<id>.lambda-url.us-west-2.on.aws/route
     *   guideglasses.aws.busApiEndpoint=https://<id>.lambda-url.us-west-2.on.aws
     */
    flavorDimensions += "backend"
    productFlavors {
        create("cloudflare") {
            dimension = "backend"
            applicationIdSuffix = ".cloudflare"
            resValue("string", "app_name", "導盲眼鏡 CF")
            backendEndpoints("cloudflare", phoneDirectPort = 8765)
        }
        create("aws") {
            dimension = "backend"
            applicationIdSuffix = ".aws"
            resValue("string", "app_name", "導盲眼鏡 AWS")
            backendEndpoints("aws", phoneDirectPort = 8766)
        }
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
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
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

if (sharedKeystore["storeFile"].isNullOrBlank()) {
    logger.warn(
        "⚠️ 找不到 keystore.properties，debug 版改用這台電腦的 debug key 簽章。" +
            "裝不上已用共用簽章安裝過的眼鏡，見 keystore.properties.example。",
    )
}
LEGACY_BACKEND_KEYS.filter { configValue(it).isNotBlank() }.takeIf { it.isNotEmpty() }?.let { keys ->
    logger.warn(
        "⚠️ 這些舊的共用設定已不再生效，請改成 flavor 專屬的 key" +
            "（例如 guideglasses.cloudflare.busApiEndpoint）：${keys.joinToString()}",
    )
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
    implementation(project(":ai:ai-asr-offline"))
    implementation(project(":ai:ai-agent"))
    implementation(project(":ai:ai-ocr"))
    implementation(project(":ai:ai-face"))
    implementation(project(":ai:ai-translate"))
    implementation(project(":ai:ai-vision"))
    implementation(project(":ai:ai-navigation"))
    implementation(project(":core:core-database"))
    implementation(project(":feature:feature-assistant"))

    // 後端閘道共用的 X-Api-Key 攔截器（di/ApiKeyInterceptor）。
    implementation(libs.okhttp)

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
