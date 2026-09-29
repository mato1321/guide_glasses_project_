plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core-domain 刻意是「純 Kotlin」模組，不套用 Android plugin。
// 這是架構上的硬性約束：任何 android.* 或 com.rokid.* 的 import 都會編譯失敗，
// 從建置層面保證 domain 層不被基礎設施污染。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
