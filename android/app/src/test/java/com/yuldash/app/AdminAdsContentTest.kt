package com.yuldash.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.AdStatsDto
import com.yuldash.app.data.AdminAdDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-компоненты экрана управления рекламой (умный `AdminAdsScreen` = стейт+сеть+LazyColumn — тут не рендерим,
 * он покрыт интеграционно). Здесь чистые под-компоненты, ставшие `internal`:
 *  - `planLabel`  — метка тарифа (String → appText), зависит от языка.
 *  - `AdAdminCard` — карточка объявления с МОДЕРАЦИЕЙ (одобрить/отклонить с причиной), статусами и гардом busy.
 *  - `CreateAdForm` — форма создания (валидация «можно сохранить»).
 * Всё зависит от языка (`LocalAppLanguage`) → оборачиваем в CompositionLocalProvider. Тексты — ДОСЛОВНО из appText.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest.
 * autoAdvance=false только там, где в кнопке крутится спиннер (busy) — иначе тест зависнет на анимации.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminAdsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Фабрика DTO объявления: все поля с дефолтами → каждый тест меняет только нужное (статус, тексты).
    private fun ad(
        id: String = "1",
        partner: String = "Кафе Батыр",
        title: String = "Скидка 20%",
        text: String = "Заходи на обед",
        plan: String = "standard",
        status: String = "active",
        placements: String = "route",
        erid: String = "ABC123",
        endsAt: String? = null,
        live: Boolean = true,
        expired: Boolean = false,
        rejectReason: String = "",
        ownerId: Int? = null,
    ) = AdminAdDto(
        id = id, partner = partner, title = title, text = text, plan = plan, status = status,
        placements = placements, erid = erid, endsAt = endsAt, live = live, expired = expired,
        rejectReason = rejectReason, ownerId = ownerId,
    )

    // --- planLabel: метка тарифа по ключу плана, на языке из LocalAppLanguage ---

    @Test
    fun planLabel_founder_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Text(planLabel("founder"))
            }
        }
        composeRule.onNodeWithText("Основатель").assertIsDisplayed()
    }

    @Test
    fun planLabel_founder_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Text(planLabel("founder"))
            }
        }
        composeRule.onNodeWithText("Нигеҙләүсе").assertIsDisplayed()
    }

    @Test
    fun planLabel_premiumAndUnknown_fallToExpected() {
        // "premium" → Премиум; любой другой ключ (в т.ч. "standard") → Стандарт (ветка else).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Text(planLabel("premium"))
                Text(planLabel("standard"))
            }
        }
        composeRule.onNodeWithText("Премиум").assertIsDisplayed()
        composeRule.onNodeWithText("Стандарт").assertIsDisplayed()
    }

    // ─────────────────── AdAdminCard: карточка объявления (модерация / статусы / гард busy) ───────────────────

    // Рендер карточки на выбранном языке; колбэки по умолчанию — no-op.
    private fun card(
        ad: AdminAdDto,
        stat: AdStatsDto? = null,
        busy: Boolean = false,
        language: AppLanguage = AppLanguage.Ru,
        onPublish: () -> Unit = {},
        onPause: () -> Unit = {},
        onDelete: () -> Unit = {},
        onEdit: () -> Unit = {},
        onApprove: () -> Unit = {},
        onReject: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                AdAdminCard(
                    ad = ad, stat = stat, busy = busy,
                    onPublish = onPublish, onPause = onPause, onDelete = onDelete, onEdit = onEdit,
                    onApprove = onApprove, onReject = onReject,
                )
            }
        }
    }

    @Test
    fun card_active_showsTitleStatusAndStats() {
        // активное «живое» объявление: заголовок, метка статуса «Активно», показы/клики из stat.
        card(ad = ad(status = "active", live = true), stat = AdStatsDto(impressions = 120, clicks = 8))
        composeRule.onNodeWithText("Скидка 20%").assertIsDisplayed()
        composeRule.onNodeWithText("Активно").assertIsDisplayed()
        // строка мест+статистики склеена в один Text → ищем по подстроке показов/кликов (substring=true).
        composeRule.onNodeWithText("показы 120", substring = true).assertExists()
        composeRule.onNodeWithText("клики 8", substring = true).assertExists()
    }

    @Test
    fun card_rejected_showsReasonToPartner() {
        // отклонённое: красная метка «Отклонено» + причина отказа (её видит партнёр).
        card(ad = ad(status = "rejected", live = false, rejectReason = "Нет маркировки"))
        composeRule.onNodeWithText("Отклонено").assertIsDisplayed()
        composeRule.onNodeWithText("Причина отказа: Нет маркировки", substring = true).assertExists()
    }

    @Test
    fun card_pendingReview_showsApproveAndReject_notPublish() {
        // на модерации: доступны «Одобрить» и «Отклонить»; кнопки «Опубликовать»/«Пауза» скрыты.
        card(ad = ad(status = "pending_review", live = false))
        composeRule.onNodeWithText("На модерации").assertIsDisplayed()
        composeRule.onNodeWithText("Одобрить").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликовать").assertDoesNotExist()
    }

    @Test
    fun card_pendingReview_approveClick_firesCallback() {
        // клик «Одобрить» шлёт onApprove (админ подтверждает объявление).
        var approved = false
        card(ad = ad(status = "pending_review", live = false), onApprove = { approved = true })
        composeRule.onNodeWithText("Одобрить").performClick()
        assertEquals(true, approved)
    }

    @Test
    fun card_pendingReview_rejectFlow_revealsReasonField() {
        // старт: поля причины нет; клик «Отклонить» раскрывает форму причины (внутренний стейт showReject).
        card(ad = ad(status = "pending_review", live = false))
        composeRule.onNodeWithText("Причина отказа (партнёр увидит)").assertDoesNotExist()
        composeRule.onNodeWithText("Отклонить").performClick()
        composeRule.onNodeWithText("Причина отказа (партнёр увидит)").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()   // в раскрытой форме появляется «Отмена»
    }

    @Test
    fun card_rejectForm_sendDisabledWhenReasonBlank() {
        // пустая причина → кнопка отправки «Отклонить» залочена (reason.isNotBlank() в enabled).
        card(ad = ad(status = "pending_review", live = false, rejectReason = ""))
        composeRule.onNodeWithText("Отклонить").performClick()   // раскрыть форму (поле пустое)
        composeRule.onNodeWithText("Отклонить").assertIsNotEnabled()
    }

    @Test
    fun card_rejectForm_withPrefilledReason_sendsIt() {
        // reason предзаполнен непустым (ad.rejectReason) → форма причины сразу валидна: кнопка отправки шлёт причину.
        var rejectedWith: String? = null
        card(ad = ad(status = "pending_review", live = false, rejectReason = "Спам"), onReject = { rejectedWith = it })
        composeRule.onNodeWithText("Отклонить").performClick()   // раскрыть форму (reason="Спам" уже валиден)
        composeRule.onNodeWithText("Отклонить").assertIsEnabled()
        composeRule.onNodeWithText("Отклонить").performClick()   // отправка
        assertEquals("Спам", rejectedWith)
    }

    @Test
    fun card_busy_deleteButtonDisabled_guardsDoubleTap() {
        // гард двойного тапа: при busy кнопки действий залочены. Целимся в «Удалить» — её текст виден
        // (в отличие от «Одобрить», где при busy крутится спиннер вместо текста).
        composeRule.mainClock.autoAdvance = false   // на карточке pending_review при busy крутится спиннер «Одобрить»
        card(ad = ad(status = "pending_review", live = false), busy = true)
        composeRule.onNodeWithText("Удалить").assertIsNotEnabled()
    }

    @Test
    fun card_active_deleteClick_firesCallback_whenIdle() {
        // одиночный тап «Удалить» (busy=false) зовёт onDelete.
        var deleted = false
        card(ad = ad(status = "active", live = true), busy = false, onDelete = { deleted = true })
        composeRule.onNodeWithText("Удалить").assertIsEnabled()
        composeRule.onNodeWithText("Удалить").performClick()
        assertEquals(true, deleted)
    }

    @Test
    fun card_bashkir_showsBashkirStatusAndButtons() {
        // двуязычие: башкирские метка статуса и подписи кнопок (черновой BA → на проверку носителю).
        card(ad = ad(status = "pending_review", live = false), language = AppLanguage.Ba)
        composeRule.onNodeWithText("Тикшереүҙә").assertIsDisplayed()   // «На модерации»
        composeRule.onNodeWithText("Раҫларға").assertIsDisplayed()      // «Одобрить»
    }

    @Test
    fun card_emptyTitle_showsPlaceholderTitle() {
        // объявление без названия → плейсхолдер «Без названия» (ad.title.ifBlank).
        card(ad = ad(title = "", status = "active", live = true))
        composeRule.onNodeWithText("Без названия").assertIsDisplayed()
    }

    // ─────────────────── CreateAdForm: форма создания (валидация «можно сохранить») ───────────────────

    @Test
    fun createForm_emptyFields_saveButtonDisabled() {
        // пустая форма: партнёр/заголовок/текст/erid не заполнены → canSave=false → «Создать (черновик)» залочена.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CreateAdForm(founderFull = false, edit = null, onCreated = {})
            }
        }
        composeRule.onNodeWithText("Новое объявление").assertIsDisplayed()
        composeRule.onNodeWithText("Создать (черновик)").assertIsNotEnabled()
    }

    @Test
    fun createForm_bashkir_showsBashkirTitle() {
        // двуязычие заголовка формы (черновой BA → на проверку носителю).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                CreateAdForm(founderFull = false, edit = null, onCreated = {})
            }
        }
        composeRule.onNodeWithText("Яңы иғлан").assertIsDisplayed()
    }
}
