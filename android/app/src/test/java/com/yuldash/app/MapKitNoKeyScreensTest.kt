package com.yuldash.app

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Все места, где создаётся живая карта, не роняют приложение без ключа Яндекс MapKit.
 * Сборка в чистом worktree и в CI идёт без `local.properties` (ключа там и не должно быть), а
 * `MapKitFactory.initialize` тогда бросает «You need to set the API key before using MapKit!».
 * На эмуляторе 14.10.2026 так закрывалось приложение на «Отметить на карте» и на карте такси.
 *
 * Экран брони закрывает `BookingRouteMapNoKeyTest`; здесь — остальные. Тесты имеют смысл только в
 * сборке без ключа; на машине с ключом в `local.properties` рисуется настоящая карта, которую JVM
 * не умеет, — тесты пропускаются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapKitNoKeyScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun onlyWithoutKey() {
        assumeTrue("в сборке есть ключ MapKit — живая карта", BuildConfig.YANDEX_MAPKIT_KEY.isBlank())
    }

    @Test
    fun pickupPicker_withoutMapKey_showsPlaceholderAndBackWorks() {
        var dismissed = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PickupPickerOverlay(initial = null, onConfirm = { _, _ -> }, onDismiss = { dismissed++ })
            }
        }
        composeRule.onNodeWithText("Карта сейчас недоступна").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun pickupPicker_withoutMapKey_isBilingual() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PickupPickerOverlay(initial = null, onConfirm = { _, _ -> }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Карта хәҙер юҡ").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Артҡа").assertIsDisplayed()
    }

    @Test
    fun pickupPicker_systemBack_dismissesPickerBeforeParentNavigation() {
        var dismissed = 0
        var parentBacks = 0
        val visible = mutableStateOf(true)
        lateinit var dispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { parentBacks++ }
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                if (visible.value) PickupPickerOverlay(
                    initial = null,
                    onConfirm = { _, _ -> },
                    onDismiss = { dismissed++; visible.value = false },
                )
            }
        }
        composeRule.onNodeWithText("Карта сейчас недоступна").assertIsDisplayed()
        composeRule.runOnIdle { dispatcher.onBackPressed() }

        assertEquals("Системное «Назад» сначала закрывает пикер, сохраняя форму/режим", 0, parentBacks)
        assertEquals(1, dismissed)
        composeRule.onAllNodesWithText("Карта сейчас недоступна").assertCountEquals(0)

        // После закрытия пикера та же настоящая цепочка возврата снова принадлежит родителю.
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(1, parentBacks)
        assertEquals(1, dismissed)
    }

    @Test
    fun pickupPicker_backButton_staysBelowUnconsumedStatusBar() {
        assertBackButtonRespectsStatusBar(parentConsumes = false)
    }

    @Test
    fun pickupPicker_backButton_doesNotAddConsumedStatusBarTwice() {
        assertBackButtonRespectsStatusBar(parentConsumes = true)
    }

    private fun assertBackButtonRespectsStatusBar(parentConsumes: Boolean) {
        lateinit var view: View
        var topPx = 0
        var observedTopPx = 0
        composeRule.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            topPx = with(density) { 64.dp.roundToPx() }
            // Читаем настоящий holder Compose до dispatch, чтобы его listener уже был подключён.
            val statusTop = WindowInsets.statusBars.getTop(density)
            SideEffect { observedTopPx = statusTop }
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Box(Modifier.fillMaxSize().then(if (parentConsumes) Modifier.statusBarsPadding() else Modifier)) {
                    PickupPickerOverlay(initial = null, onConfirm = { _, _ -> }, onDismiss = {})
                }
            }
        }
        composeRule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, topPx, 0, 0))
                    .build(),
            )
        }
        composeRule.waitForIdle()
        assertEquals("Тест должен доставить системный отступ в настоящий WindowInsets Compose", topPx, observedTopPx)
        val back = composeRule.onNodeWithContentDescription("Назад").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Кнопка «Назад» не перекрывается системной областью: $back, inset=$topPx", back.top >= topPx)
        if (parentConsumes) {
            assertTrue("Родитель уже дал системный отступ: второе прибавление недопустимо, $back",
                back.top < topPx * 2f)
        }
    }

    @Test
    fun taxiRouteMap_withoutMapKey_showsPlaceholderInsteadOfCrashing() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantRouteMap(
                    from = Point(53.58, 58.31),
                    to = Point(54.72, 55.94),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.onNodeWithText("Карта сейчас недоступна").assertIsDisplayed()
    }
}
