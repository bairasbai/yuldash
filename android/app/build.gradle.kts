import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ Яндекс MapKit читаем из local.properties (он в .gitignore, в git не попадает).
val mapkitKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YANDEX_MAPKIT_KEY", "")

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.yuldash.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yuldash.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Пробрасываем ключ в BuildConfig (в коде не хардкодим).
        buildConfigField("String", "YANDEX_MAPKIT_KEY", "\"$mapkitKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // Яндекс MapKit Mobile SDK (lite: карта, маркеры, линия маршрута; роутинг/поиск — это -full).
    implementation("com.yandex.android:maps.mobile:4.39.0-lite")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
