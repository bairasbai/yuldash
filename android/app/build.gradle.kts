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

// Ключ Яндекс Геокодера (HTTP API, поиск адресов) — тоже из local.properties.
val geocoderKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YANDEX_GEOCODER_KEY", "")

// URL backend API для debug/release. Можно переопределить в local.properties:
// YULDASH_DEBUG_API_BASE_URL=http://10.0.2.2:8000
// YULDASH_RELEASE_API_BASE_URL=https://yulbash.ru
val debugApiBaseUrl: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_DEBUG_API_BASE_URL", "http://10.0.2.2:8000")

val releaseApiBaseUrl: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_RELEASE_API_BASE_URL", "https://yulbash.ru")

// Телефон поддержки/оператора (для звонка из приложения). Задать в local.properties:
//   YULDASH_SUPPORT_PHONE=+79991234567
// Пусто → кнопка звонка прячется, остаётся «попросить звонок».
val supportPhone: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_SUPPORT_PHONE", "")

// Подпись релиза: данные из keystore.properties (в .gitignore, в git не попадает).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storePassword") != null

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
        buildConfigField("String", "YANDEX_GEOCODER_KEY", "\"$geocoderKey\"")
        buildConfigField("String", "YULDASH_SUPPORT_PHONE", "\"$supportPhone\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "YULDASH_API_BASE_URL", "\"$debugApiBaseUrl\"")
        }

        release {
            buildConfigField("String", "YULDASH_API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
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
