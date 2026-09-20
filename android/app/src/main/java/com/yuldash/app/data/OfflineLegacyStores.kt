package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Old stores have no owner. Never import them into the active v2 stores. */
internal object OfflineLegacyStores {
    fun clear(context: Context) {
        for (name in listOf("yuldash_trippass", "yuldash_outbox")) {
            runCatching {
                val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val secure = runCatching {
                    val key = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                    EncryptedSharedPreferences.create(
                        context, name + "_secure", key,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                }.getOrNull()
                clearStores(plain, secure)
            }
        }
    }

    // A failed cleanup never makes legacy data eligible for reads or sending.
    internal fun clearStores(plain: SharedPreferences, secure: SharedPreferences?): Boolean =
        OfflineStoreReset.clear(plain, secure)
}
