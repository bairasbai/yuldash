plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    // FCM (push). Применяется в :app только при наличии google-services.json (см. app/build.gradle.kts).
    id("com.google.gms.google-services") version "4.4.2" apply false
}

