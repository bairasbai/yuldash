package com.yuldash.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedAuthInstrumentedTest {
    @Test
    fun seedPassengerSession() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("token").orEmpty()
        // Утилита сидинга сессии: запускается точечно с `-e token <JWT>`. Без аргумента в общем
        // прогоне connectedCheck — ПРОПУСК (assumeTrue), а не провал всего набора.
        org.junit.Assume.assumeTrue("Instrumentation argument `token` is required", token.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ApiClient.init(context)
        ApiClient.saveToken(token)
        ApiClient.saveName(args.getString("name") ?: "Codex Passenger")
        val securePrefs = runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                "yuldash_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
        (securePrefs ?: context.getSharedPreferences("yuldash", Context.MODE_PRIVATE))
            .edit()
            .putString("token", token)
            .putString("user_name", args.getString("name") ?: "Codex Passenger")
            .commit()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .putString("preferred_role", RideRole.Passenger.name)
            .commit()
    }

}
