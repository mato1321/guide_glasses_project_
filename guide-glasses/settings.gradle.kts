pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Rokid CXR SDK（glasses-cxr 模組使用）
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }

        /*
         * sherpa-onnx（離線語音合成）沒有發佈到任何 Maven repository ——
         * 網路上流傳的 `com.k2fsa.sherpa.onnx:sherpa-onnx-android` 座標是錯的，
         * 那個 group 在 Maven Central 上不存在。官方只提供 GitHub release
         * 的預編譯 AAR，所以檔案直接進版控，用本地 maven 佈局餵給 Gradle。
         *
         * 試過但不可行的兩條路：
         * 1. `implementation(files("....aar"))` —— AGP 直接擋：
         *    "Direct local .aar file dependencies are not supported when building an AAR"
         * 2. `flatDir { ... }` —— 能編譯，但產生的座標沒有 group，
         *    lint 的 GradleDetector 會拿它去組路徑然後丟 InvalidPathException。
         *
         * 所以用 maven 佈局並給一個真的 group。沒有 .pom，
         * 因此要 `metadataSources { artifact() }` 讓 Gradle 直接認檔案。
         */
        maven {
            url = uri("$rootDir/ai/ai-tts-offline/libs")
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "guide-glasses"

// ===== 組裝層 =====
include(":app")

// ===== 共用基礎 =====
include(":core:core-common")
include(":core:core-domain")
include(":core:core-database")

// ===== 眼鏡硬體抽象的實作 =====
include(":glasses:glasses-camerax")
include(":glasses:glasses-sensors")

// ===== Edge AI =====
include(":ai:ai-speech")
// APK 內建的離線語音合成。眼鏡上 Android TextToSpeech 綁不上，這是唯一的出聲途徑。
include(":ai:ai-tts-offline")
include(":ai:ai-agent")
include(":ai:ai-ocr")
include(":ai:ai-face")
include(":ai:ai-translate")
include(":ai:ai-vision")

// ===== 功能模組 =====
include(":feature:feature-assistant")
