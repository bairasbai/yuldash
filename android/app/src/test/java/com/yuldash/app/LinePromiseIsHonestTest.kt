package com.yuldash.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * «Ты на линии — заказы придут сюда» должно быть правдой.
 *
 * История. Ильдар выходит на линию и гасит экран: приложение зелёным обещает, что заказ
 * придёт уведомлением. Он стоит на трассе весь вечер — ни одного заказа. Уходит домой
 * с мыслью, что в районе никто не ездит.
 *
 * На самом деле заказы приходили. Их молча выбрасывали: когда-то раньше Ильдар выключил
 * тумблер «Уведомления» в настройках Юлдаша — может, устал от звуков. Выброс оффера этот
 * тумблер учитывал, а зелёная плашка на экране линии — нет: она смотрела только на системное
 * разрешение (аудит 2026-08-08, волна 117).
 *
 * Выключателей на самом деле три: отказ в системном разрешении, выключенные уведомления
 * приложения в настройках телефона и наш собственный тумблер. Теперь их знает одна общая
 * проверка «дойдёт ли уведомление» — та же, что решает, показывать ли обещание.
 *
 * Отдельно: кнопка «Включить уведомления» раньше всегда вела в системные настройки. Если
 * выключен НАШ тумблер, человек попадал туда, где всё разрешено, и оставался ни с чем.
 * Теперь наш тумблер включается одним тапом прямо на экране линии.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinePromiseIsHonestTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun manager() = ctx.getSystemService(NotificationManager::class.java)!!

    @Before
    fun setUp() {
        AppPrefs.setNotifications(ctx, true)
        AppPrefs.setSounds(ctx, true)
        manager().cancelAll()
    }

    @After
    fun tearDown() = AppPrefs.setNotifications(ctx, true)

    private fun приходитЗаказ(): Int {
        manager().cancelAll()
        TaxiOfferNotifier.show(
            ctx, orderId = 7, fromText = "Баймак", toText = "Сибай",
            priceRub = 300, lang = AppLanguage.Ru, ttlSec = 20,
        )
        return shadowOf(manager()).allNotifications.size
    }

    @Test
    fun `обещание совпадает с тем, что происходит на самом деле`() {
        AppPrefs.setNotifications(ctx, false)

        val обещаем = notificationsAllowed(ctx)
        val дошло = приходитЗаказ()

        assertFalse(
            "экран линии обещает «заказы придут сюда», а заказ до водителя не доходит: " +
                "он простоит вечер на трассе впустую и решит, что заказов в районе нет",
            обещаем && дошло == 0,
        )
        assertEquals("при выключенном тумблере заказ не должен доходить", 0, дошло)
        assertFalse("обещание осталось зелёным при выключенном тумблере", обещаем)
    }

    @Test
    fun `с включённым тумблером заказ доходит и обещание честное`() {
        // Обратная сторона: строгость не должна съесть работающий случай.
        assertTrue("обещание пропало у водителя, у которого всё включено", notificationsAllowed(ctx))
        assertEquals("заказ не дошёл при полностью разрешённых уведомлениях", 1, приходитЗаказ())
    }

    @Test
    fun `видно, что выключено именно у нас, а не в системе`() {
        // От этого зависит, куда вести человека: в системные настройки или включить одним тапом.
        AppPrefs.setNotifications(ctx, false)
        assertTrue("не отличаем свой выключатель от системного", notificationsOffInApp(ctx))

        AppPrefs.setNotifications(ctx, true)
        assertFalse("при включённом тумблере не должно быть подсказки про него", notificationsOffInApp(ctx))
    }

    @Test
    fun `экран линии не заводит свою проверку в обход общей`() {
        // Сторож: правило «дойдёт ли уведомление» должно жить в ОДНОМ месте. Пока экран
        // спрашивает общую проверку, новый выключатель доедет до него сам; напишет свою —
        // и они снова разойдутся, как разошлись в волне 117.
        val экран = java.io.File("src/main/java/com/yuldash/app/ProfileScreen.kt").readText()
        val блок = экран.substringAfter("val notifOk =").substringBefore("\n")
        assertTrue(
            "экран линии считает доступность уведомлений сам: $блок",
            блок.contains("notificationsAllowed("),
        )
        val оффер = java.io.File("src/main/java/com/yuldash/app/TaxiOfferNotifier.kt").readText()
        assertFalse(
            "у выброса заказа снова своя проверка тумблера — экран о ней не узнает",
            оффер.contains("if (!AppPrefs.notifications(ctx)) return"),
        )
    }
}
