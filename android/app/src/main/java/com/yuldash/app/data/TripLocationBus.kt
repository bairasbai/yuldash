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
}
