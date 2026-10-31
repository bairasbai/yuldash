package com.yuldash.app

import android.content.Context
import com.yuldash.app.data.ApiException
import org.json.JSONArray

/** Последний публичный список сервера. Пустой ответ означает выключенный вход, а не сбой кеша. */
internal object LoginMethods {
    private const val PREFS = "login_methods"
    private const val KEY = "methods"
    private val supported = listOf("sms", "telegram")
    val fallback: List<String> = listOf("telegram")

    fun normalize(methods: List<String>): List<String> = supported.filter { it in methods }

    fun decode(cached: String?): List<String> = if (cached == null) fallback else runCatching {
        val array = JSONArray(cached)
        normalize((0 until array.length()).mapNotNull { array.opt(it) as? String })
    }.getOrDefault(fallback)

    fun read(context: Context): List<String> = runCatching {
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }.getOrDefault(fallback)

    fun save(context: Context, methods: List<String>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, JSONArray(normalize(methods)).toString()).apply()
        }
    }
}

internal fun loginNeedsPhone(error: Throwable): Boolean = error is ApiException &&
    (error.detailCode == "phone_required" || error.status == 403 && error.detailCode.isEmpty())

internal fun loginMethodDisabled(error: Throwable): Boolean =
    (error as? ApiException)?.detailCode == "login_method_disabled"
