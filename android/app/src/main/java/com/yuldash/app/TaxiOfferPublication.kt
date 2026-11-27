package com.yuldash.app

/** Serializes publication, but never holds the monitor while an offer is fetched. */
internal class TaxiOfferPublication {
    data class Offer(val orderId: Int, val deadline: String)
    data class Ticket(val session: Long, val revision: Long)

    private var session: Long? = null
    private var revision = 0L
    private var observed = false
    private var current: Offer? = null
    private var shown: Offer? = null

    @Synchronized
    fun snapshot(owner: Long): Ticket {
        if (session != owner) {
            session = owner
            revision++
            observed = false
            current = null
            shown = null
        }
        return Ticket(owner, revision)
    }

    @Synchronized
    fun observe(
        ticket: Ticket,
        offer: Offer?,
        clear: () -> Unit = {},
        post: () -> Boolean = { false },
    ) {
        if (ticket.session != session || ticket.revision != revision) return
        if (!observed || current != offer) {
            observed = true
            current = offer
            revision++
            shown = null
        }
        if (offer == null) {
            // Also clear a notification left by a previous process.
            clear()
        } else if (shown != offer && post()) {
            shown = offer
        }
    }
}
