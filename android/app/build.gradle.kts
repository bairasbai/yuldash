import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    jacoco   // QA: отчёт покрытия JVM unit-тестов (worktree-локально, в git не коммитим)
}

jacoco { toolVersion = "0.8.12" }

// JaCoCo + Robolectric: без этого код, выполненный в sandbox-classloader Robolectric,
// не засчитывается в покрытие (пробы не совпадают с classDirectories). includeNoLocationClasses
// включает такие классы, jdk.internal.* исключаем (иначе JaCoCo падает на лямбдах JDK).
tasks.withType<Test>().configureEach {
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
    // Полный текст падения прямо в лог. По умолчанию Gradle пишет только «Тест X FAILED» и
    // короткое имя исключения — по такой строке причину не понять, а HTML-отчёт в CI лежит
    // артефактом, который ещё надо скачать. Печатаем причину сразу: время разбора → минуты
    // вместо часов (первый же прогон в CI дал 76 падений без единого объяснения).
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showExceptions = true
    }
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

// Ключ Яндекс Геокодера в приложение НЕ попадает и попадать не должен.
// Аудит 2026-06-27 перевёл поиск адресов на серверный прокси `/geocode` именно затем, чтобы
// платный ключ не лежал в APK: APK раздаётся файлом, распаковывается за минуту, а `BuildConfig`
// хранит строки открытым текстом — любой желающий выжигал бы дневную квоту (и наш счёт).
// Строка `buildConfigField("YANDEX_GEOCODER_KEY", …)` пережила ту правку и продолжала зашивать
// ключ в каждую сборку, хотя в коде его никто не читал (аудит 2026-08-08). Убрана.
// URL backend API для debug/release. Можно переопределить в local.properties:
// YULDASH_DEBUG_API_BASE_URL=http://10.0.2.2:8000
// YULDASH_RELEASE_API_BASE_URL=https://yulbash.ru
// Адрес сервера для debug-сборки. Порядок: переменная окружения → local.properties → эмулятор.
//
// Переменная окружения добавлена 2026-08-12 ради теста на ДВУХ ТЕЛЕФОНАХ. Дефолт `10.0.2.2` —
// это «хост» глазами эмулятора, и на настоящем телефоне такого адреса не существует: APK
// ставится, открывается и молча висит в «нет связи». Чтобы собрать сборку для живых устройств,
// правку приходилось вносить в local.properties — файл с ключом карты, который не в git и
// у каждого свой. Теперь достаточно:
//
//     YULDASH_DEBUG_API_BASE_URL=https://yulbash.ru gradlew.bat :app:assembleDebug
//
// local.properties при этом не трогается, и никто случайно не закоммитит чужой адрес.
val debugApiBaseUrl: String = System.getenv("YULDASH_DEBUG_API_BASE_URL")
    ?: Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }.getProperty("YULDASH_DEBUG_API_BASE_URL", "http://10.0.2.2:8000")

val releaseApiBaseUrl: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_RELEASE_API_BASE_URL", "https://yulbash.ru")

// Публичный сайт (лендинг) — база для расшариваемых ссылок на поездку yulbash.ru/r/{id}.
// Переопределяется YULDASH_WEB_BASE_URL в local.properties. По умолчанию — прод-домен.
// Отдельно от API base: в debug тот указывает на localhost, а ссылка должна быть рабочей всегда.
val webBaseUrl: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_WEB_BASE_URL", "https://yulbash.ru")

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

// Sentry DSN (сбор ошибок приложения) — из local.properties (НЕ в git):
//   YULDASH_SENTRY_DSN=https://<key>@o0.ingest.sentry.io/0
// Пусто → Sentry не инициализируется (no-op), приложение работает как раньше.
val sentryDsn: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("YULDASH_SENTRY_DSN", "")

// Подпись релиза: данные из keystore.properties (в .gitignore, в git не попадает).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storePassword") != null

// Неподписанный релиз — самый тихий способ потерять вечер (разбор №2, 2026-08-03).
// Раньше без keystore.properties `signingConfig` просто не назначался: сборка проходила
// ЗЕЛЁНОЙ и выдавала APK, который не ставится на телефон и не принимается стором. Понятно это
// становилось уже при заливке. Теперь падаем сразу и с инструкцией.
// Проверяем только задачи, которые реально делают релизный артефакт: `assembleReleaseAndroidTest`
// и `testReleaseUnitTest` подписываются отладочным ключом и к релизу отношения не имеют,
// поэтому их не трогаем — иначе сломали бы CI, где keystore нет и не должно быть.
val releaseArtifactTasks = setOf("assembleRelease", "bundleRelease", "packageRelease", "installRelease")

