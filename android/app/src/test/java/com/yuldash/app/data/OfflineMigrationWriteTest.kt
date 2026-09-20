package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMigrationWriteTest {
    private lateinit var context: Context
    private val plain get() = context.getSharedPreferences("migration-plain", 0)
    private val secure get() = context.getSharedPreferences("migration-secure", 0)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        plain.edit().clear().commit()
        secure.edit().clear().commit()
    }
    private fun refusesWrites() = object : SharedPreferences by secure {
        override fun edit(): SharedPreferences.Editor {
            val editor = secure.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun commit() = false
                override fun apply() = Unit
            }
        }
    }
    private fun pass(id: Int) = TripPass.fromJson(JSONObject().put("booking_id", id))
    @Test fun failedPassportCopyPreservesSourceAndDefersNewWrites() {
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass(7)))
        TripPassStore.initStores(plain, refusesWrites())
        assertTrue("source erased before secure copy succeeded", plain.contains("pass_7"))
        assertNotNull(TripPassStore.load(context, 7))
        assertFalse("pending migration must refuse new writes", TripPassStore.save(context, pass(8)))
        TripPassStore.initStores(plain, secure)
        assertNotNull(TripPassStore.load(context, 7))
        assertNull(TripPassStore.load(context, 8))
        assertTrue(TripPassStore.save(context, pass(8)))
        assertNotNull(TripPassStore.load(context, 8))
        assertFalse(plain.contains("pass_7"))
    }
    @Test fun failedQueueCopyPreservesSourceAndDefersNewWrites() {
        Outbox.initStores(plain, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "first")))
        Outbox.initStores(plain, refusesWrites())
        assertTrue("source erased before secure copy succeeded", plain.contains("queue"))
        assertEquals(1, Outbox.count(context, 7))
        assertFalse("pending migration must refuse new writes", Outbox.enqueue(context, Outbox.newMessage(8, "second")))
        Outbox.initStores(plain, secure)
        assertEquals(1, Outbox.count(context, 7))
        assertEquals(0, Outbox.count(context, 8))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(8, "second")))
        assertEquals(1, Outbox.count(context, 8))
        assertFalse(plain.contains("queue"))
    }
}
