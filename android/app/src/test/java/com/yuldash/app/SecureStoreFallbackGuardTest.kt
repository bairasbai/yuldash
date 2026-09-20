package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.OfflineStoreReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Сторож: у шифрованного хранилища с «мягким падением» обязан быть переезд и уборка.
 *
 * Зачем (аудит 2026-08-08, волна 74). Телефон водителя, его госномер и код посадки лежат
 * на диске пассажира — в шифрованном хранилище. Но шифрование поднимается не всегда:
 * на «кривой» прошивке Keystore может не завестись, и приложение честно падает на обычное
 * хранилище, чтобы человек на трассе без связи не остался с пустым экраном. Решение верное.
 *
 * Проблема начинается дальше. Телефон перезагрузили, шифрование завелось — и приложение
 * стало писать в защищённое место. Старые записи остались в открытом навсегда: перенести
 * их никто не пытался, а выход из аккаунта чистил только то хранилище, что активно сейчас.
 * Обещание «вышел — чужих данных на телефоне нет» выполнялось не полностью. У соседней
 * очереди исходящих сообщений такой перенос был с самого начала, у паспортов поездок — нет.
 *
 * Тест читает исходники: где есть пара «шифрованное + открытое», там обязаны быть и перенос,
 * и очистка открытого. Так следующее хранилище не повторит ту же половинчатость.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureStoreFallbackGuardTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun source(name: String): String {
        val f = File("src/main/java/com/yuldash/app/data/$name")
        assertTrue("не нашёл исходник: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun pass(id: Int) = TripPass(
        bookingId = id, fromCity = "Баймак", toCity = "Сибай",
        departAt = "2026-08-05T08:00:00", driverName = "Айдар",
        driverCar = "Lada Vesta", driverPlate = "А123БВ102",
        driverPhone = "+79991234567", boardingCode = "4821",
        pickup = "у школы", pickupLat = null, pickupLng = null,
        price = 300, seats = 1, paymentNote = "", savedAt = 0L,
    )

    @Test
    fun `паспорт переезжает в шифрованное и не остаётся в открытом`() {
        // Оба хранилища подставляем сами: в тестовой среде Keystore не поднимается, и проверять
        // переезд «через init» значило бы проверять ветку, которая тут никогда не выполняется.
        val plain = ctx.getSharedPreferences("миграция_открытое", Context.MODE_PRIVATE)
        val secure = ctx.getSharedPreferences("миграция_шифрованное", Context.MODE_PRIVATE)
        plain.edit().clear().apply()
        secure.edit().clear().apply()
        plain.edit().putString("pass_42", pass(42).toJson().toString()).apply()

        TripPassStore.migratePlain(plain, secure)

        val moved = secure.getString("pass_42", null)
        assertTrue("паспорт не доехал до шифрованного хранилища — телефон водителя потерян",
            moved != null && moved.contains("+79991234567"))
        assertTrue("паспорт остался лежать в открытом хранилище: ${plain.all.keys}",
            plain.all.isEmpty())
    }

    @Test
    fun `выход из аккаунта не оставляет телефон водителя на диске`() {
        TripPassStore.initStores(ctx.getSharedPreferences("yuldash_trippass", Context.MODE_PRIVATE), null)
        TripPassStore.save(ctx, pass(43))

        TripPassStore.clearAll()

        val plainLeft = ctx.getSharedPreferences("yuldash_trippass", Context.MODE_PRIVATE).all
        assertEquals("после выхода допустима только отметка очистки недоступного secure",
            mapOf(OfflineStoreReset.PENDING to true), plainLeft)
        assertTrue("паспорт читается после выхода из аккаунта", TripPassStore.load(ctx, 43) == null)
    }

    @Test
    fun `у каждого хранилища с мягким падением есть переезд и уборка открытого`() {
        val src = source("TripPass.kt")
        // Оба объекта в этом файле держат пару «шифрованное + открытое».
        assertTrue(
            "у паспортов поездок нет переноса из открытого хранилища в шифрованное: " +
                "данные, записанные при неподнявшемся Keystore, останутся открытыми навсегда",
            src.contains("PREF_PLAIN") && src.contains("plainPrefs"),
        )
        assertTrue(
            "очистка при выходе не трогает открытое хранилище — часть паспортов переживёт " +
                "выход из аккаунта и достанется следующему владельцу телефона",
            src.contains("OfflineStoreReset.clear(plain, securePrefs)") &&
                source("OfflineStoreReset.kt").contains("plain.edit().clear().putBoolean(PENDING, true).commit()"),
        )
        assertTrue(
            "у очереди исходящих пропал разовый перенос из открытого хранилища",
            src.contains("OfflineMigration.open(plain, available) { it == KEY }") &&
                source("OfflineMigration.kt").contains("remove(JOURNAL)"),
        )
    }
}
