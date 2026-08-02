// Заглушка Firebase (артефакты на dl.google.com). Сигнатуры повторяют реальные.
package com.google.firebase.analytics

import android.content.Context
import android.os.Bundle

class FirebaseAnalytics private constructor() {
    fun logEvent(name: String, params: Bundle?) {}
    fun setUserProperty(name: String, value: String?) {}
    companion object {
        @JvmStatic fun getInstance(context: Context): FirebaseAnalytics = FirebaseAnalytics()
    }
}
