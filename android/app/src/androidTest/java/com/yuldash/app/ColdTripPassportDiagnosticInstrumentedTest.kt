package com.yuldash.app

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.Closeable
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolated synthetic hide checks; never logs private payload or credentials. */
@RunWith(AndroidJUnit4::class)
class ColdTripPassportDiagnosticInstrumentedTest {
    @Test fun inspectCurrentSyntheticBooking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("coldPassportDiagnostic")
        require(phase in setOf("read", "save", "remove")) { "Explicit QA phase required" }
        val bookingId = arguments.getString("coldPassportBooking")?.toIntOrNull() ?: error("Booking required")
        require(bookingId > 0)
        val context = instrumentation.targetContext.applicationContext
        ApiClient.init(context)
        OfflineTripSnapshots.init(context)
        TripPassStore.init(context)
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner()) { "Current durable owner unavailable" }
        val saveResult = if (phase == "save") {
            require(ApiClient.apiBase() == "http://127.0.0.1:8000") { "Only the isolated local API may be used" }
            ApiClient.getBookingDetails(bookingId).getOrThrow()
            ApiClient.getBoardingCode(bookingId).getOrThrow()
            val pass = requireNotNull(OfflineTripSnapshots.privatePass(bookingId))
            OfflineTripSnapshots.saveColdPassport(context, pass, expectedGeneration = ApiClient.queueSessionGeneration())
        } else null
        val removalResult = if (phase == "remove") {
            require(ApiClient.apiBase() == "http://127.0.0.1:8000") { "Only the isolated local API may be used" }
            TripPassStore.requestRemoval(context, bookingId, expectedGeneration = ApiClient.queueSessionGeneration())
        } else null
        val disk = SnapshotPreferencesDisk.open(context)
        val document = disk.read(owner.namespace)?.let(::JSONObject)
        val record = document?.getJSONObject("records")?.optJSONObject(bookingId.toString())
        val grant = record?.optJSONObject("cold_grant")
        val candidate = TripPassStore.readSecureCandidate(context, owner, bookingId)
        val restored = OfflineTripSnapshots.loadColdPassport(owner, bookingId)
        val snapshot = OfflineTripSnapshots.load(owner, bookingId)
        val barriers = listOf("scope" to 0, "booking" to bookingId, "cold-booking" to bookingId,
            "cold-scope" to 0, "cold-ride" to (record?.optInt("ride") ?: 0)).associate { (kind, id) ->
            kind to (disk.barrier(owner.barrier(kind, id)) != null)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("diagnostic_pid", Process.myPid())
            putInt("diagnostic_booking", bookingId)
            putString("save_result", saveResult?.name)
            putString("removal_result", removalResult?.name)
            putBoolean("owner_valid", owner.valid)
            putBoolean("document_present", document != null)
            putBoolean("record_present", record != null)
            putBoolean("grant_present", grant != null)
            putBoolean("secure_candidate_present", candidate != null)
            putBoolean("secure_candidate_canonical", candidate != null && candidate.raw == candidate.pass.toJson().toString())
            putBoolean("candidate_hash_matches_grant", candidate != null && candidate.payloadSha256 == grant?.optString("trippass_sha256"))
            putBoolean("cold_passport_restored", restored != null)
            putBoolean("cold_passport_allowed", restored?.let(OfflineTripSnapshots::canDrawCold) == true)
            putBoolean("ordinary_snapshot_restored", snapshot != null)
            putBoolean("live_allowed", OfflineTripSnapshots.allowsLive(bookingId))
            putBoolean("private_projection_present", OfflineTripSnapshots.privatePass(bookingId) != null)
            putString("closed_bookings", document?.getJSONArray("closed_bookings")?.toString())
            putString("closed_rides", document?.getJSONArray("closed_rides")?.toString())
            putString("barriers", JSONObject(barriers).toString())
            putString("grant_clock", grant?.getJSONObject("grant_stamp")?.toString())
            putString("snapshot_clock", record?.getJSONObject("state_stamp")?.toString())
            putString("current_clock", OfflineTripSnapshots.clock()?.let {
                JSONObject().put("boot", it.boot).put("wall", it.wall).put("before", it.before).put("after", it.after).toString()
            })
        })
    }

    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<ComponentActivity>()
    private var now = SnapshotClock(3, 1000, 1000, 1000)
    private val mounted = androidx.compose.runtime.mutableStateOf(false)

    private data class LocalHttp(val socket: ServerSocket, val requests: CopyOnWriteArrayList<String>, val thread: Thread,
        @Volatile var response: (String) -> String,
        val responses: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()) : Closeable {
        override fun close() {
            socket.close()
            thread.join(1000)
        }
    }

    private data class QaEnvironment(
        val owner: SnapshotOwner,
        val server: LocalHttp,
        val stem: String,
    ) {
        val snapshotMeta = "${stem}_meta"
        val snapshotSecure = "${stem}_snapshot"
        val passPlain = "${stem}_pass"
        val passSecure = "${stem}_pass_secure"
        val alias = "${stem}_key"
        val names = setOf(snapshotMeta, snapshotSecure, passPlain, passSecure)
        fun key(context: android.content.Context) = androidx.security.crypto.MasterKey.Builder(context, alias)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        fun disk(context: android.content.Context): SnapshotPreferencesDisk = SnapshotPreferencesDisk.open(
            context, SnapshotPreferenceFiles(snapshotMeta, snapshotSecure), ::key)
        fun initPasses(context: android.content.Context) {
            val plain = context.getSharedPreferences(passPlain, android.content.Context.MODE_PRIVATE)
            val secure = androidx.security.crypto.EncryptedSharedPreferences.create(
                context, passSecure, key(context),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            TripPassStore.initStores(plain, secure)
        }
    }

    private fun notFoundResponse() = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"

    private fun localHttp(port: Int = 0, response: (String) -> String = { notFoundResponse() }): LocalHttp {
        val socket = ServerSocket()
        socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 4)
        val requests = CopyOnWriteArrayList<String>()
        lateinit var server: LocalHttp
        val thread = Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        val line = client.getInputStream().bufferedReader().readLine()
                        if (line != null) {
                            requests += line
                            client.getOutputStream().write(server.response(line).toByteArray(Charsets.US_ASCII))
                            client.getOutputStream().flush()
                            server.responses += line
                        }
                    }
                }
            } catch (_: java.io.IOException) {
            }
        }.apply { isDaemon = true }
        server = LocalHttp(socket, requests, thread, response)
        thread.start()
        return server
    }

    private fun isolateAuthMemory() {
        listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "authStoreSource", "appCtx", "token",
            "refreshToken", "cachedUserId", "cachedUserIdForToken").forEach {
            ApiClient::class.java.getDeclaredField(it).apply { isAccessible = true }.set(ApiClient, null)
        }
    }

    private fun environment(): QaEnvironment {
        val server = localHttp()
        try {
            ApiClient.resetForTest()
            isolateAuthMemory()
            val origin = "http://127.0.0.1:${server.socket.localPort}"
            ApiClient.testBaseUrl = origin
            ApiClient.testTimeoutMs = 500
            check(ApiClient.apiBase() == origin) { "Only this test's local socket is allowed" }
            ApiClient.saveToken("e30." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"7\"}".toByteArray()) + ".synthetic")
            val owner = requireNotNull(OfflineTripSnapshots.currentOwner()) {
                "Synthetic login did not publish a durable owner; user=${ApiClient.myUserId()}, " +
                    "session=${ApiClient.isCurrentSession(ApiClient.queueSessionGeneration())}, base=${ApiClient.apiBase()}"
            }
            val stem = "rs_cold_hide_" + UUID.randomUUID().toString().replace("-", "")
            return QaEnvironment(owner, server, stem)
        } catch (failure: Throwable) {
            server.close()
            throw failure
        }
    }

    private fun proof(request: SnapshotReadTicket, owner: SnapshotOwner): SnapshotNetworkProof = SnapshotReadTrace().apply {
        attempt(); startWire(authorized = true); status(200)
    }.confirmed200(request, owner)!!

    private fun details(bookingId: Int, rideId: Int) = BookingDetailsDto(
        bookingId = bookingId, rideId = rideId, role = "passenger", status = "onboard", contactUnlocked = true,
        fromCity = "Уфа", toCity = "Сибай", departAt = "2030-01-01T10:00:00.000001", seats = 1, price = 200,
        payMethod = "cash", payAmount = 200, driverName = "QA Driver", driverVerified = true,
        driverPhone = "+70000000000", driverCar = "QA car", driverPlate = "QA-plate",
        pickup = "QA public stop", pickupLat = null, pickupLng = null,
        fromLat = null, fromLng = null, toLat = null, toLng = null, rideHasStops = true,
        meetLat = 55.1, meetLng = 55.2, meetNote = "QA private point", meetLandmarkRu = "QA RU", meetLandmarkBa = "QA BA",
        alightNote = "QA private dropoff", boardingUndo = BoardingUndoTerms(onboardAt = "2030-01-01T10:00:00.000001"),
    )

    private suspend fun prepareCold(context: android.content.Context, environment: QaEnvironment): ColdTripPassport {
        environment.initPasses(context)
        OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
        OfflineTripSnapshots.init(context)
        val owner = environment.owner
        OfflineTripSnapshots.activate(owner.userId, owner.scope, owner.generation)
        check(OfflineTripSnapshots.currentOwner() == owner)
        val details = details(911, 922)
        val detailsRead = requireNotNull(OfflineTripSnapshots.gate.beginRead(911, SnapshotAuthorityKind.DETAILS))
        check(OfflineTripSnapshots.acceptDetails(proof(detailsRead, owner), details))
        val projection = requireNotNull(OfflineTripSnapshots.projection(911))
        val codeRead = requireNotNull(OfflineTripSnapshots.gate.beginRead(911, SnapshotAuthorityKind.DETAILS))
        check(OfflineTripSnapshots.attachCode(projection, "4821", proof(codeRead, owner)))
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(911))
        check(OfflineTripSnapshots.saveColdPassport(context, pass, expectedGeneration = owner.generation) == SnapshotPersistence.COMMITTED)
        return requireNotNull(OfflineTripSnapshots.loadColdPassport(owner, 911))
    }

    private fun blockingDisk(
        actual: SnapshotDisk,
        started: CountDownLatch,
        release: CountDownLatch,
        writeStarted: CountDownLatch,
        releaseWrite: CountDownLatch,
        blockBarrierAt: Int = 1,
        failWrites: AtomicBoolean = AtomicBoolean(false),
    ) = object : SnapshotDisk {
        val reads = AtomicInteger(0)
        val blockAfter = AtomicInteger(Int.MAX_VALUE)
        val enabled = AtomicBoolean(false)
        val removalBarriers = AtomicInteger(0)
        override fun read(namespace: String): String? {
            if (reads.incrementAndGet() >= blockAfter.get() && enabled.compareAndSet(true, false)) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Cold read was not released" }
            }
            return actual.read(namespace)
        }
        override fun write(namespace: String, value: String) =
            if (failWrites.get()) false else actual.write(namespace, value)
        override fun barrier(key: String) = actual.barrier(key)
        override fun setBarrier(key: String, value: String?): Boolean {
            if (value == "blocked" && removalBarriers.incrementAndGet() == blockBarrierAt) {
                writeStarted.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Removal barrier was not released" }
            }
            return actual.setBarrier(key, value)
        }
        override fun retire(namespace: String) = actual.retire(namespace)
        override fun purgeExcept(namespace: String?) = actual.purgeExcept(namespace)
    }

    private fun mark(step: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("cold_hide_step", step)
        })
    }

    private fun show(text: String) {
        mark("show-wait")
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Known UI state did not appear in 10 seconds" }
            compose.mainClock.advanceTimeByFrame()
            android.os.SystemClock.sleep(20)
        }
        mark("show-found")
        fun displayed() = runCatching {
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }.isSuccess
        if (displayed()) {
            mark("show-displayed")
            return
        }
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        repeat(12) {
            if (displayed()) {
                mark("show-displayed")
                return
            }
            scroll.performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeByFrame()
            android.os.SystemClock.sleep(20)
        }
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        mark("show-displayed")
    }

    private fun assertNoPrivate() {
        compose.waitForIdle()
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)
        for (marker in listOf("4821", "QA-plate", "QA private point", "+70000000000", "200 ₽", "Наличными", "Наличный менән")) {
            compose.onAllNodes(hasText(marker, substring = true) and !hidden and !hasAnyAncestor(hidden),
                useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun screenshot(case: String): File {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val directory = requireNotNull(compose.activity.getExternalFilesDir(null))
        val screenshot = File(directory, "rs-cold-hide-20261108-$case.png")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            FileOutputStream(screenshot).use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        check(screenshot.isFile && screenshot.length() > 0)
        return screenshot
    }

    private fun clockJson(value: SnapshotClock?) = value?.let {
        JSONObject().put("boot", it.boot).put("wall", it.wall).put("before", it.before).put("after", it.after)
    } ?: JSONObject.NULL

    private fun snapshotDescriptor(value: OfflineTripSnapshot) = JSONObject()
        .put("user", value.owner.userId).put("scope", value.owner.scope)
        .put("generation", value.owner.generation).put("origin", value.owner.origin)
        .put("booking", value.bookingId).put("ride", value.rideId)
        .put("state", JSONObject().put("role", value.state.role).put("status", value.state.status)
            .put("phase", value.state.phase).put("max_onboard", value.state.maxOnboard ?: JSONObject.NULL)
            .put("corrected", value.state.corrected ?: JSONObject.NULL))
        .put("route", JSONObject().put("from", value.route?.from).put("to", value.route?.to)
            .put("depart_at", value.route?.departAt).put("pickup", value.route?.pickup ?: JSONObject.NULL)
            .put("dropoff", value.route?.dropoff ?: JSONObject.NULL).put("stops", value.route?.hasStops == true))
        .put("payload_stamp", clockJson(value.payloadStamp)).put("state_stamp", clockJson(value.stateStamp))
        .put("process", value.ticketProcess).put("state_ticket", value.stateTicket).put("payload_ticket", value.payloadTicket)

    private fun readClock(value: JSONObject) = SnapshotClock(value.getInt("boot"), value.getLong("wall"),
        value.getLong("before"), value.getLong("after"))

    private fun readSnapshot(value: JSONObject, owner: SnapshotOwner): OfflineTripSnapshot {
        val state = value.getJSONObject("state")
        val route = value.getJSONObject("route")
        fun nullable(source: JSONObject, key: String) = if (source.isNull(key)) null else source.getString(key)
        return OfflineTripSnapshot(owner, value.getInt("booking"), value.getInt("ride"),
            SnapshotEpochState(state.getString("role"), state.getString("status"), state.getString("phase"),
                nullable(state, "max_onboard"), nullable(state, "corrected")),
            SnapshotRoute(route.getString("from"), route.getString("to"), route.getString("depart_at"),
                nullable(route, "pickup"), nullable(route, "dropoff"), route.getBoolean("stops")),
            value.optJSONObject("payload_stamp")?.let(::readClock), readClock(value.getJSONObject("state_stamp")),
            value.getString("process"), value.getLong("state_ticket"), value.getLong("payload_ticket"))
    }

    private fun capture(case: String, environment: QaEnvironment, lateRead: Boolean, running: File? = null,
        restartSnapshot: OfflineTripSnapshot? = null, outcome: String = "CLEARED"): JSONObject {
        val owner = environment.owner
        val candidate = TripPassStore.readSecureCandidate(compose.activity, owner, 911)
        val restored = runBlocking { OfflineTripSnapshots.loadColdPassport(owner, 911) }
        check(restored == null) { "Cold passport must not be restored after hiding" }
        val directory = requireNotNull(compose.activity.getExternalFilesDir(null))
        val screenshot = screenshot(case)
        val result = JSONObject()
            .put("case", case).put("pid", Process.myPid()).put("booking", 911).put("ride", 922)
            .put("source", "synthetic_contract_fixture_not_http")
            .put("lateColdRestored", restored != null).put("hideConfirmed", outcome == "CLEARED")
            .put("outcome", outcome).put("lateReadBlocked", lateRead).put("secureCandidateAbsent", candidate == null)
            .put("httpRequests", org.json.JSONArray(environment.server.requests))
            .put("screenWidthDp", compose.activity.resources.configuration.screenWidthDp)
            .put("fontScale", compose.activity.resources.configuration.fontScale.toDouble())
            .put("screenshot", screenshot.absolutePath)
            .put("boundary", "Real Android Compose clock, isolated encrypted QA files and real save/removal; no hardware-backed proof")
        if (running != null) result.put("runningScreenshot", running.absolutePath)
        if (restartSnapshot != null) result.put("restart", snapshotDescriptor(restartSnapshot).put("stem", environment.stem))
        File(directory, "rs-cold-hide-20261108-$case.json").writeText(result.toString(2))
        return result
    }

    private fun cleanOwned(context: android.content.Context, environment: QaEnvironment, preserveForRestart: Boolean = false) {
        compose.runOnIdle { mounted.value = preserveForRestart }
        OfflineTripSnapshots.resetForTest()
        TripPassStore.initStores(context.getSharedPreferences(environment.passPlain, android.content.Context.MODE_PRIVATE), null)
        check(environment.names.size == 4 && environment.names.all { it.startsWith("rs_cold_hide_") })
        val leftovers = environment.names.flatMap { name ->
            if (!preserveForRestart) context.deleteSharedPreferences(name)
            listOf("$name.xml", "$name.xml.bak")
        }.map { File(context.applicationInfo.dataDir, "shared_prefs/$it") }.filter(File::exists)
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(environment.alias.startsWith("rs_cold_hide_"))
        if (!preserveForRestart && keys.containsAlias(environment.alias)) keys.deleteEntry(environment.alias)
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        isolateAuthMemory()
        environment.server.close()
        if (preserveForRestart) return
        check(leftovers.isEmpty()) { "QA preference files remain" }
        check(!keys.containsAlias(environment.alias)) { "QA key alias remains" }
    }

    private suspend fun exerciseHide(case: String, language: AppLanguage, dark: Boolean, lateRead: Boolean,
        preserveForRestart: Boolean = false, defer: Boolean = false,
        parentRole: Pair<CountDownLatch, CountDownLatch>? = null): JSONObject {
        mark("exercise-entry")
        val environment = environment()
        mark("environment-created")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val previousAutoAdvance = compose.mainClock.autoAdvance
        val previousDarkOverride = ThemePrefs.darkOverride
        ThemePrefs.darkOverride = dark
        if (lateRead) compose.mainClock.autoAdvance = false
        now = SnapshotClock(3, 1000, 1000, 1000)
        try {
            mark("context-requested")
            val context = compose.activity.applicationContext
            mark("context-received")
            val saved = prepareCold(context, environment)
            mark("cold-prepared")
            val failWrites = AtomicBoolean(defer)
            val blocking = blockingDisk(environment.disk(context), started, release, writeStarted, releaseWrite,
                blockBarrierAt = if (lateRead) 2 else 1, failWrites = failWrites)
            if (lateRead) {
                val nearExpiry = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS - 5_000
                now = now.copy(wall = nearExpiry, before = nearExpiry, after = nearExpiry)
            }
            OfflineTripSnapshots.installForTest(blocking) { now }
            OfflineTripSnapshots.activate(environment.owner.userId, environment.owner.scope, environment.owner.generation)
            OfflineTripSnapshots.init(context)
            val mode = OfflineTripSnapshotMode(saved.snapshot, 0)
            val vm = parentRole?.let {
                YuldashViewModel(androidx.lifecycle.SavedStateHandle()).apply { openOfflineTrip(saved.snapshot, 0) }
            }
            if (parentRole != null) environment.server.response = { line ->
                if (line.startsWith("GET /bookings/911/role ")) {
                    parentRole.first.countDown()
                    check(parentRole.second.await(5, TimeUnit.SECONDS)) { "Parent ROLE was not released" }
                    "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                } else notFoundResponse()
            }
            mounted.value = true
            compose.setContent {
                if (mounted.value) YuldashTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalAppLanguage provides language) {
                        if (vm == null) OfflineActiveTripSnapshot(mode, isCurrent = { true }, onRetry = {}, onBack = {})
                        else GuardedActiveTripDestination(vm, ApiClient.queueSessionGeneration(), emptyList(), null, false,
                            onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {},
                            onTripStopped = {}, onOpenReceipt = {}, currentReceiptBookingId = { 0 })
                    }
                }
            }
            mark("content-mounted")
            val russian = language == AppLanguage.Ru
            show(if (russian) "Паспорт поездки" else "Сәфәр паспорты")
            val hide = if (russian) "Скрыть личные данные на этом устройстве" else "Шәхси мәғлүмәтте был ҡоротманан йәшереү"
            show(hide)
            if (parentRole != null) compose.waitUntil(5000) { parentRole.first.count == 0L }
            if (lateRead) {
                val expiryDelay = requireNotNull(saved.remaining(now))
                check(OfflineTripSnapshots.gate.beginColdRestore(environment.owner, 911) != null)
                blocking.blockAfter.set(blocking.reads.get() + 1)
                blocking.enabled.set(true)
                compose.mainClock.advanceTimeByFrame()
                val expired = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS
                now = SnapshotClock(3, expired, expired, expired)
                compose.mainClock.advanceTimeBy(expiryDelay + 1000)
                compose.waitForIdle()
                check(started.await(10, TimeUnit.SECONDS)) {
                    "Cold read did not start on Android; reads=${blocking.reads.get()}, expectedDelay=$expiryDelay"
                }
            }
            mark("read-blocked")
            compose.onNodeWithText(hide).performClick()
            mark("hide-clicked")
            compose.runOnIdle { }
            compose.mainClock.advanceTimeByFrame()
            assertNoPrivate()
            if (lateRead) release.countDown()
            compose.runOnIdle { }
            compose.mainClock.advanceTimeByFrame()
            check(writeStarted.await(5, TimeUnit.SECONDS)) {
                "Removal did not start on Android; barriers=${blocking.removalBarriers.get()}"
            }
            mark("removal-blocked")
            show(if (russian) "Скрываем личные данные…" else "Шәхси мәғлүмәтте йәшерәбеҙ…")
            assertNoPrivate()
            val running = if (lateRead) screenshot("ru-light-running") else null
            releaseWrite.countDown()
            if (defer) {
                show("Доступ к личным данным закрыт. Очистка не завершена.")
                show("Приложение повторит очистку при следующем запуске.")
                compose.onAllNodesWithText("Приложение завершит очистку при следующем запуске.").assertCountEquals(0)
                assertNoPrivate()
                assertTrue(environment.server.requests.isEmpty())
                assertEquals(SnapshotPersistence.DURABLY_BLOCKED, OfflineTripSnapshots.persistence.value)
                val result = capture("deferred", environment, lateRead = false, outcome = "DEFERRED")
                    .put("persistence", OfflineTripSnapshots.persistence.value.name)
                failWrites.set(false)
                compose.onNodeWithText("Завершить очистку").performClick()
                compose.runOnIdle { }
                compose.mainClock.advanceTimeByFrame()
                show("Личные данные скрыты на этом устройстве")
                assertNoPrivate()
                result.put("hideConfirmed", true).put("retryOutcome", "CLEARED")
                    .put("retryPersistence", OfflineTripSnapshots.persistence.value.name)
                File(requireNotNull(context.getExternalFilesDir(null)),
                    "rs-cold-hide-20261108-deferred.json").writeText(result.toString(2))
                return result
            }
            show(if (russian) "Личные данные скрыты на этом устройстве" else "Шәхси мәғлүмәт был ҡоротмала йәшерелде")
            assertNoPrivate()
            assertNull(TripPassStore.readSecureCandidate(context, environment.owner, 911))
            assertNull(OfflineTripSnapshots.loadColdPassport(environment.owner, 911))
            if (parentRole == null) {
                assertTrue(environment.server.requests.isEmpty())
            } else {
                assertEquals("Only the pre-hide ROLE request is allowed", 1, environment.server.requests.size)
                parentRole.second.countDown()
                compose.waitUntil(5000) { environment.server.responses.any { it.startsWith("GET /bookings/911/role ") } }
                compose.waitForIdle()
                withContext(Dispatchers.IO) { Thread.sleep(250) }
                compose.waitForIdle()
                assertTrue("Parent must not fetch DETAILS after hiding",
                    environment.server.requests.none { it.startsWith("GET /bookings/911/details ") })
            }
            if (case == "ba-narrow-large") {
                assertTrue(compose.activity.resources.configuration.screenWidthDp <= 340)
                assertTrue(compose.activity.resources.configuration.fontScale >= 1.29f)
            }
            mark("assertions-passed")
            return capture(case, environment, lateRead, running,
                restartSnapshot = saved.snapshot.takeIf { preserveForRestart }).apply {
                if (parentRole != null) {
                    put("parentPath", "GuardedActiveTripDestination").put("preHideRoleRequests", 1)
                    File(requireNotNull(context.getExternalFilesDir(null)),
                        "rs-cold-hide-20261108-$case.json").writeText(toString(2))
                }
            }
        } finally {
            parentRole?.second?.countDown()
            release.countDown()
            releaseWrite.countDown()
            cleanOwned(compose.activity.applicationContext, environment, preserveForRestart)
            compose.mainClock.autoAdvance = previousAutoAdvance
            ThemePrefs.darkOverride = previousDarkOverride
        }
    }

    @Test fun parentDelayedRoleCannotFetchAfterHide() = runBlocking {
        captureResult(exerciseHide("parent-role-delayed", AppLanguage.Ru, dark = false, lateRead = false,
            parentRole = CountDownLatch(1) to CountDownLatch(1)))
    }

    @Test fun parentGateBeforePublishSendsNoRequests() = runBlocking {
        val environment = environment()
        try {
            val context = compose.activity.applicationContext
            OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
            OfflineTripSnapshots.activate(environment.owner.userId, environment.owner.scope, environment.owner.generation)
            OfflineTripSnapshots.init(context)
            val gate = ParentTransportGate()
            gate.close()
            environment.server.response = {
                "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
            }
            val result = runCatching { readBookingNavigation(911, singleAttemptTransport = true, transportSlot = gate) }
            val report = transportResult("gate-before-publish", environment)
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(environment.server.requests.isEmpty())
            assertNull(TripPassStore.readSecureCandidate(context, environment.owner, 911))
            assertNull(OfflineTripSnapshots.loadColdPassport(environment.owner, 911))
            File(requireNotNull(context.getExternalFilesDir(null)), "rs-cold-hide-20261108-gate-before-publish.json")
                .writeText(report.toString(2))
            captureResult(report)
        } finally {
            cleanOwned(compose.activity.applicationContext, environment)
        }
    }

    @Test fun parentGateBeforeRefreshSendsNoRoleOrRefresh() = runBlocking {
        val environment = environment()
        try {
            val context = compose.activity.applicationContext
            OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
            OfflineTripSnapshots.activate(environment.owner.userId, environment.owner.scope, environment.owner.generation)
            OfflineTripSnapshots.init(context)
            val gate = ParentTransportGate()
            val authority = object : com.yuldash.app.data.SnapshotTransportAuthority {
                override val gate: com.yuldash.app.data.SnapshotAuthorityGate
                    get() = OfflineTripSnapshots.gate
                override fun currentOwner(): com.yuldash.app.data.SnapshotOwner? = OfflineTripSnapshots.currentOwner()
                override fun cacheEnabled(owner: com.yuldash.app.data.SnapshotOwner): Boolean =
                    OfflineTripSnapshots.cacheEnabled(owner)
                override fun revoked(owner: com.yuldash.app.data.SnapshotOwner, bookingId: Int) {
                    gate.close()
                    OfflineTripSnapshots.revoked(owner, bookingId)
                }
                override suspend fun coldDenied(ticket: com.yuldash.app.data.SnapshotTicket, bookingId: Int) =
                    OfflineTripSnapshots.coldDenied(ticket, bookingId)
                override suspend fun close(ticket: com.yuldash.app.data.SnapshotTicket?, bookingId: Int) =
                    OfflineTripSnapshots.close(ticket, bookingId)
            }
            environment.server.response = { line ->
                when {
                    line.startsWith("GET /bookings/911/role ") -> {
                        if (environment.server.requests.count { it.startsWith("GET /bookings/911/role ") } == 1) {
                            "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        } else {
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        }
                    }
                    line.startsWith("POST /auth/refresh ") ->
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 82\r\nConnection: close\r\n\r\n{\"access_token\":\"qa-native\",\"token\":\"qa-native\",\"refresh_token\":\"qa-native-refresh\"}"
                    else -> notFoundResponse()
                }
            }
            val token = "e30." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"7\"}".toByteArray()) + ".synthetic"
            val prefs = context.getSharedPreferences(environment.passPlain, android.content.Context.MODE_PRIVATE)
            check(prefs.edit().putString("token", token).putString("refresh_token", "qa-native-refresh").commit())
            ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(ApiClient, prefs)
            ApiClient::class.java.getDeclaredField("refreshToken").apply { isAccessible = true }.set(ApiClient, "qa-native-refresh")
            val read = SnapshotTransportRead(911, SnapshotAuthorityKind.ROLE,
                ApiClient.queueSessionGeneration(), authority)
            assertNotNull("ROLE must have current authority before the request", read.ticket)
            val result = runCatching {
                ApiClient.readTripSnapshotJson("/bookings/911/role", read,
                    singleAttemptTransport = true, transportSlot = gate)
            }
            val report = transportResult("gate-before-refresh", environment)
            val failure = result.exceptionOrNull() ?: result.getOrNull()?.exceptionOrNull()
            assertTrue("ROLE must be cancelled before refresh: $result",
                failure is kotlinx.coroutines.CancellationException || failure is java.io.IOException)
            assertEquals(1, environment.server.requests.count { it.startsWith("GET /bookings/911/role ") })
            assertEquals(0, environment.server.requests.count { it.startsWith("POST /auth/refresh ") })
            assertTrue(runCatching { gate.ensureOpen() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            File(requireNotNull(context.getExternalFilesDir(null)), "rs-cold-hide-20261108-gate-before-refresh.json")
                .writeText(report.toString(2))
            captureResult(report)
        } finally {
            cleanOwned(compose.activity.applicationContext, environment)
        }
    }

    @Test fun parentDetailsBeforePublishSendsNoRequests() = runBlocking {
        val environment = environment()
        try {
            val context = compose.activity.applicationContext
            environment.initPasses(context)
            OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
            OfflineTripSnapshots.activate(environment.owner.userId, environment.owner.scope, environment.owner.generation)
            OfflineTripSnapshots.init(context)
            val gate = ParentTransportGate()
            gate.close()
            val result = runCatching { ApiClient.getBookingDetails(911, singleAttemptTransport = true, transportSlot = gate) }
            val report = transportResult("gate-details-before-publish", environment)
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(environment.server.requests.isEmpty())
            File(requireNotNull(context.getExternalFilesDir(null)), "rs-cold-hide-20261108-gate-details-before-publish.json")
                .writeText(report.toString(2))
            captureResult(report)
        } finally {
            cleanOwned(compose.activity.applicationContext, environment)
        }
    }

    @Test fun parentDetailsCloseCancelsActiveCall() = runBlocking {
        val environment = environment()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val traces = CopyOnWriteArrayList<String>()
        try {
            val context = compose.activity.applicationContext
            environment.initPasses(context)
            OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
            OfflineTripSnapshots.activate(environment.owner.userId, environment.owner.scope, environment.owner.generation)
            OfflineTripSnapshots.init(context)
            ApiClient.testTimeoutMs = 10000
            ApiClient.testTrace = { traces += it }
            environment.server.response = { line ->
                if (line.startsWith("GET /bookings/911/details ")) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "DETAILS was not released" }
                    "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                } else notFoundResponse()
            }
            val gate = ParentTransportGate()
            val read = async(Dispatchers.Default) {
                ApiClient.getBookingDetails(911, singleAttemptTransport = true, transportSlot = gate)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            gate.close()
            release.countDown()
            kotlinx.coroutines.withTimeout(10000) { read.join() }
            compose.waitUntil(5000) { environment.server.responses.any { it.startsWith("GET /bookings/911/details ") } }
            withContext(Dispatchers.IO) { Thread.sleep(250) }
            val report = transportResult("gate-details-close", environment)
            val result = read.await()
            assertTrue("DETAILS must be cancelled after gate close: $result; $traces",
                result.exceptionOrNull() is java.io.IOException)
            assertEquals(1, environment.server.requests.count { it.startsWith("GET /bookings/911/details ") })
            File(requireNotNull(context.getExternalFilesDir(null)), "rs-cold-hide-20261108-gate-details-close.json")
                .writeText(report.toString(2))
            captureResult(report)
        } finally {
            release.countDown()
            ApiClient.testTrace = null
            cleanOwned(compose.activity.applicationContext, environment)
        }
    }

    private suspend fun transportResult(case: String, environment: QaEnvironment): JSONObject = JSONObject()
        .put("case", case).put("pid", Process.myPid()).put("source", "synthetic_contract_fixture_not_http")
        .put("outcome", "BLOCKED").put("hideConfirmed", false)
        .put("lateColdRestored", OfflineTripSnapshots.loadColdPassport(environment.owner, 911) != null)
        .put("secureCandidateAbsent", TripPassStore.readSecureCandidate(compose.activity.applicationContext, environment.owner, 911) == null)
        .put("httpRequests", org.json.JSONArray(environment.server.requests))
        .put("boundary", "Transport-only ParentTransportGate on Android; not a UI deletion or hardware-backed proof")

    @Test fun lateColdLoadCannotRepublishAfterHide() = runBlocking {
        captureResult(exerciseHide("ru-light", AppLanguage.Ru, dark = false, lateRead = true))
    }

    @Test fun hiddenPassportVisual() = runBlocking {
        val case = requireNotNull(InstrumentationRegistry.getArguments().getString("coldHideCase")) {
            "Explicit visual case required"
        }
        val language = if (case.startsWith("ba")) AppLanguage.Ba else AppLanguage.Ru
        captureResult(exerciseHide(case, language, dark = case.endsWith("dark"), lateRead = false))
    }

    @Test fun hiddenDeferredRetriesLocally() = runBlocking {
        captureResult(exerciseHide("deferred", AppLanguage.Ru, dark = false, lateRead = false, defer = true))
    }

    @Test fun hiddenPassportRestartPrepare() = runBlocking<Unit> {
        captureResult(exerciseHide("restart-prepare", AppLanguage.Ru, dark = false, lateRead = false,
            preserveForRestart = true))
        kotlinx.coroutines.awaitCancellation()
    }

    @Test fun hiddenPassportRestartVerify() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val previousPid = arguments.getString("coldHidePreviousPid")?.toIntOrNull()
            ?: error("Previous process id is required")
        assertNotEquals("Root must actually restart the target process", previousPid, Process.myPid())
        val context = compose.activity.applicationContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val descriptor = JSONObject(File(directory, "rs-cold-hide-20261108-restart-prepare.json").readText())
        check(descriptor.getInt("pid") == previousPid)
        val restart = descriptor.getJSONObject("restart")
        val owner = SnapshotOwner(restart.getInt("user"), restart.getString("scope"),
            restart.getLong("generation"), restart.getString("origin"))
        val stem = restart.getString("stem")
        check(stem.matches(Regex("rs_cold_hide_[0-9a-f]{32}")))
        check(owner.valid && owner.origin.startsWith("http://127.0.0.1:"))
        val environment = QaEnvironment(owner, localHttp(java.net.URI(owner.origin).port), stem)
        val previousDarkOverride = ThemePrefs.darkOverride
        ThemePrefs.darkOverride = false
        now = SnapshotClock(3, 1000, 1000, 1000)
        try {
            ApiClient.resetForTest()
            isolateAuthMemory()
            ApiClient.testBaseUrl = owner.origin
            ApiClient.testTimeoutMs = 500
            check(ApiClient.apiBase() == owner.origin)
            ApiClient::class.java.getDeclaredField("sessionGeneration").apply { isAccessible = true }
                .setLong(ApiClient, owner.generation)
            ApiClient::class.java.getDeclaredField("token").apply { isAccessible = true }.set(ApiClient,
                "e30." + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":\"7\"}".toByteArray()) + ".synthetic")
            environment.initPasses(context)
            OfflineTripSnapshots.installForTest(environment.disk(context)) { now }
            OfflineTripSnapshots.init(context)
            OfflineTripSnapshotHooks.newSession(owner.userId, owner.scope, owner.generation)
            check(OfflineTripSnapshots.currentOwner() == owner)
            assertNull(TripPassStore.readSecureCandidate(context, owner, 911))
            assertNull(OfflineTripSnapshots.loadColdPassport(owner, 911))
            val mode = OfflineTripSnapshotMode(readSnapshot(restart, owner), 0)
            mounted.value = true
            compose.setContent {
                if (mounted.value) YuldashTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                        OfflineActiveTripSnapshot(mode, isCurrent = { true }, onRetry = {}, onBack = {})
                    }
                }
            }
            show("Сохранённые данные устарели или недоступны")
            assertNoPrivate()
            assertTrue(environment.server.requests.isEmpty())
            val result = capture("restart-verified", environment, lateRead = false)
                .put("previousPid", previousPid).put("actualProcessRestart", true)
                .put("processBoundary", "adb_force_stop_and_new_pid_synthetic_session_not_http_login")
                .put("clock", "injected_test_clock")
            File(directory, "rs-cold-hide-20261108-restart-verified.json").writeText(result.toString(2))
            captureResult(result)
        } finally {
            cleanOwned(context, environment)
            ThemePrefs.darkOverride = previousDarkOverride
        }
    }

    @Test fun coldHideQaCleanup() {
        val action = InstrumentationRegistry.getArguments().getString("coldHideQaCleanup")
        check(action == "inspect" || action == "delete") { "Explicit inspect/delete QA cleanup required" }
        val context = compose.activity.applicationContext
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = store.aliases().toList().filter { it.startsWith("rs_cold_hide_") }.sorted()
        val preferences = File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.name.startsWith("rs_cold_hide_") }?.sortedBy { it.name }.orEmpty()
        val external = requireNotNull(context.getExternalFilesDir(null)).listFiles()
            ?.filter { it.name.startsWith("rs-cold-hide-20261108-") }?.sortedBy { it.name }.orEmpty()
        val cacheHelper = File(context.cacheDir, "rs_cold_hide_cleanup.dex")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("cold_hide_cleanup", JSONObject().put("action", action).put("aliases", aliases.size)
                .put("preferences", preferences.size).put("external", external.size)
                .put("temporaryHelper", cacheHelper.exists()).toString())
        })
        if (action == "delete") {
            aliases.forEach(store::deleteEntry)
            preferences.forEach { file ->
                check(file.isFile && (file.name.endsWith(".xml") || file.name.endsWith(".xml.bak")))
                if (file.name.endsWith(".xml")) context.deleteSharedPreferences(file.name.removeSuffix(".xml"))
                if (file.exists()) check(file.delete())
            }
            external.forEach { file -> check(file.isFile && file.delete()) }
            if (cacheHelper.exists()) check(cacheHelper.delete())
        }
        assertTrue(store.aliases().toList().none { it.startsWith("rs_cold_hide_") } || action == "inspect")
        assertTrue(preferences.none { it.exists() } || action == "inspect")
        assertTrue(external.none { it.exists() } || action == "inspect")
    }

    private fun captureResult(result: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("cold_hide_result", result.toString())
        })
    }
}
