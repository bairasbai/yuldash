package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaxiDriverAcceptRecoveryTest {
    private fun source(file: String): String {
        val name = "app/src/main/java/com/yuldash/app/$file"
        for (root in listOf(".", "..", "android", "../android")) {
            val candidate = File(root, name)
            if (candidate.isFile) return candidate.readText()
        }
        error("Cannot locate $name")
    }

    @Test
    fun `the driver offer defers expiry and polls the pending result before any new offer`() {
        val screen = source("InstantOrderScreen.kt")
        assertTrue(screen.contains("if (!canAccept && !accepting && !expiryHandled)"))
        assertTrue(screen.contains("ApiClient.instantAcceptOnce(current.id, generation)"))
        assertTrue(screen.contains("ApiClient.readDriverAcceptOnce(id, generation)"))
        assertTrue(screen.contains("if (accepting || pendingAccept != null) return@InstantOfferOverlay"))
        val api = source("data/ApiClient.kt").substringAfter("internal suspend fun instantAcceptOnce")
            .substringBefore("Водитель пропускает оффер")
        assertTrue(api.contains("retryOnNetwork = false, singleAttemptMutation = true"))
        assertTrue(api.contains("withoutAuthReplay = true"))
    }

    @Test
    fun `late active success must match order and authenticated driver`() {
        assertEquals(DriverAcceptRead.Active, classifyDriverAcceptRead(81, 17, 81, "driver", 17, "accepted"))
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, 82, "driver", 17, "accepted"))
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, 81, "passenger", 17, "accepted"))
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, 81, "driver", 18, "accepted"))
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, 81, "driver", null, "offered"))
    }

    @Test
    fun `unknown responses do not release the next offer`() {
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, null, null, null, null, 503))
        assertEquals(DriverAcceptRead.Unknown, classifyDriverAcceptRead(81, 17, null, null, null, null, 403, "Forbidden"))
        assertEquals(DriverAcceptRead.Closed, classifyDriverAcceptRead(81, 17, null, null, null, null, 403, "Заказға рөхсәт юҡ"))
        assertEquals(DriverAcceptRead.Closed, classifyDriverAcceptRead(81, 17, 81, "driver", null, "expired"))
    }
}
