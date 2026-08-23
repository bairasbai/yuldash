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
 * Адрес картинки → модель для `AsyncImage`, но ТОЛЬКО если ссылка ведёт на наш хост.
 * Чужая ссылка → `null`: Coil по ней не пойдёт вообще.
 *
 * Зачем не грузить, а не просто «не давать токен». Сам факт загрузки — уже утечка: чтобы
 * забрать картинку, телефон стучится на чужой сервер, и его хозяин записывает IP, город,
 * время и модель устройства КАЖДОГО, кто открыл экран. Нажимать ничего не надо. Для
 * приложения «между своими» это тихая слежка за пассажиром — ровно та дыра, что чинили
 * для аватаров (`_clean_avatar_url`, аудит 2026-08-07) и фото посылки (`is_own_media_url`).
 *
 * Правило клиентское намеренно: серверных полей с адресами много, и однажды кто-то заведёт
 * новое без проверки (так и вышло с фото в чате — `voice_url` сервер проверяет, а текст
 * сообщения с префиксом `[img]` нет). Здесь вторая стена, общая для всех экранов.
 */
internal fun ownImageModel(url: String): String? = url.trim().takeIf { isOwnMediaHost(it) }

/**
 * Готовая модель для `AsyncImage`: наш адрес — с токеном, чужой — не грузится совсем
 * (ни картинки, ни токена, ни следа на чужом сервере).
 */
internal fun authedImageRequest(ctx: Context, url: String, token: String): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(ownImageModel(url))
        .apply { if (token.isNotBlank() && isOwnMediaHost(url)) addHeader("Authorization", "Bearer $token") }
        .crossfade(true)
        .build()
