package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Сетевой слой `ApiClient`: фотоконтроль машины (580-ФЗ) — `getCarPhoto`, `uploadCarPhoto`,
 * `submitCarPhoto` на локальном MockWebServer.
 *
 * Зачем. Разбор этого ответа не трогал ни один тест, а от него зависит, увидит ли водитель, что
 * через три дня его поставят на паузу, и какой кадр переснять. Ошибка в одном поле здесь — это
 * «всё в порядке» на экране у человека, которого завтра снимут с линии.
 *
 * Ответы сервера — в той форме, какую собирает `backend/app/carphoto.py::payload` (и
 * `routers/carphoto.py` для одного кадра): тексты кадров и правила чистоты взяты оттуда же.
 * Базовый URL подменяется тест-хуком `ApiClient.testBaseUrl` (в проде он null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientCarPhotoTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private val frontSlot = """{"code":"front","ru":"Спереди","ba":"Алдан",
        "hint_ru":"Весь перёд машины и номер","hint_ba":"Машинаның алды һәм номеры",
        "url":"/secure/carphoto/12-front.jpg","verdict":"ok"}"""
    private val salonSlot = """{"code":"salon","ru":"Салон","ba":"Салон",
        "hint_ru":"Задние сиденья и пол — там едет пассажир",
        "hint_ba":"Арттағы урындыҡтар һәм иҙән — юлаусы шунда бара","url":null,"verdict":""}"""

    @Test
    fun getCarPhoto_overdueCheck_parsesLadderSlotsDemandAndRules() = runBlocking {
        // Второй контроль просрочен на 5 дней → ступень «slow»; по жалобе открыто требование салона.
        server.enqueue(
            json(
                """{"mode":"taxi","enabled":true,"required":true,
                   "slots":[$frontSlot,$salonSlot],
                   "demand":{"id":7,"status":"waiting","due_at":"2026-09-27T10:00:00",
                             "hours_left":3,"overdue":false,"slots":[$salonSlot],"missing":["salon"]},
                   "clean_rules":[{"ru":"Нет мусора и личных вещей водителя",
                                   "ba":"Сүп һәм водителдең шәхси әйберҙәре юҡ"}],
                   "keep_days":60,"last_passed_at":"2026-06-01T09:30:00",
                   "id":12,"seq":2,"status":"waiting","due_at":"2026-09-21T00:00:00",
                   "days_left":-5,"stage":"slow","late_days":5,"winter":false,
                   "check_signs":false,"manual":false,"reject_reason":"","missing":["salon"]}"""
            )
        )
        val cp = ApiClient.getCarPhoto().getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/carphoto?mode=taxi", recorded.path)

        assertTrue(cp.enabled)
        assertTrue(cp.required)
        assertEquals("waiting", cp.status)
        assertEquals(2, cp.seq)
        assertEquals("2026-09-21T00:00:00", cp.dueAt)
        assertEquals(-5, cp.daysLeft)
        assertEquals("slow", cp.stage)
        assertEquals(5, cp.lateDays)
        assertFalse(cp.checkSigns)
        assertEquals(listOf("salon"), cp.missing)
        assertEquals("2026-06-01T09:30:00", cp.lastPassedAt)
        assertEquals("Срок хранения снимков — с сервера, а не умолчание", 60, cp.keepDays)

        assertEquals(listOf("front", "salon"), cp.slots.map { it.code })
        val front = cp.slots[0]
        assertEquals("Алдан", front.ba)
        assertEquals("Весь перёд машины и номер", front.hintRu)
        assertEquals("/secure/carphoto/12-front.jpg", front.url)
        assertEquals("ok", front.verdict)
        assertNull("JSON null — «кадра ещё нет», а не строка \"null\"", cp.slots[1].url)
        assertEquals("", cp.slots[1].verdict)

        val demand = assertNotNullDemand(cp)
        assertEquals(7, demand.id)
        assertEquals("waiting", demand.status)
        assertEquals("2026-09-27T10:00:00", demand.dueAt)
        assertEquals(3, demand.hoursLeft)
        assertFalse(demand.overdue)
        assertEquals(listOf("salon"), demand.slots.map { it.code })
        assertEquals("Арттағы урындыҡтар һәм иҙән — юлаусы шунда бара", demand.slots.single().hintBa)
        assertEquals(listOf("salon"), demand.missing)

        assertEquals(
            listOf(BiText("Нет мусора и личных вещей водителя", "Сүп һәм водителдең шәхси әйберҙәре юҡ")),
            cp.cleanRules,
        )
    }

    @Test
    fun getCarPhoto_controlOff_keepsSafeDefaults() = runBlocking {
        // Сервер с выключенным контролем отдаёт три поля. Экран не должен выдумать ни срока, ни паузы.
        server.enqueue(json("""{"mode":"courier","enabled":false,"required":false}"""))
        val cp = ApiClient.getCarPhoto("courier").getOrThrow()

        assertEquals("/carphoto?mode=courier", server.takeRequest().path)
        assertEquals("courier", cp.mode)
        assertFalse(cp.enabled)
        assertFalse(cp.required)
        assertEquals("ok", cp.stage)
        assertNull(cp.dueAt)
        assertNull(cp.lastPassedAt)
        assertNull(cp.demand)
        assertTrue(cp.slots.isEmpty())
        assertTrue(cp.cleanRules.isEmpty())
        assertEquals("Без keep_days — прежние 90 дней", 90, cp.keepDays)
    }

    @Test
    fun getCarPhoto_onlyOverdueDemand_blankValuesDoNotLeakAsText() = runBlocking {
        // Планового контроля нет, но требование по жалобе просрочено. Пустые строки и keep_days=0
        // с сервера не должны превратиться в «ссылку» и «0 дней хранения».
        server.enqueue(
            json(
                """{"mode":"taxi","enabled":true,"required":false,
                   "slots":[{"code":"salon","ru":"Салон","ba":"Салон","url":"","verdict":"too_dark"}],
                   "demand":{"id":9,"status":"waiting","due_at":null,"hours_left":0,"overdue":true,
                             "slots":[{"code":"salon","ru":"Салон","ba":"Салон","url":"  ","verdict":""}]},
                   "clean_rules":[],"keep_days":0,"last_passed_at":null}"""
            )
        )
        val cp = ApiClient.getCarPhoto().getOrThrow()

        assertFalse(cp.required)
        assertEquals("", cp.status)
        assertEquals(0, cp.seq)
        assertNull(cp.dueAt)
        assertEquals(90, cp.keepDays)
        assertNull("Пустая ссылка — не кадр", cp.slots.single().url)
        assertEquals("too_dark", cp.slots.single().verdict)

        val demand = assertNotNullDemand(cp)
        assertTrue(demand.overdue)
        assertEquals(0, demand.hoursLeft)
        assertNull(demand.dueAt)
        assertNull("Ссылка из пробелов — тоже не кадр", demand.slots.single().url)
        assertTrue("Нет missing в ответе — пустой список, не падение", demand.missing.isEmpty())
    }

    @Test
    fun uploadCarPhoto_sendsSlotAsMultipartAndReturnsWhatToRetake() = runBlocking {
        // Кадр не подошёл: человек стоит у машины, ответ должен сразу сказать, что переснять.
        server.enqueue(
            json(
                """{"url":"/secure/carphoto/12-salon.jpg","slot":"salon","ok":false,"reason":"blurry",
                   "message":{"ru":"Кадр размыт. Придержи телефон и сними ещё раз.",
                              "ba":"Кадр йәйелгән. Телефонды тотоп тор ҙа тағы төшөр."},
                   "missing":["salon","trunk"]}"""
            )
        )
        val shot = ApiClient.uploadCarPhoto("taxi", "salon", byteArrayOf(1, 2, 3)).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/carphoto/photo?mode=taxi&slot=salon&kind=periodic", recorded.path)
        assertTrue(recorded.getHeader("Content-Type").orEmpty().contains("multipart/form-data"))
        assertEquals("salon", shot.slot)
        assertFalse(shot.ok)
        assertEquals("blurry", shot.reason)
        assertEquals("/secure/carphoto/12-salon.jpg", shot.url)
        assertEquals(listOf("salon", "trunk"), shot.missing)
    }

    @Test
    fun submitCarPhoto_postsModeAndKindAndReturnsReviewState() = runBlocking {
        // Набор по жалобе ушёл человеку на просмотр: экран показывает «смотрят», а не «принято».
        server.enqueue(
            json(
                """{"mode":"taxi","enabled":true,"required":true,"slots":[$frontSlot],
                   "demand":null,"clean_rules":[],"keep_days":90,"last_passed_at":null,
                   "id":12,"seq":1,"status":"review","due_at":"2026-09-30T00:00:00","days_left":4,
                   "stage":"ok","late_days":0,"winter":true,"check_signs":true,"manual":true,
                   "reject_reason":"","missing":[]}"""
            )
        )
        val cp = ApiClient.submitCarPhoto(kind = "complaint").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/carphoto/submit", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("taxi", body.getString("mode"))
        assertEquals("complaint", body.getString("kind"))

        assertEquals("review", cp.status)
        assertTrue(cp.manual)
        assertTrue("Первый контроль такси — фонарь и «шашечки»", cp.checkSigns)
        assertTrue("Зимой чистый кузов не требуем", cp.winter)
        assertEquals(4, cp.daysLeft)
        assertNull(cp.demand)
        assertTrue(cp.missing.isEmpty())
    }

    private fun assertNotNullDemand(cp: CarPhotoDto): CarPhotoDemandDto {
        assertNotNull("Открытое требование по жалобе должно дойти до экрана", cp.demand)
        return cp.demand!!
    }
}
