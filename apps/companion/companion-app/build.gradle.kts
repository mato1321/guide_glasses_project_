import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 與眼鏡端 `app/build.gradle.kts` 共用同一把設定檔讀法 ——
 * 兩邊本來就該指到同一個後端，設定 key 也刻意共用（`guideglasses.busApiEndpoint`）。
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

android {
    namespace = "com.guideglasses.companion"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.guideglasses.companion"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // 與眼鏡端相同的後端位址 —— 這支 App 只負責把手機 GPS 座標
        // POST 給它，見 docs/ARCHITECTURE.md §5.2「手機只該當 GPS 感測器」。
        buildConfigField(
            "String",
            "BUS_API_ENDPOINT",
            stringLiteral(configValue("guideglasses.busApiEndpoint")),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 開發時後端多半是區網上的 HTTP（見 AndroidManifest），非 HTTPS。
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
