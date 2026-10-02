package com.yuldash.app.walk.l1_4

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.yuldash.app.rememberPriceSpeaker
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * leaf-1.4 R6 (правка по ревью Opus): `PriceSpeaker.ready` раньше был обычным Kotlin `var`.
 * Инициализация синтезатора речи асинхронная — колбэк `TextToSpeech.OnInitListener` приходит
 * ПОСЛЕ первой отрисовки экрана, а обычное поле меняется мимо системы состояний Compose.
 * Кнопка «Прочитать цену вслух» либо не появлялась вовсе (если успевала отрисоваться ДО
 * колбэка и больше ничего не перерисовывало экран), либо появлялась СЛУЧАЙНО — только если
 * что-то ДРУГОЕ на экране вызывало перерисовку уже после готовности синтезатора.
 *
 * `ShadowTextToSpeech` (Robolectric) сам НИКОГДА не вызывает `onInit` — это подтверждено
 * разбором байткода шadow-класса (единственные `Handler.post` там — внутри `speak()`, не
 * в конструкторе/`initTts()`). Значит тест может дёрнуть колбэк вручную, строго ПОСЛЕ первой
 * отрисовки, и это честно воспроизводит реальную асинхронность.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
class PriceSpeakerReadyStateTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        // languageAvailabilities и lastTextToSpeechInstance — статика, общая на весь прогон.
        ShadowTextToSpeech.reset()
    }

    @Composable
    private fun ReadyProbe() {
        val speaker = rememberPriceSpeaker()
        // Тот же паттерн, что в TaxiPriceAloudButton (InstantOrderScreen.kt):
        // чтение `speaker.ready` прямо в теле @Composable.
        if (speaker.ready) Text("READY_FOR_SPEECH")
    }

    @Test
    fun readyFlipsAfterAsyncCallback_recomposesWithoutAnyOtherTrigger() {
        ShadowTextToSpeech.addLanguageAvailability(Locale("ru", "RU"))

        compose.setContent { ReadyProbe() }
        compose.waitForIdle()
        // Колбэк ещё не пришёл — кнопки/текста быть не должно.
        compose.onNodeWithText("READY_FOR_SPEECH").assertDoesNotExist()

        // Дёргаем ТОЛЬКО колбэк синтезатора — ничего больше на экране не меняем и не кликаем.
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        compose.waitForIdle()

        // Если `ready` наблюдаемый (State) — экран сам перерисуется. Если это обычный var —
        // этот узел так и не появится, потому что ничего больше не просило Compose перерисовать
        // ReadyProbe().
        compose.onNodeWithText("READY_FOR_SPEECH").assertIsDisplayed()
    }

    @Test
    fun noLanguageAvailable_staysNotReady_noCrashOnFailureStatus() {
        // Ни один язык не зарегистрирован в shadow — ru/ba недоступны.
        compose.setContent { ReadyProbe() }
        compose.waitForIdle()

        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.ERROR)
        compose.waitForIdle()

        // Неудача синтезатора — не крash и не ложная кнопка.
        compose.onNodeWithText("READY_FOR_SPEECH").assertDoesNotExist()
    }
}
