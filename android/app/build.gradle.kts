import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// FCM (push): google-services применяем ТОЛЬКО когда есть app/google-services.json.
// Без файла сборка не падает (push просто неактивен). Александр кладёт файл из Firebase → push оживает.
if (rootProject.file("app/google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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

// OAuth-вход: имя Telegram-бота и VK app_id. Реальные значения — в local.properties (НЕ в git):
//   YULDASH_TELEGRAM_BOT=yuldash_bot
//   YULDASH_VK_APP_ID=51234567
// Пусто → кнопка показывает «скоро», ломаный поток не открываем.
val telegramBot: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_TELEGRAM_BOT", "")

val vkAppId: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_VK_APP_ID", "")

// SMS-вход ЗАМОРОЖЕН (нет юр.лица/ИП для договора с sms.ru). Код входа цел — оживляется флагом.
// Оживить: YULDASH_SMS_LOGIN=true в local.properties + на сервере SMS_PROVIDER=smsru с ключом.
// По умолчанию выключен → основной вход через мессенджеры.
val smsLoginEnabled: Boolean = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_SMS_LOGIN", "false").trim().lowercase() == "true"

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
        // Рунер инструментальных тестов (без него AGP берёт легаси android.test.* → краш Compose-тестов).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Пробрасываем ключ в BuildConfig (в коде не хардкодим).
        buildConfigField("String", "YANDEX_MAPKIT_KEY", "\"$mapkitKey\"")
        buildConfigField("String", "YANDEX_GEOCODER_KEY", "\"$geocoderKey\"")
        buildConfigField("String", "YULDASH_SUPPORT_PHONE", "\"$supportPhone\"")
        buildConfigField("String", "TELEGRAM_BOT", "\"$telegramBot\"")
        buildConfigField("String", "VK_APP_ID", "\"$vkAppId\"")
        buildConfigField("boolean", "SMS_LOGIN_ENABLED", "$smsLoginEnabled")
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
            // Только arm64 в релизе → APK ~50 МБ вместо 145 (MapKit нативные либы). Покрывает ~все
            // телефоны с 2017. Debug остаётся универсальным (эмулятор x86_64 работает). Под Google Play
            // позже вернуть все ABI или использовать AAB-сплиты.
            ndk { abiFilters += "arm64-v8a" }
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")   // показ удалённых изображений (фото в чате)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // Яндекс MapKit Mobile SDK (lite: карта, маркеры, линия маршрута; роутинг/поиск — это -full).
    implementation("com.yandex.android:maps.mobile:4.39.0-full")
    // OkHttp — только ради WebSocket-клиента (realtime-чат). REST остаётся на HttpURLConnection.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // FCM (push-уведомления): новое сообщение/бронь/SOS. Активен при наличии google-services.json.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")   // метрики: DAU/удержание/воронка событий (активно при google-services.json)
    // Шифрованное хранилище JWT (вместо открытого SharedPreferences).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Lifecycle-aware корутины в Compose (LocalLifecycleOwner + repeatOnLifecycle):
    // поллинг ленты/ближайших ставится на паузу, когда приложение в фоне (не дёргаем сервер зря).
    // Версия = уже резолвящаяся в графе lifecycle 2.9.4 → без конфликта версий.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // ViewModel в Compose (viewModel()) — состояние приложения вынесено из YuldashApp в YuldashViewModel.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // JVM unit-тесты (каркас «с нуля»): чистая логика без Android-фреймворка. Запуск: gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // Инструментальные Compose-тесты (на устройстве/эмуляторе). Запуск: gradlew :app:connectedDebugAndroidTest
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Espresso 3.6.1: старые версии падают на новых API (NoSuchMethodException InputManager.getInstance).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
