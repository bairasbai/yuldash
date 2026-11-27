package com.yuldash.app

/** Lives on the service main thread; an HTTP response belongs to one exact onStartCommand. */
internal class TaxiLineRun {
    class Start(val owner: Long, val startId: Int)

    var current: Start? = null
        private set

    fun started(owner: Long, startId: Int) {
        current = Start(owner, startId)
    }

    fun isCurrent(start: Start): Boolean = current === start

    fun close(start: Start): Boolean {
        if (!isCurrent(start)) return false
        current = null
        return true
    }
}
