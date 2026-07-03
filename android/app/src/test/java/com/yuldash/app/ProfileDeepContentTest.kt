package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.AdPackageDto
import com.yuldash.app.data.MyAdDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `AdsCabinetScreen` разрезан на умную обёртку
 * (сеть/стейт) и чистый `AdsCabinetContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (загрузка / ошибка+повтор / пусто-витрина / список / отправка) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ad(
        id: String = "a1",
        title: String = "Кафе у дороги",
        text: String = "Горячий чай и выпечка",
        status: String = "draft",
        pkg: String = "week",
        pkgTitle: String = "Неделя",
        budgetKop: Int = 50000,
        periodDays: Int = 7,
        paid: Boolean = false,
    ) = MyAdDto(
        id = id, title = title, text = text, button = "Позвонить", target = "+79990000000",
        erid = "erid123", status = status, rejectReason = "",
        pkg = pkg, pkgTitle = pkgTitle, budgetKop = budgetKop, periodDays = periodDays,
        placements = "", cities = "", paid = paid, submittedAt = null,
    )

    private fun pkg(code: String = "week", title: String = "Неделя", periodDays: Int = 7) =
        AdPackageDto(code = code, title = title, titleBa = "", amountKop = 50000, periodDays = periodDays)

    private fun content(
        loading: Boolean = false,
        error: String? = null,
        ads: List<MyAdDto> = emptyList(),
        packages: List<AdPackageDto> = emptyList(),
        submittingId: String? = null,
        onRetry: () -> Unit = {},
        onCreateAd: () -> Unit = {},
        onEditAd: (MyAdDto) -> Unit = {},
        onSubmitAd: (MyAdDto) -> Unit = {},
        onPayAd: (MyAdDto) -> Unit = {},
        language: AppLanguage = AppLanguage.Ru,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                AdsCabinetContent(
                    loading = loading,
                    error = error,
                    ads = ads,
                    packages = packages,
                    submittingId = submittingId,
                    onRetry = onRetry,
                    onCreateAd = onCreateAd,
                    onEditAd = onEditAd,
                    onSubmitAd = onSubmitAd,
                    onPayAd = onPayAd,
                )
            }
        }
    }

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        content(loading = true, ads = listOf(ad()))
        composeRule.onNodeWithText("Новое объявление").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        content(error = "Не удалось загрузить кабинет")
        composeRule.onNodeWithText("Не удалось загрузить кабинет").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        content(error = "Ошибка", onRetry = { retried = true })
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsShowcaseWithPackages() {
        content(ads = emptyList(), packages = listOf(pkg()))
        composeRule.onNodeWithText("Реклама в Юлдаше").assertIsDisplayed()
        composeRule.onNodeWithText("Тарифы").assertIsDisplayed()
        composeRule.onNodeWithText("Разместить рекламу").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirShowcase() {
        content(ads = emptyList(), packages = listOf(pkg()), language = AppLanguage.Ba)
        composeRule.onNodeWithText("Юлдашта реклама").assertIsDisplayed()
        composeRule.onNodeWithText("Реклама урынлаштырырға").assertIsDisplayed()
    }

    @Test
    fun list_showsAdTitleAndCreateButton() {
        content(ads = listOf(ad()))
        composeRule.onNodeWithText("Кафе у дороги").assertIsDisplayed()
        composeRule.onNodeWithText("Новое объявление").assertIsDisplayed()
    }

    @Test
    fun list_createClick_firesOnCreateAd() {
        var created = false
        content(ads = listOf(ad()), onCreateAd = { created = true })
        composeRule.onNodeWithText("Новое объявление").performClick()
        assertTrue(created)
    }

    @Test
    fun list_draftAd_submitClick_firesOnSubmitWithItem() {
        var submitted: MyAdDto? = null
        val draft = ad(id = "draft7", status = "draft")
        content(ads = listOf(draft), onSubmitAd = { submitted = it })
        composeRule.onNodeWithText("На модерацию").performClick()
        assertEquals("draft7", submitted?.id)
    }

    @Test
    fun list_approvedUnpaidAd_payClick_firesOnPayWithItem() {
        var paid: MyAdDto? = null
        val active = ad(id = "act9", status = "active", paid = false)
        content(ads = listOf(active), onPayAd = { paid = it })
        composeRule.onNodeWithText("Оплатить размещение · 500 ₽").performClick()
        assertEquals("act9", paid?.id)
    }

    @Test
    fun list_submittingItem_showsSpinnerInsteadOfSubmitLabel() {
        composeRule.mainClock.autoAdvance = false   // спиннер в кнопке «На модерацию»
        val draft = ad(id = "d5", status = "draft")
        content(ads = listOf(draft), submittingId = "d5")
        composeRule.onNodeWithText("На модерацию").assertDoesNotExist()
    }
}
