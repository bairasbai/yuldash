package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину 2»: добираем НЕпокрытые ветки под-компонентов SOS / «Проверка водителя» из
 * SosVerifyScreens.kt. Здесь — только чистые (без сети/state/эффектов) под-компоненты, которые
 * берут готовые примитивы или зовут `appText` внутри. Все анимации тут конечные → тестируем целиком.
 *
 * НЕ дублируем существующие SosVerifyContentTest / SosVerifyDeepContentTest:
 *  - SosContent / VerifyDriverContent целиком уже покрыты в SosVerifyDeepContentTest;
 *  - StatusBanner (base RU/BA), DocumentRow (loaded=true), SubmitErrorBanner (RU/BA заголовок),
 *    UploadTile (RU-ветки), DriverReasonBanner (rejected-fallback / license_expired / needs_human)
 *    — в SosVerifyContentTest.
 *
 * Тут добираем: остальные коды причин DriverReasonBanner + блок «мы распознали» + заголовок
 * «Что улучшить в фото» + ранний return (баннера нет); башкирские ветки UploadTile / StatusBanner /
 * DocumentRow / SubmitErrorBanner; DocumentRow при loaded=false. Тексты сверены 1-в-1 с исходником.
 *
 * Высокое окно (w411dp-h2600dp) → под-компоненты помещаются целиком, скролл не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosVerifyDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Хелпер: показать любой Composable под нужным языком.
    private fun setLang(lang: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) { content() }
        }
    }

    // ============ DriverReasonBanner: остальные коды причин (RU) ============
    // Каждый код из backend/driver_check.py → своя понятная строка (ветки buildList в исходнике).

    @Test
    fun driverReason_notALicense_showsCantReadNumberLine() {
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["not_a_license"]}""",
            )
        }
        composeRule.onNodeWithText("Не разобрали номер прав на фото.").assertIsDisplayed()
    }

    @Test
    fun driverReason_noLicenseNumber_showsNotFoundNumberLine() {
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["no_license_number"]}""",
            )
        }
        composeRule.onNodeWithText("Не нашли номер водительского удостоверения.").assertIsDisplayed()
    }

    @Test
    fun driverReason_noExpiryDate_showsNoExpiryLine() {
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["no_expiry_date"]}""",
            )
        }
        composeRule.onNodeWithText("Не нашли срок действия на фото.").assertIsDisplayed()
    }

    @Test
    fun driverReason_docNotFound_showsReuploadLine() {
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["doc_not_found"]}""",
            )
        }
        composeRule.onNodeWithText("Фото прав не открылось. Загрузи его ещё раз.").assertIsDisplayed()
    }

    @Test
    fun driverReason_ocrUnavailable_showsHumanWillCheckLine() {
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["ocr_unavailable"]}""",
            )
        }
        composeRule.onNodeWithText("Авто-проверка временно недоступна — заявку посмотрит человек.").assertIsDisplayed()
    }

    // ============ DriverReasonBanner: блок «Мы распознали» (license_number + expiry) ============

    @Test
    fun driverReason_recognizedData_showsLicenseNumberAndExpiry() {
        // Есть распознанные № прав + срок → строка «Мы распознали: …» с обоими значениями.
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "rejected",
                autocheckResult = "reject",
                autocheckData = """{"reasons":["license_expired"],"license_number":"AB1234567","expiry":"2020-05-01"}""",
            )
        }
        composeRule.onNodeWithText(
            "Мы распознали: № прав: AB1234567   ·   срок до 2020-05-01",
        ).assertIsDisplayed()
    }

    // ============ DriverReasonBanner: заголовок «Что улучшить в фото» (reject без rejected-статуса) ============

    @Test
    fun driverReason_autocheckRejectWithoutRejectedStatus_showsImprovePhotoTitle() {
        // docsStatus != "rejected", но autocheckResult == "reject" → не rejected и не needsHuman →
        // заголовок else-ветки «Что улучшить в фото».
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(
                docsStatus = "pending",
                autocheckResult = "reject",
                autocheckData = "",
            )
        }
        composeRule.onNodeWithText("Что улучшить в фото").assertIsDisplayed()
    }

    // ============ DriverReasonBanner: ранний return — баннера нет ============

    @Test
    fun driverReason_okStatusNoAutocheck_rendersNothing() {
        // Не отклонено и авто-проверка без reject/needs_human → return, ни одной строки баннера.
        setLang(AppLanguage.Ru) {
            DriverReasonBanner(docsStatus = "pending", autocheckResult = "ok", autocheckData = "")
        }
        composeRule.onNodeWithText("Проверь данные и фото, затем отправь снова.").assertDoesNotExist()
        composeRule.onNodeWithText("Что улучшить в фото").assertDoesNotExist()
    }

    // ============ DriverReasonBanner: башкирский ============

    @Test
    fun driverReason_bashkir_showsWhyRejectedTitleAndFooter() {
        setLang(AppLanguage.Ba) {
            DriverReasonBanner(docsStatus = "rejected", autocheckResult = "", autocheckData = "")
        }
        composeRule.onNodeWithText("Ниңә кире ҡағылды").assertIsDisplayed()
        composeRule.onNodeWithText("Мәғлүмәт менән фотоны тикшереп, ҡабат ебәр.").assertIsDisplayed()
    }

    // ============ UploadTile: башкирские статусы (RU покрыт в SosVerifyContentTest) ============

    @Test
    fun uploadTile_bashkir_notDone_showsPrompt() {
        setLang(AppLanguage.Ba) {
            UploadTile(title = "Машина фотоһы", done = false, loading = false) {}
        }
        composeRule.onNodeWithText("Фото һайлау өсөн баҫығыҙ").assertIsDisplayed()
    }

    @Test
    fun uploadTile_bashkir_done_showsLoadedStatus() {
        setLang(AppLanguage.Ba) {
            UploadTile(title = "Машина фотоһы", done = true, loading = false) {}
        }
        composeRule.onNodeWithText("Йөкләнде").assertIsDisplayed()
    }

    // ============ StatusBanner: башкирский подзаголовок (RU/BA-заголовок покрыт) ============

    @Test
    fun statusBanner_bashkir_showsSubtitleToo() {
        // sub тоже приходит готовым → компонент рисует любой язык, проверяем именно подзаголовок.
        setLang(AppLanguage.Ba) {
            StatusBanner(
                icon = Icons.Default.Shield,
                title = "Тикшереү үтелмәгән",
                sub = "Машина мәғлүмәтен тултырып, фото йөкләгеҙ.",
                bg = CanonMint,
                fg = CanonGreen2,
            )
        }
        composeRule.onNodeWithText("Машина мәғлүмәтен тултырып, фото йөкләгеҙ.").assertIsDisplayed()
    }

    // ============ DocumentRow: ветка loaded=false + башкирский текст ============

    @Test
    fun documentRow_notLoaded_showsWarnStatus() {
        // loaded=false → статус тем же текстом, но цветом CanonWarn (ветка else в исходнике).
        setLang(AppLanguage.Ru) {
            DocumentRow(
                icon = Icons.Default.CreditCard,
                title = "Водительские права",
                status = "Не загружено",
                loaded = false,
            )
        }
        composeRule.onNodeWithText("Не загружено").assertIsDisplayed()
    }

    @Test
    fun documentRow_bashkir_showsTitleAndStatus() {
        setLang(AppLanguage.Ba) {
            DocumentRow(
                icon = Icons.Default.DirectionsCar,
                title = "Машина фотоһы",
                status = "Йөкләнде",
                loaded = true,
            )
        }
        composeRule.onNodeWithText("Машина фотоһы").assertIsDisplayed()
        composeRule.onNodeWithText("Йөкләнде").assertIsDisplayed()
    }

    // ============ SubmitErrorBanner: башкирское тело (RU целиком + BA-заголовок покрыты) ============

    @Test
    fun submitErrorBanner_bashkir_showsBodyText() {
        setLang(AppLanguage.Ba) {
            SubmitErrorBanner()
        }
        composeRule.onNodeWithText("Интернетты тикшереп, «Тикшереүгә ебәреү»гә тағы баҫ.").assertIsDisplayed()
    }
}
