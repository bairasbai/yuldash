package com.yuldash.app

import android.content.Context
import coil.request.ImageRequest
import com.yuldash.app.data.ApiClient
import java.net.URI

/**
 * Заявка на загрузку приватной картинки (документ водителя, селфи курьера, фото-доказательство
 * спора, фото посылки). Токен входа кладётся в заголовок ТОЛЬКО если ссылка ведёт на наш сервер.
 *
 * Зачем проверка хоста. Ссылки на такие фото приходят с сервера, а туда их кладёт САМ проверяемый
 * человек — это его заявка. Если хоть одно поле однажды примут без проверки, ссылка вида
 * `https://чужой-сервер/x.jpg` окажется в очереди модерации, админ её откроет, и Coil честно
 * отправит чужому серверу заголовок `Authorization: Bearer <токен админа>` — то есть отдаст
 * полный доступ к админке. Ровно так и было с селфи курьера (аудит 2026-08-08); серверную
 * проверку мы починили, но полагаться на одну сторону нельзя: следующее поле добавит другой
 * человек в другой день. Здесь вторая стена — токен физически не уходит на чужой домен.
 *
 * Свои — это хост API (`ApiClient.apiBase()`) и хост сайта (`BuildConfig.YULDASH_WEB_BASE_URL`,
 * с него отдаются медиа в проде). Схема должна совпадать: `https` → `http` это понижение,
 * при котором токен ушёл бы открытым текстом.
 */
internal fun isOwnMediaHost(url: String): Boolean {
    val target = url.trim()
    if (target.isEmpty()) return false
    // Относительный путь («/secure/docs/…») грузится с нашей же базы — чужого хоста тут нет.
    // «//хост/путь» — это протокол-относительный АБСОЛЮТНЫЙ адрес, его пропускать нельзя.
    if (target.startsWith("/")) return !target.startsWith("//")
    val uri = runCatching { URI(target) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return ownBases().any { own ->
        host == own.host?.lowercase() && scheme == own.scheme?.lowercase() && uri.port == own.port
    }
}

private fun ownBases(): List<URI> = listOfNotNull(
    runCatching { URI(ApiClient.apiBase()) }.getOrNull(),
    runCatching { URI(BuildConfig.YULDASH_WEB_BASE_URL) }.getOrNull(),
)

/**
 * Готовая модель для `AsyncImage`: наш адрес — с токеном, чужой — без него (картинка просто
 * не загрузится, а токен останется дома).
 */
internal fun authedImageRequest(ctx: Context, url: String, token: String): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(url)
        .apply { if (token.isNotBlank() && isOwnMediaHost(url)) addHeader("Authorization", "Bearer $token") }
        .crossfade(true)
        .build()
