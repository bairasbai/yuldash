// Заглушка Sentry (io.sentry:sentry-android — артефакт есть на Maven Central, но тянуть весь
// SDK ради двух вызовов дороже, чем описать их сигнатуры). ApiClient.init() шлёт WARNING, когда
// Android Keystore недоступен и токены ложатся в НЕзашифрованные prefs.
package io.sentry

enum class SentryLevel { DEBUG, INFO, WARNING, ERROR, FATAL }

object Sentry {
    @JvmStatic fun captureMessage(message: String): SentryId = SentryId()
    @JvmStatic fun captureMessage(message: String, level: SentryLevel): SentryId = SentryId()
    @JvmStatic fun captureException(throwable: Throwable): SentryId = SentryId()
}

class SentryId
