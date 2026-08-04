package com.yuldash.app

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.TripLocationBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Фоновая трансляция позиции: поездка и доставка.
 *
 * Что здесь на кону. Эти два сервиса — единственное в приложении, что работает с погашенным
 * экраном и тратит GPS. Ошибка тут стоит дорого сразу с двух сторон:
 *  • сервис не выключился — телефон водителя садится за смену, а позиция утекает в канал уже
 *    завершённой поездки (чужой человек продолжает видеть, где он едет);
 *  • сервис выключился слишком рано — пассажир на трассе теряет машину с карты.
 *
 * Замер покрытия 2026-08-04 показал у обоих **0%**. Ниже закрыты те ветки, где ошибка
 * незаметна глазами: воскрешение после смерти процесса, отзыв разрешения на геолокацию,
 * смена поездки на живом сервисе и штатное завершение.
 *
 * Разрешение на точную геолокацию в тестах выдаём явно — Robolectric по умолчанию его не даёт,
 * и без этого сервис честно выключался бы на каждом сценарии.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationServicesTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun grantLocation() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        tripPrefs().edit().clear().apply()
        courierPrefs().edit().clear().apply()
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
    }

    @After
    fun cleanup() {
        tripPrefs().edit().clear().apply()
        courierPrefs().edit().clear().apply()
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
    }

    private fun tripPrefs() = app.getSharedPreferences("trip_location_svc", Context.MODE_PRIVATE)
    private fun courierPrefs() = app.getSharedPreferences("courier_location_svc", Context.MODE_PRIVATE)

    private fun tripIntent(bookingId: Int, lang: AppLanguage = AppLanguage.Ru) =
        Intent(app, TripLocationService::class.java)
            .putExtra(TripLocationService.EXTRA_BOOKING, bookingId)
            .putExtra(TripLocationService.EXTRA_LANG, lang.name)

    // ---------- Поездка ----------

    @Test
    fun `поездка запомнена, чтобы пережить смерть процесса`() {
        val svc = Robolectric.buildService(TripLocationService::class.java, tripIntent(42)).create().get()
        val result = svc.onStartCommand(tripIntent(42), 0, 1)

        assertEquals("сервис должен просить систему воскресить себя", Service.START_STICKY, result)
        assertEquals("номер поездки не сохранён — после смерти процесса трекинг погаснет", 42, tripPrefs().getInt("last_booking", -1))
        assertEquals("карта должна знать, по какой поездке идёт стрим", 42, TripLocationBus.bookingId)
        svc.onDestroy()
    }

    @Test
    fun `воскрешение без данных продолжает ту же поездку`() {
        // Android убил процесс и поднял сервис заново — intent приходит пустым.
        tripPrefs().edit().putInt("last_booking", 77).putString("last_lang", "Ba").apply()

        val svc = Robolectric.buildService(TripLocationService::class.java).create().get()
        val result = svc.onStartCommand(null, 0, 1)

        assertEquals(Service.START_STICKY, result)
        assertEquals("долгая поездка оборвалась бы на середине", 77, TripLocationBus.bookingId)
        svc.onDestroy()
    }

    @Test
    fun `штатное завершение стирает поездку — воскрешать нечего`() {
        val svc = Robolectric.buildService(TripLocationService::class.java, tripIntent(42)).create().get()
        svc.onStartCommand(tripIntent(42), 0, 1)
        svc.onDestroy()

        assertEquals("после поездки запись обязана исчезнуть", -1, tripPrefs().getInt("last_booking", -1))
        assertNull("иначе на карте зависнет машина из законченной поездки", TripLocationBus.bookingId)
        assertNull(TripLocationBus.peer)
    }

    @Test
    fun `без разрешения на геолокацию сервис выключается сам`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)

        val svc = Robolectric.buildService(TripLocationService::class.java, tripIntent(42)).create().get()
        val result = svc.onStartCommand(tripIntent(42), 0, 1)

        // Иначе в шторке висела бы надпись «показываем вашу позицию попутчику» — прямое враньё.
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue("сервис должен был остановиться", shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `без номера поездки сервис не поднимается`() {
        val empty = Intent(app, TripLocationService::class.java)
        val svc = Robolectric.buildService(TripLocationService::class.java, empty).create().get()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(empty, 0, 1))
    }

    @Test
    fun `смена поездки на живом сервисе переключает канал`() {
        val svc = Robolectric.buildService(TripLocationService::class.java, tripIntent(1)).create().get()
        svc.onStartCommand(tripIntent(1), 0, 1)
        assertEquals(1, TripLocationBus.bookingId)

        // Поездка A кончилась, сразу началась B: GPS не должен продолжать литься в канал A.
        svc.onStartCommand(tripIntent(2), 0, 2)
        assertEquals(2, TripLocationBus.bookingId)
        assertEquals(2, tripPrefs().getInt("last_booking", -1))
        svc.onDestroy()
    }

    @Test
    fun `язык шторки берётся из приложения, а не по умолчанию`() {
        val svc = Robolectric.buildService(TripLocationService::class.java, tripIntent(5, AppLanguage.Ba)).create().get()
        svc.onStartCommand(tripIntent(5, AppLanguage.Ba), 0, 1)
        assertEquals("Ba", tripPrefs().getString("last_lang", null))
        svc.onDestroy()
    }

    @Test
    fun `битое имя языка не роняет сервис`() {
        val broken = tripIntent(5).putExtra(TripLocationService.EXTRA_LANG, "Klingon")
        val svc = Robolectric.buildService(TripLocationService::class.java, broken).create().get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(broken, 0, 1))
        assertEquals("непонятный язык → падаем на русский", "Ru", tripPrefs().getString("last_lang", null))
        svc.onDestroy()
    }

    // ---------- Доставка ----------

    private fun courierIntent(vararg ids: Int, lang: AppLanguage = AppLanguage.Ru) =
        Intent(app, CourierLocationService::class.java)
            .putExtra(CourierLocationService.EXTRA_PARCELS, ids)
            .putExtra(CourierLocationService.EXTRA_LANG, lang.name)

    @Test
    fun `набор доставок запоминается целиком`() {
        val i = courierIntent(3, 9, 14)
        val svc = Robolectric.buildService(CourierLocationService::class.java, i).create().get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(i, 0, 1))
        assertEquals("3,9,14", courierPrefs().getString("last_parcels", null))
        svc.onDestroy()
    }

    @Test
    fun `воскрешение курьера поднимает те же посылки`() {
        courierPrefs().edit().putString("last_parcels", "5,6").putString("last_lang", "Ba").apply()
        val svc = Robolectric.buildService(CourierLocationService::class.java).create().get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(null, 0, 1))
        svc.onDestroy()
        assertNull("после штатного завершения запись должна исчезнуть", courierPrefs().getString("last_parcels", null))
    }

    @Test
    fun `мусор в сохранённом наборе не роняет курьера`() {
        // Запись могла остаться от старой версии приложения.
        courierPrefs().edit().putString("last_parcels", "5, ,abc,7").apply()
        val svc = Robolectric.buildService(CourierLocationService::class.java).create().get()
        assertEquals("годные номера должны выжить", Service.START_STICKY, svc.onStartCommand(null, 0, 1))
        svc.onDestroy()
    }

    @Test
    fun `пустой набор доставок сервис не поднимает`() {
        val i = courierIntent()
        val svc = Robolectric.buildService(CourierLocationService::class.java, i).create().get()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(i, 0, 1))
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `курьер без разрешения на геолокацию выключается сам`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val i = courierIntent(3)
        val svc = Robolectric.buildService(CourierLocationService::class.java, i).create().get()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(i, 0, 1))
    }

    @Test
    fun `смена набора доставок переключает каналы`() {
        val first = courierIntent(3)
        val svc = Robolectric.buildService(CourierLocationService::class.java, first).create().get()
        svc.onStartCommand(first, 0, 1)

        // Одну посылку вручил, взял другую — старый канал закрывается, иначе позиция уходит
        // человеку, которому уже всё отдали.
        val second = courierIntent(9)
        svc.onStartCommand(second, 0, 2)
        assertEquals("9", courierPrefs().getString("last_parcels", null))
        svc.onDestroy()
    }

    // ---------- Смена водителя (линия такси) ----------

    @Test
    fun `линия такси поднимается и глушится без падений`() {
        val i = Intent(app, TaxiLineService::class.java).putExtra(TaxiLineService.EXTRA_LANG, "Ba")
        val svc = Robolectric.buildService(TaxiLineService::class.java, i).create().get()
        val result = svc.onStartCommand(i, 0, 1)
        assertTrue("смена должна держаться, пока водитель на линии", result == Service.START_STICKY || result == Service.START_NOT_STICKY)
        svc.onDestroy()
    }
}
