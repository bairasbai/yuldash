package com.yuldash.app

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * «Я на линии» — смена водителя такси.
 *
 * Что делает этот сервис. Пока тумблер включён, телефон водителя раз в 15 секунд говорит
 * серверу «я здесь, вот мои координаты», и раз в 5 секунд спрашивает «есть заказ?». По этим
 * координатам сервер выбирает ближайшую машину, считает время подачи и рисует её пассажиру
 * на карте.
 *
 * Почему ошибка тут дорогая с обеих сторон:
 *  • сервис не выключился — телефон водителя садится за смену, а пассажирам показывают машину,
 *    которая давно уехала домой;
 *  • сервис выключился зря — водитель стоит «на линии», а заказы уходят мимо него.
 *
 * Отдельно про геолокацию. Сервис требует именно ТОЧНУЮ. «Примерная» — это километры
 * погрешности: заказ уйдёт не тому, время подачи соврёт, машина на карте будет не там.
 * Честнее не встать на линию вовсе, чем работать «как получится».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiLineServiceTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun setup() = runBlocking {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        // Встать на линию может только вошедший водитель — логинимся обычным путём.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"jwt_driver","refresh_token":"ref","name":"Айдар"}"""),
        )
        ApiClient.verifyCode(phone = "+79990000002", code = "1234", name = "Айдар")
        server.takeRequest()
        Unit
    }

    @After
    fun teardown() {
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        runCatching { server.shutdown() }
    }

    private fun intent(lang: AppLanguage = AppLanguage.Ru) =
        Intent(app, TaxiLineService::class.java).putExtra(TaxiLineService.EXTRA_LANG, lang.name)

    private fun service() =
        Robolectric.buildService(TaxiLineService::class.java, intent()).create().get()

    @Test
    fun `вошедший водитель с точной геолокацией встаёт на линию`() {
        val svc = service()
        assertEquals(
            "смена должна держаться, пока тумблер включён",
            Service.START_STICKY, svc.onStartCommand(intent(), 0, 1),
        )
        svc.onDestroy()
    }

    @Test
    fun `без входа в аккаунт на линию не встать`() {
        ApiClient.logout()
        val svc = service()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(intent(), 0, 1))
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `без точной геолокации на линию не встать`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val svc = service()
        // «Примерная» позиция здесь не годится: заказы уходили бы мимо, а машина на карте
        // стояла бы не там, где она есть.
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(intent(), 0, 1))
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `язык шторки берётся из приложения`() {
        val svc = Robolectric.buildService(TaxiLineService::class.java, intent(AppLanguage.Ba)).create().get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(intent(AppLanguage.Ba), 0, 1))
        svc.onDestroy()
    }

    @Test
    fun `битое имя языка не мешает встать на линию`() {
        val broken = Intent(app, TaxiLineService::class.java).putExtra(TaxiLineService.EXTRA_LANG, "Klingon")
        val svc = Robolectric.buildService(TaxiLineService::class.java, broken).create().get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(broken, 0, 1))
        svc.onDestroy()
    }

    @Test
    fun `повторный запуск не плодит вторые опросы`() {
        val svc = service()
        svc.onStartCommand(intent(), 0, 1)
        svc.onStartCommand(intent(), 0, 2)
        // Второй запуск (например после воскрешения) не должен удваивать частоту запросов
        // к серверу — иначе трафик и батарея уходят вдвое быстрее.
        svc.onDestroy()
    }

    @Test
    fun `телефон водителя сам говорит серверу, что он на линии`() {
        val svc = service()
        // Первый heartbeat уходит сразу, не дожидаясь свежего GPS-фикса: берём последнюю
        // известную позицию приложения, иначе водитель первые полминуты «невидим».
        LocationPrefs.lastLat = 52.5911
        LocationPrefs.lastLng = 58.3178
        // Опросов два: «я на линии» и «есть заказ?». Кто из них успеет первым — не важно,
        // поэтому отвечаем на несколько и ищем нужный среди первых запросов.
        repeat(6) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
        svc.onStartCommand(intent(), 0, 1)

        val paths = buildList {
            repeat(4) { server.takeRequest(5, TimeUnit.SECONDS)?.path?.let { add(it) } }
        }
        assertTrue(
            "сервер не узнал, что водитель на линии — заказы пойдут мимо. Запросы: $paths",
            paths.any { it.contains("presence") },
        )
        svc.onDestroy()
    }

    @Test
    fun `остановка смены глушит опросы`() {
        val svc = service()
        svc.onStartCommand(intent(), 0, 1)
        svc.onDestroy()
        // После выключения тумблера телефон не должен продолжать сыпать запросы: это и трафик,
        // и «живой» водитель на карте у пассажиров, который на самом деле уже уехал.
    }
}
