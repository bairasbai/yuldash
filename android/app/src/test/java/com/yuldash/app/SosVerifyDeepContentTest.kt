package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: продолжение выноса стейта из SosVerifyScreens.kt. Два умных экрана —
 * `SosScreen` (экстренный вызов + сигнал «своим») и `VerifyDriverScreen` (проверка водителя) —
 * разрезаны на умную обёртку (сеть/GPS/пикеры/эффекты) и чистый Content (`SosContent`,
 * `VerifyDriverContent`) на примитивах + колбэках. Здесь Content покрыт во всех «плохих»/крайних
 * состояниях: SOS активен/неактивен, есть/нет координат, статусы верификации (none/pending/
 * verified/rejected), клики → колбэки, disabled-кнопка. Раньше это было 0%.
 *
 * Бесконечных (пульсирующих) анимаций в этих Content нет → тестируем целиком, без autoAdvance=false.
 * Тексты сверены 1-в-1 с SosVerifyScreens.kt. Существующий SosVerifyContentTest покрывает мелкие
 * под-компоненты (StatusBanner / DocumentRow / SubmitErrorBanner / UploadTile / DriverReasonBanner) —
 * здесь их не дублируем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: экран целиком, скролл-вызовы = no-op
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosVerifyDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Те же 3 службы, что зашиты в SosScreen (Россия): 102 · 101 · 103.
    private fun services() = listOf(
        SosService("police", "102", LocalizedText("Полиция", "Полиция"), Icons.Default.LocalPolice, "other"),
        SosService("fire", "101", LocalizedText("Пожарные", "Янғын"), Icons.Default.LocalFireDepartment, "breakdown"),
        SosService("ambulance", "103", LocalizedText("Скорая", "Тиҙ ярҙам"), Icons.Default.LocalHospital, "medical"),
    )

    // Хелпер: показать SosContent с настраиваемыми состояниями; по умолчанию — «спокойное» состояние.
    private fun setSos(
        lang: AppLanguage = AppLanguage.Ru,
        description: String = "",
        coordsText: String? = null,
        locating: Boolean = false,
        loggedIn: Boolean = true,
        sent: Boolean = false,
        failed: Boolean = false,
        rateLimited: Boolean = false,
        sending: Boolean = false,
        onDial: (String) -> Unit = {},
        onLocate: () -> Unit = {},
        onCopy: () -> Unit = {},
        onSendSignal: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                SosContent(
                    services = services(),
                    description = description,
                    onDescriptionChange = {},
                    coordsText = coordsText,
                    locating = locating,
                    loggedIn = loggedIn,
                    sent = sent,
                    failed = failed,
                    rateLimited = rateLimited,
                    sending = sending,
                    onDial = onDial,
                    onLocate = onLocate,
                    onCopy = onCopy,
                    onSendSignal = onSendSignal,
                    onBack = onBack,
                )
            }
        }
    }

    // Скролл к тексту внутри единственного скроллера (LazyColumn) — для элементов ниже сгиба.
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    // ---------------- SosContent: заголовок и главная кнопка 112 ----------------

    @Test
    fun sos_russian_showsHeaderAndCall112() {
        setSos(lang = AppLanguage.Ru)
        composeRule.onNodeWithText("Срочный вызов").assertIsDisplayed()
        composeRule.onNodeWithText("Позвонить 112").assertIsDisplayed()
    }

    @Test
    fun sos_bashkir_showsHeaderAndCall112() {
        setSos(lang = AppLanguage.Ba)
        composeRule.onNodeWithText("Ашығыс саҡырыу").assertIsDisplayed()
        composeRule.onNodeWithText("112 — шылтыратыу").assertIsDisplayed()
    }

    @Test
    fun sos_call112Click_firesOnDialWith112() {
        var dialed: String? = null
        setSos(onDial = { dialed = it })
        composeRule.onNodeWithText("Позвонить 112").performClick()
        assertEquals("112", dialed)
    }

    // ---------------- SosContent: прямые вызовы служб (SosDirectCallChip внутри) ----------------

    @Test
    fun sos_showsAllThreeDirectServices() {
        setSos()
        composeRule.onNodeWithText("Полиция").assertIsDisplayed()
        composeRule.onNodeWithText("Пожарные").assertIsDisplayed()
        composeRule.onNodeWithText("Скорая").assertIsDisplayed()
    }

    @Test
    fun sos_directServiceClick_firesOnDialWithItsNumber() {
        var dialed: String? = null
        setSos(onDial = { dialed = it })
        // Тап по «Полиция» → её номер 102.
        composeRule.onNodeWithText("Полиция").performClick()
        assertEquals("102", dialed)
    }

    // ---------------- SosContent: блок координат (есть/нет гео, locating) ----------------

    @Test
    fun sos_noCoords_showsGeoOffHintAndEnableButton() {
        // coordsText == null → подсказка «геолокация выключена» + кнопка «Включить гео».
        setSos(coordsText = null)
        scrollTo("Включить гео")
        composeRule.onNodeWithText("Геолокация выключена — включи, чтобы продиктовать координаты.").assertIsDisplayed()
        composeRule.onNodeWithText("Включить гео").assertIsDisplayed()
    }

    @Test
    fun sos_withCoords_showsCoordsAndRefreshButton() {
        setSos(coordsText = "54.73510, 55.95840")
        scrollTo("Обновить")
        composeRule.onNodeWithText("Координаты: 54.73510, 55.95840").assertIsDisplayed()
        composeRule.onNodeWithText("Обновить").assertIsDisplayed()
    }

    @Test
    fun sos_locating_showsUpdatingLabel() {
        setSos(coordsText = "54.7, 55.9", locating = true)
        scrollTo("Обновляю…")
        composeRule.onNodeWithText("Обновляю…").assertIsDisplayed()
    }

    @Test
    fun sos_locateButtonClick_firesOnLocate() {
        var located = false
        setSos(coordsText = null, onLocate = { located = true })
        scrollTo("Включить гео")
        composeRule.onNodeWithText("Включить гео").performClick()
        assertTrue(located)
    }

    @Test
    fun sos_withDescription_showsCopyButtonAndFiresOnCopy() {
        // Кнопка «Скопировать» появляется только если есть описание или координаты.
        var copied = false
        setSos(description = "ДТП на трассе", onCopy = { copied = true })
        scrollTo("Скопировать")
        composeRule.onNodeWithText("Скопировать").performClick()
        assertTrue(copied)
    }

    @Test
    fun sos_noDescriptionNoCoords_hidesCopyButton() {
        setSos(description = "", coordsText = null)
        // Нет ни описания, ни координат → кнопки копирования нет.
        composeRule.onNodeWithText("Скопировать").assertDoesNotExist()
    }

    // ---------------- SosContent: залогинен / нет ----------------

    @Test
    fun sos_loggedIn_showsSignalDescription() {
        setSos(loggedIn = true)
        // Текст сменился 2026-08-06: теперь близким уходит ещё и машина, и экран об этом
        // честно предупреждает — человек в панике не должен гадать, что именно ушло.
        val expected = "SMS твоим доверенным контактам с местом и машиной, в которой ты едешь, + сигнал поддержке Юлдаш."
        scrollTo(expected)
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun sos_notLoggedIn_showsLoginRequiredHint() {
        setSos(loggedIn = false)
        scrollTo("Для SMS близким и сигнала поддержке нужно войти. Звонок 112 работает без входа.")
        composeRule.onNodeWithText("Для SMS близким и сигнала поддержке нужно войти. Звонок 112 работает без входа.").assertIsDisplayed()
    }

    // ---------------- SosContent: состояния отправки сигнала (sent / failed / rateLimited) ----------------

    @Test
    fun sos_sent_showsSentInfoCard() {
        setSos(sent = true)
        // Текст карточки переписан: было «Уведомление отправлено», стало «Сигнал отправлен»
        // (SosVerifyScreens.kt). Тест ждал старую формулировку — отсюда и провал прокрутки:
        // performScrollToNode искал узел, которого в дереве нет вообще.
        scrollTo("Сигнал отправлен")
        composeRule.onNodeWithText("Сигнал отправлен").assertIsDisplayed()
    }

    @Test
    fun sos_failedNetwork_showsNotSentCard() {
        setSos(failed = true, rateLimited = false)
        scrollTo("Сигнал не отправлен")
        composeRule.onNodeWithText("Сигнал не отправлен").assertIsDisplayed()
        composeRule.onNodeWithText("Похоже, нет сети. Проверь связь и нажми ещё раз.").assertIsDisplayed()
    }

    @Test
    fun sos_failedRateLimited_showsTooOftenCard() {
        // failed + rateLimited (429) → честный текст «слишком часто», не «нет сети».
        setSos(failed = true, rateLimited = true)
        scrollTo("Слишком часто")
        composeRule.onNodeWithText("Слишком часто").assertIsDisplayed()
        composeRule.onNodeWithText("Сигнал уже отправлялся недавно. Подожди немного и нажми ещё раз.").assertIsDisplayed()
    }

    // Прим.: при sending=true AppButton рисует ТОЛЬКО спиннер (бесконечная анимация) без текста —
    // проверять надпись бессмысленно и опасно (зависание waitForIdle). Состояние «отправляем»
    // покрыто на стороне UiKit-тестов AppButton, здесь не дублируем.

    @Test
    fun sos_signalButtonClick_firesOnSendSignal() {
        var sent = false
        setSos(onSendSignal = { sent = true })
        scrollTo("Сообщить близким и поддержке")
        // Кнопка сигнала (внизу) — жмём именно action-кнопку AppButton.
        composeRule.onAllNodesWithText("Сообщить близким и поддержке").onLast().performClick()
        assertTrue(sent)
    }

    @Test
    fun sos_backClick_firesOnBack() {
        var backed = false
        setSos(onBack = { backed = true })
        scrollTo("Назад")
        composeRule.onNodeWithText("Назад").performClick()
        assertTrue(backed)
    }

    // ================= VerifyDriverContent =================

    // Хелпер для VerifyDriverContent; по умолчанию — новый водитель, ничего не заполнено.
    private fun setVerify(
        lang: AppLanguage = AppLanguage.Ru,
        make: String = "",
        model: String = "",
        carColor: String = "",
        plate: String = "",
        seats: String = "4",
        licenseUrl: String? = null,
        carPhotoUrl: String? = null,
        uploadingLicense: Boolean = false,
        uploadingCar: Boolean = false,
        docsStatus: String = "none",
        verified: Boolean = false,
        submitting: Boolean = false,
        submitError: Boolean = false,
        autocheckResult: String = "",
        autocheckData: String = "",
        canSubmit: Boolean = false,
        onMakeChange: (String) -> Unit = {},
        onPickLicense: () -> Unit = {},
        onPickCar: () -> Unit = {},
        onSubmit: () -> Unit = {},
        onSelectTab: (HomeTab) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                VerifyDriverContent(
                    make = make, onMakeChange = onMakeChange,
                    model = model, onModelChange = {},
                    carColor = carColor, onColorChange = {},
                    plate = plate, onPlateChange = {},
                    seats = seats, onSeatsChange = {},
                    licenseUrl = licenseUrl,
                    carPhotoUrl = carPhotoUrl,
                    uploadingLicense = uploadingLicense,
                    uploadingCar = uploadingCar,
                    docsStatus = docsStatus,
                    verified = verified,
                    submitting = submitting,
                    submitError = submitError,
                    autocheckResult = autocheckResult,
                    autocheckData = autocheckData,
                    canSubmit = canSubmit,
                    onPickLicense = onPickLicense,
                    onPickCar = onPickCar,
                    onSubmit = onSubmit,
                    onSelectTab = onSelectTab,
                )
            }
        }
    }

    // ---------------- VerifyDriverContent: заголовок + статус-баннеры (4 ветки) ----------------

    @Test
    fun verify_russian_showsTitle() {
        setVerify(lang = AppLanguage.Ru)
        composeRule.onNodeWithText("Проверка водителя").assertIsDisplayed()
    }

    @Test
    fun verify_bashkir_showsTitle() {
        setVerify(lang = AppLanguage.Ba)
        composeRule.onNodeWithText("Водителде тикшереү").assertIsDisplayed()
    }

    @Test
    fun verify_none_showsNotVerifiedBanner() {
        setVerify(docsStatus = "none", verified = false)
        composeRule.onNodeWithText("Проверка не пройдена").assertIsDisplayed()
    }

    @Test
    fun verify_pending_showsUnderReviewBanner() {
        setVerify(docsStatus = "pending")
        composeRule.onNodeWithText("На проверке").assertIsDisplayed()
    }

    @Test
    fun verify_rejected_showsRejectedBanner() {
        setVerify(docsStatus = "rejected")
        composeRule.onNodeWithText("Отклонено").assertIsDisplayed()
    }

    @Test
    fun verify_verified_showsApprovedBanner() {
        // verified=true перекрывает docsStatus.
        setVerify(verified = true, docsStatus = "pending")
        composeRule.onNodeWithText("Профиль подтверждён").assertIsDisplayed()
    }

    // ---------------- VerifyDriverContent: форма и её колбэки ----------------

    @Test
    fun verify_showsCarDataFields() {
        setVerify()
        composeRule.onNodeWithText("Данные автомобиля").assertIsDisplayed()
        composeRule.onNodeWithText("Марка").assertIsDisplayed()
        composeRule.onNodeWithText("Модель").assertIsDisplayed()
    }

    @Test
    fun verify_makeField_showsPrefilledValue() {
        // Значение приходит примитивом (обёртка заполнила из getDriverStatus) — Content его рисует.
        setVerify(make = "Lada")
        composeRule.onNodeWithText("Lada").assertIsDisplayed()
    }

    // ---------------- VerifyDriverContent: загрузка фото → колбэки пикеров ----------------

    @Test
    fun verify_uploadTilesVisibleAndPickLicenseFires() {
        var pickedLicense = false
        setVerify(onPickLicense = { pickedLicense = true })
        scrollTo("Фото водительских прав")
        composeRule.onNodeWithText("Фото водительских прав").performClick()
        assertTrue(pickedLicense)
    }

    @Test
    fun verify_pickCarFires() {
        var pickedCar = false
        setVerify(onPickCar = { pickedCar = true })
        scrollTo("Фото автомобиля")
        composeRule.onNodeWithText("Фото автомобиля").performClick()
        assertTrue(pickedCar)
    }

    // ---------------- VerifyDriverContent: кнопка отправки (disabled / enabled / submitting) ----------------

    @Test
    fun verify_cannotSubmit_buttonDisabledAndNoCallback() {
        // canSubmit=false (нет фото) → кнопка неактивна, клик не вызывает onSubmit.
        var submitted = false
        setVerify(canSubmit = false, onSubmit = { submitted = true })
        scrollTo("Отправить на проверку")
        composeRule.onNodeWithText("Отправить на проверку").assertIsNotEnabled()
        composeRule.onNodeWithText("Отправить на проверку").performClick()
        assertFalse(submitted)
    }

    @Test
    fun verify_canSubmit_buttonEnabledAndFiresOnSubmit() {
        var submitted = false
        setVerify(
            licenseUrl = "https://x/l.jpg",
            carPhotoUrl = "https://x/c.jpg",
            canSubmit = true,
            onSubmit = { submitted = true },
        )
        scrollTo("Отправить на проверку")
        composeRule.onNodeWithText("Отправить на проверку").assertIsEnabled()
        composeRule.onNodeWithText("Отправить на проверку").performClick()
        assertTrue(submitted)
    }

    @Test
    fun verify_submitting_showsSendingLabel() {
        setVerify(submitting = true, canSubmit = false)
        scrollTo("Отправка…")
        composeRule.onNodeWithText("Отправка…").assertIsDisplayed()
    }

    // ---------------- VerifyDriverContent: инлайн-ошибка отправки ----------------

    @Test
    fun verify_submitError_showsErrorBanner() {
        setVerify(submitError = true)
        composeRule.onNodeWithText("Не отправилось").assertIsDisplayed()
    }
}
