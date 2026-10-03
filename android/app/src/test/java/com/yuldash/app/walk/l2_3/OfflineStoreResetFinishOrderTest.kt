package com.yuldash.app.walk.l2_3

import android.app.Application
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineStoreReset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * leaf-2.3 (OfflineStoreReset.kt): `finish()` комментирует "Never remove the marker before the
 * old secure data is durably erased", но до этого теста ни один сценарий не сбивал именно
 * commit `secure.edit().clear()` внутри `availableSecure`/`finish` — только commit соседних
 * ключей (PENDING/pass_X). Это прямая защита 152-ФЗ: если стереть чужие (предыдущего аккаунта)
 * зашифрованные данные физически не удалось, отметка "уборка нужна" обязана остаться, иначе
 * следующий человек, вошедший на этом телефоне, рискует однажды получить secure-хранилище,
 * которое приложение уже считает чистым, а на диске всё ещё лежат старые записи.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineStoreResetFinishOrderTest {
    @Test fun secureClearFailureKeepsPendingFlagSoNextAttemptRetries() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        secure.edit().putString("pass_7", "stale-previous-account-data").commit()
        plain.edit().putBoolean(OfflineStoreReset.PENDING, true).commit()
        // MemoryDiskPreferences treats any failRemovalOf as "the clear() commit itself fails".
        secure.failRemovalOf = "anything"

        val unavailable = OfflineStoreReset.availableSecure(plain, secure)

        assertNull("secure must not be handed out as usable while old data on it is still unerased", unavailable)
        assertTrue("stale previous-account data must still be physically present, not silently forgotten",
            secure.restarted().contains("pass_7"))
        assertTrue("a failed physical clear must leave the pending flag so the next attempt retries",
            plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))

        secure.failRemovalOf = null
        val recovered = OfflineStoreReset.availableSecure(plain, secure)

        assertNotNull("once the write actually succeeds, the retry must finish the job", recovered)
        assertFalse("the previous account's data must be gone once recovery truly committed",
            secure.restarted().contains("pass_7"))
        assertFalse("the marker must only clear once the physical erase is confirmed on disk",
            plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))
    }

    @Test fun clearFailureOnPlainNeverReportsSuccessOrLiftsQuarantine() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        plain.edit().putString("pass_7", "account-A").commit()
        // clearing=true always triggers failRemovalOf in the fault model, regardless of key name:
        // this models a disk that refuses the wipe (full disk / IO error), not a missing key.
        plain.failRemovalOf = "anything"

        val result = OfflineStoreReset.clear(plain, secure)

        assertFalse("a store that could not actually be wiped must never be reported as cleared", result)
        assertTrue("an honestly-failed wipe must keep the quarantine so load()/save() keep refusing",
            com.yuldash.app.data.OfflineMigration.resetUnconfirmed(plain))
    }

    // R8b: `availableSecure` reads PENDING with `runCatching {...}.getOrDefault(true)` — an
    // unreadable flag must be treated as "cleanup still owed", not as "nothing to do". Simulate
    // an unreadable flag the same way a real corrupted preferences file would surface: the stored
    // value has the wrong type, so the typed getter throws instead of returning a boolean.
    @Test fun unreadablePendingFlagIsTreatedAsADebtNotAsAlreadyClean() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        secure.edit().putString("pass_7", "stale-previous-account-data").commit()
        // Wrong type under the PENDING key: reading it as a Boolean throws a ClassCastException,
        // exactly the kind of failure `runCatching` is there to catch.
        plain.edit().putString(OfflineStoreReset.PENDING, "not-a-boolean").commit()

        val available = OfflineStoreReset.availableSecure(plain, secure)

        // An unreadable flag must be treated as a debt: finish() must run, and since secure is
        // actually reachable here, the previous account's leftover data must end up erased
        // instead of being silently handed out as "already clean".
        assertNotNull("secure must still be usable once finish() actually succeeds", available)
        assertFalse("treating an unreadable flag as 'no debt' would leak the previous account's data",
            secure.restarted().contains("pass_7"))
    }
}
