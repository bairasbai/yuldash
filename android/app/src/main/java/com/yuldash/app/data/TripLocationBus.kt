package com.yuldash.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Мост между foreground-сервисом стриминга позиции (TripLocationService) и UI карты.
 * Сервис пишет сюда позицию ДРУГОГО участника поездки; MapScreen читает → рисует маркер-машину.
 * Singleton-объект: переживает смену экранов, не зависит от жизненного цикла Compose.
 */
object TripLocationBus {
    var peer by mutableStateOf<LocationSocket.Peer?>(null)   // последняя позиция другого участника
    var bookingId: Int? = null                                // бронь, по которой идёт стрим (UI-гейт)

    /** Служба везёт машину для всего рейса (Д2, 24.10.2026): кабинет пишет «машину видят пассажиры рейса». */
    var rideShareActive by mutableStateOf(false)

    /**
     * Водитель на ЭТОМ телефоне сделал шаг по брони — «Выехал», «Подъезжаю», «Сел», «Завершить», «Не пришёл»
     * (сервер принял). Слушает только живая служба точки (Д2): так она узнаёт, что водитель ведёт рейс,
     * и сразу переводит GPS на другую живую бронь, когда эта закрылась. Службы нет — ничего не делаем:
     * этот сигнал никогда не включает GPS сам. `closed` — бронь закрыта (завершена или неявка).
     */
    @Volatile internal var driverActionListener: ((bookingId: Int, closed: Boolean) -> Unit)? = null

    internal fun driverAction(bookingId: Int, closed: Boolean) {
        runCatching { driverActionListener?.invoke(bookingId, closed) }
    }

    /**
     * Шаг уходит на сервер (ещё до ответа) или сервер его отверг (`failed`). Нужно закрывающему шагу
     * («Завершить», «Не пришёл»): сервер закрывает канал брони сразу, и закрытие может прийти раньше ответа —
     * служба, узнав о шаге заранее, не гасит GPS, а ждёт ответ (вторая приёмка Д2, P1).
     */
    @Volatile internal var driverStepListener: ((bookingId: Int, closing: Boolean, failed: Boolean) -> Unit)? = null

    /** Отправить шаг водителя и сообщить службе: «ушёл» → «принят» / «отвергнут» (обрыв сети — не отказ). */
    internal inline fun <T> driverStep(bookingId: Int, closing: Boolean, request: () -> Result<T>): Result<T> {
        runCatching { driverStepListener?.invoke(bookingId, closing, false) }
        return request()
            .onSuccess { driverAction(bookingId, closed = closing) }
            .onFailure { e -> if (e is ApiException) runCatching { driverStepListener?.invoke(bookingId, closing, true) } }
    }
}
