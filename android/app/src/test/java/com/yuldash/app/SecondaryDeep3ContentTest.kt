package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yuldash.app.data.BlockDto
import com.yuldash.app.data.ReportableUserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину», раунд 3: продолжение выноса стейта во вторичных экранах.
 * - `BlocklistScreen` → чистый [BlocklistContent] (чёрный список + секция «Ваши попутчики»).
 * - `ReportScreen`    → чистый [ReportListContent] (список «на кого пожаловаться»; диалог — в обёртке).
 * - [AutoCheckRow]    — бейдж авто-проверки прав (OCR): вердикты + распознанные данные (стал internal).
 * - [AdminCabinetScreen] — статичный список навигации без сети/стейта → рендер и клики напрямую.
 *
 * Все Content-функции берут state/callbacks параметрами → без сети/эффектов → JVM (Robolectric).
 * Анимаций нет (кроме статичных экранов) → autoAdvance держим false только для кадра «загрузка».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ================= BlocklistContent =================

    private fun block(id: Int = 1, name: String = "Азат") = BlockDto(blockedUserId = id, name = name)
    private fun partner(id: Int = 10, name: String = "Гульназ") = ReportableUserDto(id = id, name = name)

    @Test
    fun blocklist_loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // держим кадр загрузки
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = true, error = null, blocks = listOf(block()), addable = emptyList(),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Азат").assertDoesNotExist()
    }

    @Test
    fun blocklist_error_showsMessageAndRetry() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = "Сеть упала", blocks = emptyList(), addable = emptyList(),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Сеть упала").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun blocklist_error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = "Ошибка", blocks = emptyList(), addable = emptyList(),
                    onRetry = { retried = true }, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun blocklist_empty_russian_showsPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = null, blocks = emptyList(), addable = emptyList(),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Чёрный список пуст").assertIsDisplayed()
    }

    @Test
    fun blocklist_empty_bashkir_showsPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BlocklistContent(
                    loading = false, error = null, blocks = emptyList(), addable = emptyList(),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Ҡара исемлек буш").assertIsDisplayed()
    }

    @Test
    fun blocklist_withBlocks_showsUnblockAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = null, blocks = listOf(block(name = "Азат")), addable = emptyList(),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Азат").assertIsDisplayed()
        composeRule.onNodeWithText("Разблокировать").assertIsDisplayed()
        // Секции «Ваши попутчики» нет, когда addable пуст.
        composeRule.onNodeWithText("Ваши попутчики").assertDoesNotExist()
    }

    @Test
    fun blocklist_unblockClick_firesWithBlockedUserId() {
        var unblocked = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = null, blocks = listOf(block(id = 55)), addable = emptyList(),
                    onRetry = {}, onUnblock = { unblocked = it }, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Разблокировать").performClick()
        assertEquals(55, unblocked)
    }

    @Test
    fun blocklist_addableSection_showsPartnersAndBlockAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = null, blocks = emptyList(), addable = listOf(partner(name = "Гульназ")),
                    onRetry = {}, onUnblock = {}, onBlock = {},
                )
            }
        }
        composeRule.onNodeWithText("Ваши попутчики").assertIsDisplayed()
        composeRule.onNodeWithText("Гульназ").assertIsDisplayed()
        composeRule.onNodeWithText("Заблокировать").assertIsDisplayed()
    }

    @Test
    fun blocklist_blockClick_firesWithPartnerId() {
        var blockedId = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BlocklistContent(
                    loading = false, error = null, blocks = emptyList(), addable = listOf(partner(id = 88)),
                    onRetry = {}, onUnblock = {}, onBlock = { blockedId = it },
                )
            }
        }
        composeRule.onNodeWithText("Заблокировать").performClick()
        assertEquals(88, blockedId)
    }

    // ================= ReportListContent =================

    @Test
    fun report_loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = true, error = null, partners = listOf(partner(name = "Динар")),
                    onRetry = {}, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Динар").assertDoesNotExist()
    }

    @Test
    fun report_error_showsMessageAndRetry() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = false, error = "Нет сети", partners = emptyList(),
                    onRetry = {}, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Нет сети").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun report_error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = false, error = "Ошибка", partners = emptyList(),
                    onRetry = { retried = true }, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun report_empty_russian_showsPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = false, error = null, partners = emptyList(),
                    onRetry = {}, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Пока не на кого жаловаться").assertIsDisplayed()
        composeRule.onNodeWithText("Здесь появятся попутчики после поездок.").assertIsDisplayed()
    }

    @Test
    fun report_empty_bashkir_showsPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ReportListContent(
                    loading = false, error = null, partners = emptyList(),
                    onRetry = {}, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Әлегә ялыу итергә кеше юҡ").assertIsDisplayed()
    }

    @Test
    fun report_list_showsPartnerAndComplainAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = false, error = null, partners = listOf(partner(name = "Динар")),
                    onRetry = {}, onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Динар").assertIsDisplayed()
        composeRule.onNodeWithText("Пожаловаться").assertIsDisplayed()
    }

    @Test
    fun report_complainClick_firesOnSelectWithUser() {
        var selected: ReportableUserDto? = null
        val p = partner(id = 99, name = "Динар")
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ReportListContent(
                    loading = false, error = null, partners = listOf(p),
                    onRetry = {}, onSelect = { selected = it },
                )
            }
        }
        composeRule.onNodeWithText("Пожаловаться").performClick()
        assertEquals(99, selected?.id)
    }

    // ================= AutoCheckRow (OCR-бейдж) =================

    @Test
    fun autoCheck_blankResult_rendersNothing() {
        // result="" → компонент сразу return, ни один вердикт не рисуется.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(result = "", dataJson = "")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: похоже на действительные права").assertDoesNotExist()
        composeRule.onNodeWithText("🤖 Авто: фото не распознано").assertDoesNotExist()
    }

    @Test
    fun autoCheck_pass_russian_showsPositiveVerdict() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(result = "pass", dataJson = "")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: похоже на действительные права").assertIsDisplayed()
    }

    @Test
    fun autoCheck_pass_bashkir_showsPositiveVerdict() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AutoCheckRow(result = "pass", dataJson = "")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: ысын права кеүек").assertIsDisplayed()
    }

    @Test
    fun autoCheck_reject_showsNegativeVerdict() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(result = "reject", dataJson = "")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: фото не распознано").assertIsDisplayed()
    }

    @Test
    fun autoCheck_error_showsUnavailableVerdict() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(result = "error", dataJson = "")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: проверка недоступна").assertIsDisplayed()
    }

    @Test
    fun autoCheck_pass_withRecognizedData_showsNumberAndExpiry() {
        // dataJson с номером и сроком → строка «№ прав 1234  ·  срок до 2030» (двойные пробелы вокруг ·).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(
                    result = "pass",
                    dataJson = """{"license_number":"1234","expiry":"2030"}""",
                )
            }
        }
        composeRule.onNodeWithText("№ прав 1234  ·  срок до 2030").assertIsDisplayed()
    }

    @Test
    fun autoCheck_malformedJson_stillShowsVerdictWithoutCrash() {
        // Кривой JSON ловится try/catch → пустой JSONObject → вердикт есть, строки данных нет.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AutoCheckRow(result = "pass", dataJson = "{not-json")
            }
        }
        composeRule.onNodeWithText("🤖 Авто: похоже на действительные права").assertIsDisplayed()
    }

    // ================= AdminCabinetScreen (статичная навигация) =================

    @Test
    fun adminCabinet_russian_showsSectionRows() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminCabinetScreen(
                    onBack = {}, onAdminRequest = {}, onAdminResponses = {},
                    onAds = {}, onDrivers = {}, onReports = {}, onPaymentRequests = {},
                )
            }
        }
        composeRule.onNodeWithText("Кабинет админа").assertIsDisplayed()   // заголовок TopBar
        composeRule.onNodeWithText("Заявка за пользователя").assertIsDisplayed()
        composeRule.onNodeWithText("Модерация водителей").assertIsDisplayed()
    }

    @Test
    fun adminCabinet_bashkir_showsHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdminCabinetScreen(
                    onBack = {}, onAdminRequest = {}, onAdminResponses = {},
                    onAds = {}, onDrivers = {}, onReports = {}, onPaymentRequests = {},
                )
            }
        }
        composeRule.onNodeWithText("Админ кабинеты").assertIsDisplayed()
    }

    @Test
    fun adminCabinet_driversRowClick_firesOnDrivers() {
        var opened = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminCabinetScreen(
                    onBack = {}, onAdminRequest = {}, onAdminResponses = {},
                    onAds = {}, onDrivers = { opened = true }, onReports = {}, onPaymentRequests = {},
                )
            }
        }
        composeRule.onNodeWithText("Модерация водителей").performClick()
        assertTrue(opened)
    }

    @Test
    fun adminCabinet_adsRowClick_firesOnAds() {
        // «Реклама» — в последней группе, ниже сгиба → доскролливаем перед кликом.
        var opened = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminCabinetScreen(
                    onBack = {}, onAdminRequest = {}, onAdminResponses = {},
                    onAds = { opened = true }, onDrivers = {}, onReports = {}, onPaymentRequests = {},
                )
            }
        }
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Реклама"))
        composeRule.onNodeWithText("Реклама").performClick()
        assertTrue(opened)
    }
}
