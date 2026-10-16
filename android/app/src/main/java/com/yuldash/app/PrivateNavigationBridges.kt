package com.yuldash.app

import java.lang.ref.WeakReference

/** Compatibility signals keep the publishing window; durable records stay in its VM. */
internal class NavigationBridgeOwner {
    private var owner = WeakReference<YuldashViewModel>(null)
    fun claim(vm: YuldashViewModel) { owner = WeakReference(vm) }
    fun isForeign(vm: YuldashViewModel): Boolean = owner.get()?.let { it !== vm } == true
    fun belongsTo(vm: YuldashViewModel): Boolean = owner.get() === vm
    fun release(vm: YuldashViewModel) { if (belongsTo(vm)) owner.clear() }
}

internal object PrivateNavigationBridges {
    val booking = NavigationBridgeOwner()
    val screen = NavigationBridgeOwner()
}