// Осознанное исключение для проверки R8 в CI (правка 2026-08-06).
// Защита выше и шаг CI «Build release (R8 minify check)» противоречили друг другу: шагу нужен
// релизный прогон, чтобы поймать «в debug работало, в release упало» (вырезанные R8 классы —
// рефлексия, JSON, MapKit), а подписи у робота нет и быть не должно. В итоге чек краснел
// с 3 августа, и красный CI переставал что-либо значить.
// Флаг разделяет два разных намерения: человек собирает релиз (подпись обязательна) и робот
// проверяет ужатие кода (артефакт выбрасывается, подпись не нужна). Ставит его ТОЛЬКО ci.yml.
val unsignedReleaseAllowed = providers.gradleProperty("yuldash.unsignedRelease").isPresent
gradle.taskGraph.whenReady {
    if (!hasReleaseKeystore && !unsignedReleaseAllowed && allTasks.any { it.name in releaseArtifactTasks }) {
        throw GradleException(
            "Релизная сборка без подписи. Нужен файл android/keystore.properties " +
                "(storeFile, storePassword, keyAlias, keyPassword) — он в .gitignore и в git не попадает. " +
                "Без него APK не установится на телефон и не пройдёт модерацию стора. " +
                "Для проверки без ключа собирай debug: gradlew :app:assembleDebug, " +
                "а если нужна именно проверка R8 без установки — добавь -Pyuldash.unsignedRelease " +
                "(так делает CI; полученный APK НЕЛЬЗЯ ставить и заливать)."
        )
    }
    // Неподписанный релиз собрался — говорим это вслух. Иначе кто-нибудь скопирует флаг из CI
    // себе в команду, получит зелёную сборку и APK, который не примет стор.
    if (!hasReleaseKeystore && unsignedReleaseAllowed && allTasks.any { it.name in releaseArtifactTasks }) {
        logger.warn(
            "⚠️  Релиз собирается БЕЗ ПОДПИСИ (-Pyuldash.unsignedRelease). Это режим проверки R8 " +
                "в CI: полученный APK НЕЛЬЗЯ ставить на телефон и НЕЛЬЗЯ заливать в стор."
        )
    }
}

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
        versionCode = 2
        versionName = "1.0.0"      // первый публичный релиз
        // Рунер инструментальных тестов (без него AGP берёт легаси android.test.* → краш Compose-тестов).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Пробрасываем ключ в BuildConfig (в коде не хардкодим).
        buildConfigField("String", "YANDEX_MAPKIT_KEY", "\"$mapkitKey\"")
        buildConfigField("String", "YULDASH_SUPPORT_PHONE", "\"$supportPhone\"")
        buildConfigField("String", "TELEGRAM_BOT", "\"$telegramBot\"")
        buildConfigField("String", "VK_APP_ID", "\"$vkAppId\"")
        buildConfigField("String", "YULDASH_WEB_BASE_URL", "\"$webBaseUrl\"")
        buildConfigField("boolean", "SMS_LOGIN_ENABLED", "$smsLoginEnabled")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Robolectric: JVM unit-тесты, которым нужны Android-ресурсы/манифест (Compose-компоненты, Context).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Печатать ПРИЧИНУ падения прямо в консоль. Без этого Gradle пишет только
            // «AssertionError at Файл.kt:73», а текст (что ожидали и что нашли) остаётся
            // в HTML-отчёте — то есть в CI его не видно вообще, и чинить приходится вслепую.
            all {
                // Куча для JVM тестов. По умолчанию Gradle даёт 512 МБ — мало, когда в одной
                // машине 83 класса Robolectric с Compose-песочницами. Симптом был характерный:
                // AdminScreensIntegrationTest падал по 60-секундному таймауту в КОНЦЕ прогона и
                // каждый раз в другом методе, а в одиночку проходил за 36 секунд. Так выглядит
                // не сломанный тест, а пробуксовка сборщика мусора под конец.
                it.maxHeapSize = "4g"
                // ⏸ Версия 8 (общий кэш соединений JVM) — ОПРОВЕРГНУТА, см. историю
                // в AdminScreensIntegrationTest. Выключение переиспользования соединений
                // (`http.keepAlive=false`) стояло здесь один день и было снято: после него
                // в полном прогоне упало ДВА класса вместо одного. Похоже, оно сделало хуже —
                // без переиспользования каждый запрос открывает и закрывает своё TCP-соединение,
                // и сокетов в ожидании закрытия становится в разы больше.
                it.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStackTraces = true
                    showCauses = true
                }
                // Один упавший тест не должен обрывать прогон: нужен ПОЛНЫЙ список проблем
                // за один заход, иначе каждый круг CI (11 минут) вскрывает по одной ошибке.
                it.ignoreFailures = false
            }
        }
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
            enableUnitTestCoverage = true
            // enableAndroidTestCoverage ВЫКЛЮЧЕН намеренно (2026-08-08).
            //
            // Он инструментировал классы прямо в debug-APK (задача `jacocoDebug`), и на 91-й
            // ветке навигации `when` в `YuldashApp()` JaCoCo перестал справляться:
            // «Unable to instrument file with Jacoco: YuldashAppKt.class» — сборка падала
            // на ровном месте от добавления одного экрана.
            //
            // Терять при этом нечего: покрытие меряется по JVM unit-тестам
            // (`jacocoTestReport` читает outputs/unit_test_code_coverage), и порог
            // `jacocoCoverageVerification` смотрит туда же. Покрытие инструментальных тестов
            // не читал никто: ни CI, ни отчёт, ни docs/testing.md — флаг просто стоял.
            // Побочно: debug-APK перестаёт таскать инструментацию, ставится и работает быстрее.
            //
            // Понадобится покрытие по тестам НА УСТРОЙСТВЕ — включать вместе с разрезанием
            // навигационного `when` (первопричина, а не флаг).
        }

        release {
            buildConfigField("String", "YULDASH_API_BASE_URL", "\"$releaseApiBaseUrl\"")
            // R8: ужатие + обфускация. keep-правила для MapKit/Firebase/Tink/OkHttp — в proguard-rules.pro.
            // ⚠️ Релиз arm64-only → на x86_64-эмуляторе не ставится; финальный smoke — на реальном телефоне.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Раздача друзьям = прямой APK, не Play. Нужны ОБЕ ABI: arm64-v8a (совр. телефоны)
            // + armeabi-v7a (старые/дешёвые 32-бит). Без v7a такой телефон при установке ловит
            // «нет подходящей архитектуры» → «Приложение не установлено» (ловили вживую 2026-07-04).
            // x86_64 не берём — это только эмуляторы, друзьям не нужно (лишний вес). Под Play — AAB-сплиты.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    // AAB: НЕ дробим ресурсы по языку. В приложении свой рантайм-переключатель RU/BA
    // (appText/AppLanguage) → Play не должен выкидывать «лишний» язык, иначе второй язык пропадёт.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")   // плавный уход системного сплэша (фейд в интро)
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
    // Sentry (сбор ошибок/крашей). Инициализируется вручную в YuldashApplication ТОЛЬКО
    // при заданном BuildConfig.SENTRY_DSN (без Gradle-плагина — меньше риска для сборки).
    // 8.x обязателен из-за 16 KB страниц памяти: нативные `libsentry*.so` версии 7.x не выровнены,
    // и Google Play такое приложение больше не принимает (проверено на эмуляторе — система сама
    // показывает предупреждение о несовместимости). Поддержка 16 KB появилась в Sentry 8.0.0.
    // Наш способ инициализации (`SentryAndroid.init` вручную, только при заданном DSN) в 8.x не изменился.
    implementation("io.sentry:sentry-android:8.51.0")
    // ZXing core (только генерация QR-матрицы, без Android-модуля) — QR оплаты Сбербанка (СБП по номеру).
    implementation("com.google.zxing:core:3.5.3")
    // Lifecycle-aware корутины в Compose (LocalLifecycleOwner + repeatOnLifecycle):
    // поллинг ленты/ближайших ставится на паузу, когда приложение в фоне (не дёргаем сервер зря).
    // Версия = уже резолвящаяся в графе lifecycle 2.9.4 → без конфликта версий.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // ViewModel в Compose (viewModel()) — состояние приложения вынесено из YuldashApp в YuldashViewModel.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    // Google Play In-App Review: системный запрос оценки в Play после хорошей поездки (только 5★).
    // Без Play/на эмуляторе — тихий no-op. Play сам решает, показывать ли (правило Google).
    implementation("com.google.android.play:review-ktx:2.0.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // JVM unit-тесты (каркас «с нуля»): чистая логика без Android-фреймворка. Запуск: gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // Реальный org.json в unit-classpath: Android-стаб в JVM-тестах бросает "not mocked",
    // с этой либой JSONObject работает → можно тестировать парсинг DTO (parseMessageDto и др.).
    testImplementation("org.json:json:20240303")
    // Robolectric: гоняем Compose-компоненты и Android-логику на JVM (без эмулятора, обходит краш
    // Espresso на API 37). BOM в test-classpath → версии compose-test берутся из него.
    testImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    // MockWebServer: локальный HTTP-сервер для тестов сетевого слоя ApiClient (парсинг ответов + ошибки).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Инструментальные Compose-тесты (на устройстве/эмуляторе). Запуск: gradlew :app:connectedDebugAndroidTest
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Espresso 3.6.1: старые версии падают на новых API (NoSuchMethodException InputManager.getInstance).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// --- QA: JaCoCo отчёт покрытия по debug unit-тестам ---
// AGP кладёт .exec (enableUnitTestCoverage=true) → build/outputs/unit_test_code_coverage/...
// Отчёт по чистой логике (UI/сеть/сгенерированное исключаем — их покрывают инструментальные тесты).
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    // Исключаем ТОЛЬКО сгенерированное (R/BuildConfig/Manifest/тесты) — число честное, по всему приложению.
    // UI-экраны НЕ исключаем: они реально имеют ~0% (нужны инструментальные тесты) и это часть правды.
    val excludes = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*.*",
    )
    val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(excludes) }
    val javaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) { exclude(excludes) }
    classDirectories.setFrom(files(kotlinClasses, javaClasses))
    sourceDirectories.setFrom(files("$projectDir/src/main/java"))
    // Сужаем до папки unit-покрытия (не весь build/) — иначе Gradle видит пересечение входов
    // с выходами assembleDebug-тасок (dex/assets) и падает на валидации при общем прогоне.
    executionData.setFrom(fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") })
}

