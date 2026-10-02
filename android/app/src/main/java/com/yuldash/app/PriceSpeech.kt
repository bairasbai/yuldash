package com.yuldash.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Озвучивание цены вслух — для тех, кто мелкий шрифт не читает.
 *
 * Зачем это вообще есть. Разбор цены мы сделали подробным: поездка, дорога водителя, кресло,
 * наценка. Для человека с хорошим зрением это ответ на вопрос «за что». Для бабушки, которая
 * заказывает такси в клинику, — четыре строчки мелким шрифтом, то есть ничего. Она видит
 * только итоговую цифру и решает, что её обманывают.
 *
 * Читаем КОРОТКО — три числа: поездка, дорога водителя, итого. Полный чек вслух никто не
 * дослушает: длинная озвучка раздражает даже там, где она задумана как забота (решение
 * Александра, Q14 от 2026-08-23).
 *
 * Про язык. Синтезатор башкирского на телефонах почти никогда не установлен. Врать голосом
 * нельзя в обе стороны: читать башкирский текст русским голосом — получится каша, а молча
 * ничего не сделать по нажатию кнопки — человек решит, что приложение сломано. Поэтому:
 * пробуем язык человека, не вышло — читаем РУССКУЮ фразу (а не башкирскую русским голосом),
 * совсем не вышло — кнопки просто нет.
 */
internal class PriceSpeaker(context: Context) {
    private var engine: TextToSpeech? = null
    /**
     * Синтезатор готов и хотя бы один из наших языков ему знаком.
     *
     * Через `mutableStateOf`, а не простое поле: инициализация TTS асинхронная, колбэк
     * приходит позже первой отрисовки экрана. Обычный `var` молча меняет значение мимо
     * Compose — кнопка «Прочитать вслух» либо не появлялась вовсе, либо появлялась
     * случайно, только если что-то ДРУГОЕ на экране вызывало перерисовку в нужный момент.
     */
    var ready: Boolean by mutableStateOf(false)
        private set
    /** Башкирский голос реально есть. Почти всегда false — и это нормально. */
    var bashkirAvailable: Boolean by mutableStateOf(false)
        private set

    init {
        // Инициализация асинхронная: до колбэка `ready` остаётся false и кнопки нет.
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) return@TextToSpeech
            bashkirAvailable = tts.isLanguageAvailable(Locale("ba")) >= TextToSpeech.LANG_AVAILABLE
            val ru = tts.isLanguageAvailable(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE
            ready = bashkirAvailable || ru
            tts.language = if (bashkirAvailable) Locale("ba") else Locale("ru", "RU")
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        // QUEUE_FLUSH: второе нажатие перебивает первое. Две цены одновременно — это шум,
        // а не забота; человек чаще жмёт повторно именно потому, что не расслышал.
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yuldash-price")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}

/** Живёт ровно столько, сколько экран: синтезатор держит системный сервис и его надо гасить. */
@Composable
internal fun rememberPriceSpeaker(): PriceSpeaker {
    val ctx = LocalContext.current
    val speaker = remember { PriceSpeaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    return speaker
}

/** «рубль / рубля / рублей» — голосом сокращение «₽» звучало бы как «эр». */
internal fun rublesAloud(amount: Int): String =
    "$amount " + pluralRu(amount, "рубль", "рубля", "рублей")

/**
 * Фраза для озвучки. Три числа, не больше: поездка, дорога водителя, итого.
 *
 * Кресло и опции в озвучку не идут намеренно — человек их только что выбрал сам и помнит.
 * Наценку тоже не читаем: она уже внутри «поездки», а отдельным числом только запутает
 * на слух.
 */
internal fun priceAloudRu(ridePrice: Int, pickupFee: Int, total: Int): String = buildString {
    append("Поездка ").append(rublesAloud(ridePrice))
    if (pickupFee > 0) append(", дорога водителя ").append(rublesAloud(pickupFee))
    append(". Всего ").append(rublesAloud(total)).append(".")
}

/** То же по-башкирски — читается, только если в телефоне есть башкирский голос. */
internal fun priceAloudBa(ridePrice: Int, pickupFee: Int, total: Int): String = buildString {
    append("Сәфәр ").append(ridePrice).append(" һум")
    if (pickupFee > 0) append(", водитель юлы ").append(pickupFee).append(" һум")
    append(". Барлығы ").append(total).append(" һум.")
}
