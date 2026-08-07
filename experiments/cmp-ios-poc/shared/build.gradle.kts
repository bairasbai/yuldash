import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // --- Android как один из таргетов общего модуля ---
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // --- iOS: три архитектуры (симулятор Intel, устройство, симулятор Apple Silicon) ---
    // Каждая собирает один и тот же общий код в нативный framework "Shared", который подключает Xcode.
    // ⚠️ Эти таргеты КОМПИЛИРУЮТСЯ только на macOS + Xcode (тулчейн Apple). На Linux/Windows не собрать.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Один и тот же Compose работает на всех таргетах.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.animation)   // AnimatedContent / fadeIn / animateColorAsState
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
        }
    }
}

android {
    namespace = "com.yuldash.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
