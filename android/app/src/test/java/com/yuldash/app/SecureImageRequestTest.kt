package com.yuldash.app

import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Токен входа не должен уезжать на чужой домен.
 *
 * Находка аудита 2026-08-08: селфи курьера принималось сервером ЛЮБОЙ ссылкой, а экран
 * модерации грузил её через Coil с заголовком `Authorization: Bearer <токен админа>`.
 * То есть строка «https://чужой-сервер/x.jpg», вписанная в свою же заявку, отдавала полный
 * доступ к админке в момент, когда админ открывал очередь. Сервер починен, но проверка хоста
 * на клиенте — вторая стена: следующее поле с URL добавит другой человек в другой день.
 */
class SecureImageRequestTest {

    private val ourHost = java.net.URI(ApiClient.apiBase()).host

    @Test
    fun `свой адрес API признаётся своим`() {
        assertTrue(isOwnMediaHost("${ApiClient.apiBase()}/secure/docs/12_abc.jpg"))
    }

    @Test
    fun `путь без хоста считается своим`() {
        assertTrue(isOwnMediaHost("/secure/docs/12_abc.jpg"))
    }

    @Test
    fun `чужой хост отвергается`() {
        assertFalse(isOwnMediaHost("https://evil.example/pixel.jpg"))
    }

    @Test
    fun `похожий домен отвергается`() {
        assertFalse(isOwnMediaHost("https://$ourHost.evil.example/secure/docs/x.jpg"))
        assertFalse(isOwnMediaHost("https://evil.example/?x=https://$ourHost/secure/docs/x.jpg"))
    }

    @Test
    fun `протокол-относительный адрес отвергается`() {
        // «//evil.example/x.jpg» выглядит как путь, но это АБСОЛЮТНЫЙ адрес чужого хоста.
        assertFalse(isOwnMediaHost("//evil.example/x.jpg"))
    }

    @Test
    fun `пустая и битая строка отвергаются`() {
        assertFalse(isOwnMediaHost(""))
        assertFalse(isOwnMediaHost("   "))
        assertFalse(isOwnMediaHost("ht tp://:::"))
    }

    @Test
    fun `адрес сайта считается своим`() {
        assertTrue(isOwnMediaHost("${BuildConfig.YULDASH_WEB_BASE_URL.trimEnd('/')}/media/chat/a.jpg"))
    }
}
