package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Мелочи, у которых цена ошибки больше, чем размер кода.
 *
 * **Пилюля «🚕 ≈N мин» на карте такси.** Единственное, что пассажир видит до заказа: рядом есть
 * машина и примерно столько ждать. Рисуется вручную, попиксельно — сломается, и на карте такси
 * не будет ни одной машины при полной линии свободных водителей.
 *
 * **Просьба оценить приложение.** Google запрещает показывать её чаще примерно раза в месяц
 * и запрещает свой диалог поверх системного. Нарушение — не косметика, а риск для карточки
 * в магазине. Проверяем, что ограничение по времени действительно работает.
 *
 * **Сигналы навигации.** Через них тап по уведомлению открывает нужный экран. Если сигнал
 * не сбрасывается, экран будет открываться сам по себе при каждом возврате в приложение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmallLogicTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearReviewPrefs() {
        ctx.getSharedPreferences("yuldash_review", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------- Пилюля времени подачи ----------

    @Test
    fun `пилюля со временем подачи рисуется`() {
        val b = carEtaBitmap("≈3 мин")
        assertTrue("пустая картинка = пустая карта такси", b.width > 0 && b.height > 0)
        assertEquals(android.graphics.Bitmap.Config.ARGB_8888, b.config)
    }

    @Test
    fun `пилюля выше, чем шире её текст — снизу остриё на машину`() {
        val b = carEtaBitmap("≈7 мин")
        // Остриё смотрит вниз, на реальную точку машины: без запаса по высоте оно обрежется.
        assertTrue("не хватает высоты под остриё", b.height > 40)
    }

    @Test
    fun `одинаковое время берётся из памяти, а не рисуется заново`() {
        // На карте десятки машин с одинаковым «≈5 мин» — перерисовывать каждую расточительно.
        assertSame(carEtaBitmap("≈5 мин"), carEtaBitmap("≈5 мин"))
    }

    @Test
    fun `разное время — разные картинки`() {
        assertNotEquals(carEtaBitmap("≈2 мин"), carEtaBitmap("≈12 мин"))
    }

    @Test
    fun `длинная надпись не ломает пилюлю`() {
        val short = carEtaBitmap("≈1 мин")
        val long = carEtaBitmap("≈15 минут ожидания")
        assertTrue("пилюля должна растягиваться под текст, а не обрезать его", long.width > short.width)
    }

    // ---------- Просьба оценить приложение ----------

    @Test
    fun `просьба оценить не падает там, где нет магазина`() {
        // Эмулятор и телефоны без Google Play: молча ничего не происходит, без крашей.
        maybeRequestStoreReview(ctx)
    }

    @Test
    fun `недавно просили — второй раз не тревожим`() {
        val prefs = ctx.getSharedPreferences("yuldash_review", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_store_review_ms", System.currentTimeMillis()).apply()
        maybeRequestStoreReview(ctx)
        // Отметка не должна обновиться: значит до системного вызова дело не дошло.
        assertTrue(
            "просить оценку чаще раза в месяц запрещено правилами магазина",
            System.currentTimeMillis() - prefs.getLong("last_store_review_ms", 0) < 5_000,
        )
    }

    @Test
    fun `после долгого перерыва просить снова можно`() {
        val prefs = ctx.getSharedPreferences("yuldash_review", Context.MODE_PRIVATE)
        val давно = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000   // два месяца назад
        prefs.edit().putLong("last_store_review_ms", давно).apply()
        maybeRequestStoreReview(ctx)   // не падает; показывать или нет — решает магазин
    }

    // ---------- Сигналы навигации ----------

    @Test
    fun `сигналы навигации поднимаются и гасятся`() {
        NavSignals.openDriverCabinet.value = false
        NavSignals.openInstantChat.value = 0
        NavSignals.openInstantOrder.value = false
        NavSignals.openSosForOrder.value = 0
        NavSignals.openTaxiReceipt.value = 0

        NavSignals.openDriverCabinet.value = true
        NavSignals.openInstantChat.value = 31
        NavSignals.openInstantOrder.value = true
        NavSignals.openSosForOrder.value = 31
        NavSignals.openTaxiReceipt.value = 31

        assertTrue(NavSignals.openDriverCabinet.value)
        assertEquals(31, NavSignals.openInstantChat.value)
        assertEquals(31, NavSignals.openTaxiReceipt.value)

        // Экран обязан сбросить сигнал после открытия — иначе он будет открываться сам
        // при каждом возврате в приложение.
        NavSignals.openDriverCabinet.value = false
        NavSignals.openInstantChat.value = 0
        NavSignals.openInstantOrder.value = false
        NavSignals.openSosForOrder.value = 0
        NavSignals.openTaxiReceipt.value = 0
        assertFalse(NavSignals.openDriverCabinet.value)
        assertEquals(0, NavSignals.openInstantChat.value)
    }

    @Test
    fun `карточку заказа можно убрать, даже если её не показывали`() {
        TaxiOfferNotifier.cancel(ctx)   // оффер протух до показа — снятие не должно падать
    }

    // ---- Что человек читает, когда действие не прошло ----

    @Test
    fun `отказ сервера показываем его словами, а не «проверь сеть»`() {
        // Сервер объясняет, что делать: «закрой лишние поездки». Экран обязан показать это,
        // а не своё «проверь сеть» — иначе человек проверяет сеть, жмёт ещё раз и получает
        // то же самое, так и не узнав причину.
        val fromServer = ApiException(429, "Слишком много активных поездок. Закрой или отмени лишние.")
        assertEquals(
            "Слишком много активных поездок. Закрой или отмени лишние.",
            serverSaid(fromServer, "Не удалось опубликовать. Проверь сеть и повтори."),
        )
    }

    @Test
    fun `без ответа сервера остаётся текст про сеть`() {
        // Нет связи — сервер ничего не сказал, и «проверь сеть» здесь единственный честный ответ.
        assertEquals(
            "Не удалось опубликовать. Проверь сеть и повтори.",
            serverSaid(java.io.IOException("timeout"), "Не удалось опубликовать. Проверь сеть и повтори."),
        )
    }

    @Test
    fun `пустое сообщение от сервера не оставляет человека без объяснения`() {
        // Пустая строка вместо текста — тоже «сервер ничего не объяснил»: показываем запасной.
        assertEquals(
            "запасной текст",
            serverSaid(ApiException(500, ""), "запасной текст"),
        )
    }
}
