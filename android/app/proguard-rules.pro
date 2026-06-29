# ProGuard/R8 правила для релиза Юлдаша. Включён `isMinifyEnabled=true` + `isShrinkResources=true`.
# Цель: ужать/обфусцировать APK, НЕ сломав рантайм (MapKit/Firebase/Tink используют рефлексию и нативные либы).
# ⚠️ Релиз — только arm64; эмулятор x86_64 его не ставит → финальный smoke на реальном телефоне Александра.

# ── Yandex MapKit (нативные .so + рефлексия внутри SDK) ──
-keep class com.yandex.** { *; }
-keep class com.yandex.runtime.** { *; }
-dontwarn com.yandex.**

# ── Firebase: Cloud Messaging (push) + Analytics (рефлексия, авто-инициализация) ──
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepclassmembers class * {
    @com.google.firebase.* <methods>;
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── EncryptedSharedPreferences → Google Tink (рефлексия по именам key-template) ──
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# ── OkHttp / Okio (WebSocket-чат). У них свои consumer-правила; глушим предупреждения опц. зависимостей ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Наши DTO/доменные модели — на случай рефлексии/будущей сериализации (страховка от переименования) ──
-keep class com.yuldash.app.data.** { *; }
-keep class com.yuldash.app.Domain** { *; }

# ── Сигнатуры/аннотации нужны рефлексивным SDK ──
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Compose / Kotlin / Coroutines / AndroidX — обфускация по их собственным consumer-правилам (трогать не нужно).
