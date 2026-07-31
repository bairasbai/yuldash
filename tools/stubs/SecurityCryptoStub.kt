// Заглушка androidx.security.crypto (артефакт лежит на dl.google.com, он тут закрыт).
// Сигнатуры повторяют реальные — нужны, чтобы проверить НАШ код, а не библиотеку.
package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences

class MasterKey private constructor() {
    enum class KeyScheme { AES256_GCM }
    class Builder(context: Context) {
        fun setKeyScheme(scheme: KeyScheme): Builder = this
        fun build(): MasterKey = MasterKey()
    }
}

object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }
    enum class PrefValueEncryptionScheme { AES256_GCM }
    @JvmStatic
    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        prefKeyEncryptionScheme: PrefKeyEncryptionScheme,
        prefValueEncryptionScheme: PrefValueEncryptionScheme,
    ): SharedPreferences = throw UnsupportedOperationException("stub")
}
