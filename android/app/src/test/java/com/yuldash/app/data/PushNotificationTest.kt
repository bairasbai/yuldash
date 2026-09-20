package com.yuldash.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Приём пушей: что человек видит, когда телефон лежит в кармане.
 *
 * Почему это стоит тестов. Пуш — единственный способ дозваться до водителя с погашенным
 * экраном. Ошибка тут не «некрасиво», а «заказ никто не увидел». Плюс два тихих бага,
 * которые уже случались в этом коде и закрыты ниже:
 *
 *  • **Уведомления сыпались столбиком.** Двадцать сообщений в чате давали двадцать строк
 *    в шторке, потому что номер уведомления брался из текущего времени. Теперь новое
 *    сообщение того же чата ЗАМЕНЯЕТ прежнее.
 *  • **Тап уводил не туда.** Номер брони брался как ключ без учёта типа, поэтому чат брони
 *    №5 и статус поездки №5 делили один ключ — нажатие на чат открывало экран поездки.
 *
 * И отдельно — тумблеры в настройках. Выключил уведомления → в шторке пусто; выключил
 * звуки → приходит, но молча. Если тумблер не работает, человек просто снесёт приложение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushNotificationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun manager() = ctx.getSystemService(NotificationManager::class.java)

    private fun service(): FcmService =
        Robolectric.buildService(FcmService::class.java).create().get()

    private fun message(data: Map<String, String>): RemoteMessage {
        val b = RemoteMessage.Builder("test@fcm")
        b.addData("recipient_user_id", "2")
        data.forEach { (k, v) -> b.addData(k, v) }
        return b.build()
    }

    @Before
    fun reset() {
        ApiClient.saveToken("header.eyJzdWIiOiIyIn0.signature")
        manager().cancelAll()
        AppPrefs.setNotifications(ctx, true)
        AppPrefs.setSounds(ctx, true)
    }

    @Test
    fun `каналы заводятся сразу, а не после первого пуша`() {
        FcmService.ensureChannels(ctx)
        val ids = manager().notificationChannels.map { it.id }
        assertTrue("иначе человек открывает «Уведомления» и видит пустой список", FcmService.CHANNEL_DEFAULT in ids)
        assertTrue("сообщения глушатся отдельно от поездок", FcmService.CHANNEL_CHAT in ids)
    }

    @Test
    fun `повторное создание каналов безопасно`() {
        FcmService.ensureChannels(ctx)
        FcmService.ensureChannels(ctx)
        assertTrue(manager().notificationChannels.size >= 2)
    }

    @Test
    fun `обычный пуш доходит до шторки`() {
        service().onMessageReceived(message(mapOf("title" to "Водитель подтвердил", "body" to "Выезд в 8:00", "type" to "booking", "id" to "5")))
        val shown = shadowOf(manager()).allNotifications
        assertEquals(1, shown.size)
    }

    @Test
    fun `выключенные уведомления действительно молчат`() {
        AppPrefs.setNotifications(ctx, false)
        service().onMessageReceived(message(mapOf("title" to "Заголовок", "body" to "Текст")))
        assertTrue("тумблер в настройках обязан работать", shadowOf(manager()).allNotifications.isEmpty())
    }

    @Test
    fun `сообщения чата идут своим каналом`() {
        service().onMessageReceived(message(mapOf("title" to "Айдар", "body" to "Я подъезжаю", "type" to "chat", "id" to "5")))
        val n = shadowOf(manager()).allNotifications.first()
        assertEquals(FcmService.CHANNEL_CHAT, n.channelId)
    }

    @Test
    fun `переписка по посылке — тоже чат, а не «поездки»`() {
        service().onMessageReceived(message(mapOf("title" to "Курьер", "body" to "Оставить у соседей?", "type" to "parcel_chat", "id" to "9")))
        val n = shadowOf(manager()).allNotifications.first()
        assertEquals(
            "иначе сообщение курьера звенит в приглушённом канале «Поездки»",
            FcmService.CHANNEL_CHAT, n.channelId,
        )
    }

    @Test
    fun `новое сообщение того же чата заменяет прежнее, а не копится столбиком`() {
        val svc = service()
        svc.onMessageReceived(message(mapOf("title" to "Айдар", "body" to "первое", "type" to "chat", "id" to "5")))
        svc.onMessageReceived(message(mapOf("title" to "Айдар", "body" to "второе", "type" to "chat", "id" to "5")))
        svc.onMessageReceived(message(mapOf("title" to "Айдар", "body" to "третье", "type" to "chat", "id" to "5")))
        assertEquals(
            "двадцать сообщений не должны давать двадцать строк в шторке",
            1, shadowOf(manager()).allNotifications.size,
        )
    }

    @Test
    fun `чат и статус поездки с одним номером — разные уведомления`() {
        val svc = service()
        svc.onMessageReceived(message(mapOf("title" to "Чат", "body" to "сообщение", "type" to "chat", "id" to "5")))
        svc.onMessageReceived(message(mapOf("title" to "Поездка", "body" to "подтверждена", "type" to "booking", "id" to "5")))
        assertEquals(
            "раньше они делили ключ, и тап по чату открывал экран поездки",
            2, shadowOf(manager()).allNotifications.size,
        )
    }

    @Test
    fun `пуш без текста не роняет приём`() {
        service().onMessageReceived(message(emptyMap()))
        val n = shadowOf(manager()).allNotifications.firstOrNull()
        assertNotNull("пустой пуш всё равно должен что-то показать, а не упасть", n)
    }

    @Test
    fun `оффер такси идёт мимо обычной шторки — у него своя карточка`() {
        service().onMessageReceived(
            message(
                mapOf(
                    "type" to "instant_offer", "order_id" to "31",
                    "from" to "Сибай, Ленина 1", "to" to "Уфа", "price" to "1500", "ttl_sec" to "20",
                ),
            ),
        )
        // Полноэкранная карточка «Новый заказ» — отдельный канал с максимальной важностью.
        val ids = shadowOf(manager()).allNotifications.map { it.channelId }
        assertTrue("оффер не должен теряться среди обычных уведомлений", ids.none { it == FcmService.CHANNEL_DEFAULT })
    }

    @Test
    fun `битые числа в оффере не роняют приём`() {
        service().onMessageReceived(
            message(mapOf("type" to "instant_offer", "order_id" to "не число", "price" to "дорого")),
        )
        // Главное — не упасть: водитель не должен терять следующие заказы из-за одного кривого пуша.
    }

    @Test
    fun `новый токен устройства уходит на сервер без падения`() {
        ApiClient.saveToken("") // сценарий без входа: никакой фоновой сетевой регистрации
        service().onNewToken("новый-токен-устройства")
        // Без входа в аккаунт регистрация — тихий no-op, но упасть она не имеет права.
    }

    // --- Погашенный экран: что прочитает тот, кто взял телефон со стола (волна 110) ---

    @Test
    fun `жалоба не читается с заблокированного экрана`() {
        service().onMessageReceived(
            message(
                mapOf(
                    "title" to "Поступила жалоба", "body" to "Категория: не заплатил",
                    "type" to "safety", "private" to "1",
                ),
            ),
        )
        val n = shadowOf(manager()).allNotifications.single()
        // Защита здесь — именно ОТДЕЛЬНАЯ безопасная версия. Само по себе VISIBILITY_PRIVATE
        // ничего не прячет: это значение по умолчанию у любого уведомления, и без публичной
        // версии система на замке показывает полный текст.
        assertNotNull(
            "нет безопасной версии — на замке высветится «Категория: не заплатил» целиком, " +
                "и это прочитает любой, кто взял телефон со стола",
            n.publicVersion,
        )
        val onLock = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertTrue(
            "на замке всё равно видна суть жалобы: $onLock",
            onLock != null && !onLock.contains("заплатил"),
        )
        assertEquals(
            "приложение должно попросить систему считать это личным",
            Notification.VISIBILITY_PRIVATE, n.visibility,
        )
    }

    @Test
    fun `водитель подъезжает виден сразу, не разблокируя телефон`() {
        service().onMessageReceived(
            message(mapOf("title" to "Водитель подъезжает", "body" to "Баймак → Сибай", "type" to "booking")),
        )
        val n = shadowOf(manager()).allNotifications.single()
        assertTrue(
            "полезный пуш спрятали за заглушкой: пассажирка с сумками стоит на улице и должна " +
                "прочитать это с погашенного экрана, не разблокируя телефон",
            n.publicVersion == null,
        )
    }
}
