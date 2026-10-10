package com.yuldash.app

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only fault: model an already-sent global notification whose delivery was lost. */
internal fun withLostGlobalSnapshotNotification(block: () -> Unit) {
    val manager = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
    fun flag(name: String) = manager.getDeclaredField(name).apply { isAccessible = true }
        .get(null) as AtomicBoolean
    assertTrue("Mount and settle the initial UI before injecting the notification fault", flag("started").get())
    val sent = flag("sent")
    val previous = sent.getAndSet(true)
    try {
        block()
        assertTrue("The automatic notification stayed suppressed during this control", sent.get())
    } finally {
        val duringControl = sent.get()
        sent.set(previous)
        Snapshot.sendApplyNotifications()
        println("B02_SNAPSHOT_FAULT previousSent=$previous suppressedAtEnd=$duringControl restored=${sent.get()}")
    }
}
