package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Телефон переходит из рук в руки — и не должен унести с собой чужую жизнь.
 *
 * История. Ильдар полгода возил посылки по району, потом отдал старый телефон сыну и вышел
 * из аккаунта. Выход честно стирал токен, имя и роль — но ходил только в ОДИН ящик настроек,
 * тот, где лежит токен. Ящиков на диске несколько (аудит 2026-08-08, волна 109), и в остальных
 * оставалось:
 *
 *  • «я вожу» — хотя список очистки обещал этот ключ стереть. Обещание не выполнялось просто
 *    потому, что ключ пишется в другой ящик;
 *  • номер брони и номера посылок, которые Ильдар вёз. Это и сами по себе чужие данные,
 *    и — хуже — по ним фоновый сервис умеет ВОСКРЕСНУТЬ после смерти процесса. То есть телефон
 *    в руках сына мог снова начать светить дорогу за отца;
 *  • фильтры поиска. Среди них «только женщины» — он рассказывает о прошлом владельце то,
 *    чего новому знать не нужно.
 *
 * Обратная сторона так же важна: выход не должен стирать настройки САМОГО ТЕЛЕФОНА. Сбросить
 * язык — значит встретить нового человека чужой речью; показать интро заново — мелкая, но
 * бессмысленная грубость.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoutLeavesNothingTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun box(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Ильдар пользовался приложением: выбрал роль, ехал, вёз посылки, настроил поиск и язык. */
    @Before
    fun человек_пожил_в_приложении() {
        ApiClient.init(app)
        box("yuldash_prefs").edit()
            .putString("preferred_role", "Driver")
            .putString("mode_last", "parcel")
            .putBoolean("onboarding_completed", true)
            .apply()
        box("trip_location_svc").edit().putInt("last_booking", 777).apply()
        box("courier_location_svc").edit().putString("last_parcels", "12,13").apply()
        box("yuldash_filters").edit().putStringSet("default_filters", setOf("women_only")).apply()
        box("yuldash_settings").edit().putString("yuldash_lang", "Ba").apply()
    }

    @Test
    fun `после выхода не остаётся, кем человек был в приложении`() {
        ApiClient.logout()

        assertNull(
            "«я вожу» пережило выход — сын видит отцовскую роль. Список очистки это обещал, " +
                "но ходил не в тот ящик",
            box("yuldash_prefs").getString("preferred_role", null),
        )
        assertNull(
            "последний режим (попутка / такси / посылка) пережил выход",
            box("yuldash_prefs").getString("mode_last", null),
        )
    }

    @Test
    fun `после выхода фоновой слежке нечего воскрешать`() {
        ApiClient.logout()

        assertEquals(
            "номер брони остался на диске: по нему сервис поездки воскресает и снова шлёт GPS",
            -1, box("trip_location_svc").getInt("last_booking", -1),
        )
        assertNull(
            "номера посылок остались на диске: телефон нового владельца продолжит светить " +
                "дорогу за прошлого",
            box("courier_location_svc").getString("last_parcels", null),
        )
    }

    @Test
    fun `после выхода не остаётся, кого человек искал`() {
        ApiClient.logout()

        assertNull(
            "фильтры поиска пережили выход; среди них «только женщины» — он рассказывает " +
                "о прошлом владельце телефона",
            box("yuldash_filters").getStringSet("default_filters", null),
        )
    }

    @Test
    fun `настройки самого телефона выход не трогает`() {
        ApiClient.logout()

        assertEquals(
            "язык сбросился — новый человек встретит приложение чужой речью",
            "Ba", box("yuldash_settings").getString("yuldash_lang", null),
        )
        assertTrue(
            "интро показали бы заново, хотя на этом телефоне его уже видели",
            box("yuldash_prefs").getBoolean("onboarding_completed", false),
        )
    }

    @Test
    fun `у выхода записан адрес каждого ящика`() {
        val addressed = com.yuldash.app.data.SessionKeys.CLEARED_BY_FILE
        assertTrue(
            "выход снова знает про один ящик настроек — а их несколько",
            addressed.size >= 4,
        )
        assertTrue(
            "ящик с фоновой доставкой выпал из списка очистки",
            addressed.keys.contains("courier_location_svc"),
        )
    }
}
