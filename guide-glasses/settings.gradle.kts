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
    }
}

rootProject.name = "guide-glasses"

// ===== 組裝層 =====
include(":app")

// ===== 共用基礎 =====
include(":core:core-common")
include(":core:core-domain")

// ===== Edge AI =====
include(":ai:ai-speech")
include(":ai:ai-agent")

// ===== 功能模組 =====
include(":feature:feature-assistant")
