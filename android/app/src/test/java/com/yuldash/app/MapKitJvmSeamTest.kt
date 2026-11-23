package com.yuldash.app

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Регрессионный сторож шва «живая карта» (аудит 23.11.2026): на машине с ключом MapKit в
 * `local.properties` JVM-тесты создавали живой MapView и падали на проверке разрешений
 * (HomeBookingNavigationTest и др.), а в CI без ключа те же тесты шли зелёными. Шов
 * `mapKitKeyAvailable()` теперь под Robolectric всегда отвечает «живой карты нет» — как в CI.
 * Этот тест держит обе половины: детект Robolectric не ослеп, и шов закрыт независимо от ключа.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapKitJvmSeamTest {

    @Test
    fun robolectricNeverReportsLiveMap() {
        assertTrue(
            "Robolectric сменил дефолтный Build.FINGERPRINT — детект isJvmUiTest() в MapKitGate.kt ослеп, почини шов",
            "robolectric".equals(Build.FINGERPRINT, ignoreCase = true),
        )
        assertFalse(
            "Под JVM-тестами шов MapKit обязан показывать заглушку независимо от ключа в local.properties",
            mapKitKeyAvailable(),
        )
    }
}