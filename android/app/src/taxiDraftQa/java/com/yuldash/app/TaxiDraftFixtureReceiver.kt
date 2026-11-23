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
        if (intent.getStringExtra("fixture") == "read-controls") {
            TaxiDraftControls.write(context)
            resultCode = 107
            return
        }
        val session = JSONObject(File(context.filesDir, "taxi-consent-qa/session.json").readText())
        when (val action = intent.getStringExtra("fixture")) {
            "logout" -> ApiClient.logout()
            "primary", "alternate", "relogin" -> {
                val identity = if (action == "primary") session else session.getJSONObject(action)
                val language = intent.getStringExtra("language")
                require(language == "Ru" || language == "Ba")
                check(context.getSharedPreferences("yuldash_settings", Context.MODE_PRIVATE)
                    .edit().putString("app_language", language).commit())
                check(context.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE)
                    .edit().putBoolean("dark_override", language == "Ba").commit())
                if (action == "primary") {
                    // The normal location reader starts with its last fix before listening to GPS.
                    // Set this *only* in the initial login process; a cold task is never reseeded.
                    LocationPrefs.lastLat = 52.591
                    LocationPrefs.lastLng = 58.317
                }
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
