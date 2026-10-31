package com.yuldash.app.data

/** Список сохраняет обычные операции List; partial — сервер просмотрел не всех кандидатов. */
data class ScannedFeed<T>(val items: List<T>, val partial: Boolean = false) : List<T> by items
