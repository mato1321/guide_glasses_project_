import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 與眼鏡端 `app/build.gradle.kts` 共用同一把設定檔讀法 ——
 * 兩邊本來就該指到同一個後端，設定 key 也刻意共用（`guideglasses.<flavor>.busApiEndpoint`）。
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

fun configValue(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties[name]?.trim().orEmpty()

fun stringLiteral(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** 與眼鏡端相同：`guideglasses.<flavor>.busApiEndpoint`，刻意不退回共用 key。 */
fun flavorConfigValue(flavor: String, name: String): String =
    configValue("guideglasses.$flavor.$name")

/** 與眼鏡端共用同一把簽章，見 app/build.gradle.kts 與 keystore.properties.example。 */
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
    namespace = "com.guideglasses.companion"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.guideglasses.companion"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 與眼鏡端的 flavor 一一對應，兩版可同時裝在同一支手機上。
    // BUS_API_ENDPOINT 是眼鏡端同名 flavor 的後端 —— 這支 App 只負責把手機 GPS
    // 座標 POST 給它，見 docs/ARCHITECTURE.md §5.2「手機只該當 GPS 感測器」。
    // API_KEY 是同一個後端的共用金鑰（X-Api-Key），與眼鏡端讀同一個 key。
    // GLASSES_DIRECT_PORT 是眼鏡直連的區網埠（LocalLocationServer），必須與眼鏡端
    // 同名 flavor 的 PHONE_DIRECT_PORT 相同；兩個 flavor 不同埠，同時開也不會搶。
    flavorDimensions += "backend"
    productFlavors {
        create("cloudflare") {
            dimension = "backend"
            applicationIdSuffix = ".cloudflare"
            resValue("string", "app_name", "導盲定位 CF")
            buildConfigField("String", "BUS_API_ENDPOINT", stringLiteral(flavorConfigValue("cloudflare", "busApiEndpoint")))
            buildConfigField("String", "API_KEY", stringLiteral(flavorConfigValue("cloudflare", "apiKey")))
            buildConfigField("int", "GLASSES_DIRECT_PORT", "8765")
        }
        create("aws") {
            dimension = "backend"
            applicationIdSuffix = ".aws"
            resValue("string", "app_name", "導盲定位 AWS")
            buildConfigField("String", "BUS_API_ENDPOINT", stringLiteral(flavorConfigValue("aws", "busApiEndpoint")))
            buildConfigField("String", "API_KEY", stringLiteral(flavorConfigValue("aws", "apiKey")))
            buildConfigField("int", "GLASSES_DIRECT_PORT", "8766")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 開發時後端多半是區網上的 HTTP（見 AndroidManifest），非 HTTPS。
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

dependencies {
    // 後端網址的正規化與「重新建置後舊設定作廢」的判斷，眼鏡與手機共用（core/domain/backend）。
    implementation(project(":core:core-domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    // 畫面觀察 LocationReportService 的狀態（StateFlow + repeatOnLifecycle）。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
