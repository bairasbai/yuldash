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
        assertEquals(listOf("ColdTripPassportCoordinator.kt"), callers.map { it.name })
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
        val snapshots = File("app/src/main/java/com/yuldash/app/data/OfflineTripSnapshots.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/data/OfflineTripSnapshots.kt")
        assertTrue(snapshots.readText().substringAfter("suspend fun updateColdBoardingCode(")
            .contains("return saveColdPassport("))
        val store = File("app/src/main/java/com/yuldash/app/data/TripPass.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/data/TripPass.kt")
        val removal = store.readText().substringAfter("fun requestRemoval(")
            .substringBefore("fun remove(")
        assertTrue(removal.contains("cold == SnapshotPersistence.STALE"))
        assertTrue(removal.contains("cold == SnapshotPersistence.NOT_DURABLE"))
        assertTrue(removal.contains("cold == SnapshotPersistence.DURABLY_BLOCKED"))
    }

    @Test fun navigationDoesNotRetainPrivateColdPassport() {
        val navigation = File("app/src/main/java/com/yuldash/app/OfflineTripSnapshotNavigation.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/OfflineTripSnapshotNavigation.kt")
        assertTrue(navigation.isFile)
        val text = navigation.readText()
        assertFalse(text.contains("cold: ColdTripPassport"))
        assertFalse(text.contains("read.cold"))
        val screen = File("app/src/main/java/com/yuldash/app/OfflineActiveTripSnapshot.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/OfflineActiveTripSnapshot.kt")
        val mode = screen.readText().substringBefore("internal data class BookingNavigationRead")
        val read = screen.readText().substringAfter("internal data class BookingNavigationRead")
            .substringBefore("/** Cache failure")
        assertFalse(mode.contains("ColdTripPassport"))
        assertFalse(read.contains("ColdTripPassport"))
    }

    @Test fun coldPublicationsAreLimitedToIdleAndTheCurrentOperation() {
        val screen = File("app/src/main/java/com/yuldash/app/OfflineActiveTripSnapshot.kt")
            .takeIf { it.isFile } ?: File("src/main/java/com/yuldash/app/OfflineActiveTripSnapshot.kt")
        val load = screen.readText().substringAfter("LaunchedEffect(mode, version, resumed, operation)")
            .substringBefore("LaunchedEffect(displayed, displayedCold, resumed, operation)")
        assertTrue(load.contains("stage == ColdHideStage.IDLE"))
        assertTrue(load.contains("displayedCold = cold"))
        assertTrue(load.contains("cold.grant.rideId == mode.snapshot.rideId"))
        val result = screen.readText().substringAfter("LaunchedEffect(removal)")
            .substringBefore("LaunchedEffect(mode, version, resumed, operation)")
        assertTrue(result.contains("stage != ColdHideStage.RUNNING || !requestCurrent(request)"))
    }
}
