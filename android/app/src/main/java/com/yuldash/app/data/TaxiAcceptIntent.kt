package com.yuldash.app.data

/** Contains no bearer or trip details. Runtime generation is not persisted. */
internal data class TaxiAcceptIntent(
    val orderId: Int,
    val generation: Long,
    val driverId: Int,
    val attempt: String,
)
