package com.yuldash.app

// Google Play In-App Review — честный бесплатный рост.
// Запускаем системный Play-флоу оценки ТОЛЬКО после того, как пассажир поставил поездке 5★.
// Никакого своего диалога поверх (правило Google) — Play сам решает, показывать ли карточку.
// На эмуляторе / устройстве без Play — тихий no-op. Любая ошибка глотается молча.
// Частота ограничена: не чаще ~раз в 30 дней (флаг last_store_review_ms в prefs), чтобы не назойливо.

import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

private const val STORE_REVIEW_PREFS = "yuldash_review"
private const val KEY_LAST_STORE_REVIEW_MS = "last_store_review_ms"
private const val STORE_REVIEW_MIN_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000  // ~30 дней

/**
 * Мягко просит оценку в Google Play. Вызывать только на позитивном сигнале (5★ от пассажира).
 * Безопасно при любом контексте/окружении: без Play — ничего не произойдёт, без крашей и логов.
 */
fun maybeRequestStoreReview(context: Context) {
    runCatching {
        val prefs = context.applicationContext
            .getSharedPreferences(STORE_REVIEW_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_STORE_REVIEW_MS, 0L)
        val now = System.currentTimeMillis()
        // Не назойливо: если недавно уже просили — выходим тихо.
        if (last != 0L && now - last < STORE_REVIEW_MIN_INTERVAL_MS) return

        val activity = context.findActivityCompat() ?: return
        val manager = ReviewManagerFactory.create(context.applicationContext)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) return@addOnCompleteListener   // нет Play / офлайн — молча
            val reviewInfo = request.result ?: return@addOnCompleteListener
            // Отмечаем попытку сразу: Play мог и не показать карточку (его право),
            // но повторно дёргать раньше срока не будем — так честнее и тише.
            prefs.edit().putLong(KEY_LAST_STORE_REVIEW_MS, now).apply()
            runCatching {
                manager.launchReviewFlow(activity, reviewInfo)
                    .addOnCompleteListener { /* результат намеренно не раскрывается Google — no-op */ }
            }
        }
    }
}