// --- QA: порог покрытия «храповик» — опускаться нельзя, поднимать можно ---
//
// Зачем порог. Покрытие без порога — цифра в отчёте, на которую никто не смотрит. Порог
// превращает её в правило: код без тестов роняет сборку, а не «когда-нибудь допишем».
//
// Почему меряем ЛОГИКУ, а не всё приложение. В Compose-проекте 90% байткода — рисование
// экранов, и общий процент говорит в основном о нём. Гнаться за ним значит писать тесты
// на кнопки. А вот слой данных — сеть, деньги, токены, паспорт поездки — обязан быть покрыт
// как в любой взрослой компании: там ошибка не «кривая вёрстка», а «пассажир заплатил дважды».
//
// Планка ставится чуть ниже достигнутого: ловит откат, не заставляет писать пустые тесты.
// Дорастём — поднимем. Обратно не опускаем.
// Факт на 2026-08-04: слой данных 82,7%, всё приложение 24,7%. Планки — чуть ниже факта.
val logicCoverageFloor = "0.78".toBigDecimal()   // слой данных: com.yuldash.app.data.*
val appCoverageFloor = "0.22".toBigDecimal()     // всё приложение вместе с экранами

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    group = "verification"
    description = "Падает, если покрытие опустилось ниже достигнутого уровня"

    val excludes = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*.*",
    )
    val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(excludes) }
    val javaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) { exclude(excludes) }
    classDirectories.setFrom(files(kotlinClasses, javaClasses))
    sourceDirectories.setFrom(files("$projectDir/src/main/java"))
    executionData.setFrom(fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") })

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = appCoverageFloor
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.yuldash.app.data")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = logicCoverageFloor
            }
        }
    }
}

// `gradlew check` (и любой CI-прогон) теперь проверяет и покрытие тоже.
tasks.named("check") { dependsOn("jacocoCoverageVerification") }
