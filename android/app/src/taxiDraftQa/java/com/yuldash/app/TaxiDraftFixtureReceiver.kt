package com.yuldash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import org.json.JSONObject
import java.io.File

/** The root-owned emulator provisions authentication only. No route or Android state is injected. */
class TaxiDraftFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.DEBUG && BuildConfig.YULDASH_NATIVE_QA_PINNED_API)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API)
        val session = JSONObject(File(context.filesDir, "taxi-consent-qa/session.json").readText())
        when (val action = intent.getStringExtra("fixture")) {
            "logout" -> ApiClient.logout()
            "primary", "alternate", "relogin" -> {
                val identity = if (action == "primary") session else session.getJSONObject(action)
                ApiClient.saveToken(identity.getString("access"))
                check(ApiClient.myUserId() == identity.getInt("uid"))
                check(context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("onboarding_completed", true).commit())
            }
            else -> error("Unknown fixture action")
        }
        resultCode = 107
    }
}
