package com.yuldash.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ColdTripPassportSourceGuardTest {
    private fun sources(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            val src = File(dir, "app/src/main/java/com/yuldash/app/data")
            if (src.isDirectory) return src.walkTopDown().filter { it.extension == "kt" }.toList()
            val nested = File(dir, "src/main/java/com/yuldash/app/data")
            if (nested.isDirectory) return nested.walkTopDown().filter { it.extension == "kt" }.toList()
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test fun secureColdCommitHasOnlyCoordinatorCallSiteAndGrantLastOrder() {
        val files = sources()
        assertTrue("Cold source guard could not locate production data sources", files.size > 20)
        val callers = files.filter { file -> file.readLines().any { it.contains("TripPassStore.commitSecure(") } }
        assertEquals(listOf("OfflineTripSnapshots.kt"), callers.map { it.name })
        val coordinator = callers.single().readText()
        val save = coordinator.substringAfter("suspend fun saveColdPassport(")
            .substringBefore("suspend fun loadColdPassport(")
        val begin = save.indexOf("beginColdSave(")
        val receipt = save.indexOf("TripPassStore.commitSecure(")
        val snapshot = save.indexOf("coldSave = true")
        val grant = save.indexOf("commitColdGrant(")
        assertTrue(begin >= 0 && receipt > begin && snapshot > receipt && grant > snapshot)
        assertFalse(save.contains("TripPassStore.save("))
        val load = coordinator.substringAfter("suspend fun loadColdPassport(")
            .substringBefore("fun canDrawCold(")
        assertTrue(load.contains("TripPassStore.readSecureCandidate("))
        assertFalse(load.contains("ApiClient."))
    }

    @Test fun productionSaveEntryPointsUseColdCoordinatorAndRemovalChecksItsResult() {
        val contacts = File("app/src/main/java/com/yuldash/app/RideshareTripContacts.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/RideshareTripContacts.kt")
        assertTrue(contacts.isFile)
        val source = contacts.readText()
        assertFalse(source.contains("TripPassStore.save("))
        assertFalse(source.contains("TripPassStore.updateBoardingCode("))
        assertTrue(source.contains("OfflineTripSnapshots.saveColdPassport("))
        assertTrue(source.contains("OfflineTripSnapshots.updateColdBoardingCode("))
        val store = File("app/src/main/java/com/yuldash/app/data/TripPass.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/data/TripPass.kt")
        val removal = store.readText().substringAfter("fun requestRemoval(")
            .substringBefore("fun remove(")
        assertTrue(removal.contains("cold == SnapshotPersistence.STALE"))
        assertTrue(removal.contains("cold == SnapshotPersistence.NOT_DURABLE"))
        assertTrue(removal.contains("cold == SnapshotPersistence.DURABLY_BLOCKED"))
    }
}
