package com.yuldash.app

import androidx.compose.runtime.snapshots.Snapshot

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The historical generator is preserved in legacy-navigation/capture-harness.kt.txt.
 * Replays observed Activity VM Bundle and a separately hosted App saveable registry; no OS upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacySavedStateCompatibilityTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val capture=System.getenv("YULDASH_LEGACY_CAPTURE_DIR")
    private val mounted=mutableStateOf(true)
    private lateinit var actor: org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var vm:YuldashViewModel
    private lateinit var registry:androidx.compose.runtime.saveable.SaveableStateRegistry
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Pair<String,String>>()
    private var kind=""
    private var target=0
    private var completedBooking=false
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        context.getSharedPreferences("yuldash_prefs",0).edit().clear().putBoolean("onboarding_completed",false).commit()
        NavSignals.openInstantChat.value=0;DeepLink.pendingRequestResponsesId.value=null
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse {
                    val path=r.requestUrl!!.encodedPath;records+=r.method!! to path
                    val body=when(path) {
                        "/bookings/mine" -> if(completedBooking) """{"items":[{"id":42,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400,"driver_name":"Водитель"}]}""" else """{"items":[]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"done","driver_phase":""}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                        "/notifications" -> """{"items":[{"id":901,"type":"$kind","title_ru":"Событие $target","title_ba":"Ваҡиға $target","ref_kind":"$kind","ref_id":$target,"read":true}]}"""
                        "/support/tickets/45" -> """{"id":45,"subject":"Обращение 45","status":"open","messages":[]}"""
                        else -> """{"items":[],"messages":[],"role":"passenger"}"""
                    }
                    return MockResponse().setHeader("Content-Type","application/json").setBody(body).setResponseCode(if(path=="/instant/orders/active")503 else 200)
                }
            };start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=1000
    }
    private fun pump() { repeat(8) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};compose.waitForIdle() }
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent { if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalSaveableStateRegistry provides registry) { YuldashTheme { YuldashApp() } } }
        pump()
    }
    private fun runFixture(name:String,destination:Screen,id:Int,rowKind:String) {
        kind=rowKind;target=id
        val state=if(capture==null)JSONObject(javaClass.getResource("/legacy-navigation/$name.json")!!.readText()) else null
        val bundle=state?.let {decode(it.getJSONObject("activity")) as Bundle}
        actor=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        @Suppress("UNCHECKED_CAST")
        val restored=state?.let {decode(it.getJSONObject("compose")) as Map<String,List<Any?>>}
        registry=SaveableStateRegistry(restored) {true}
        if(capture!=null) {vm.screen.value=Screen.Notifications;vm.language.value=AppLanguage.Ru;vm.persistNav()}
        mount()
        if(capture!=null) {
            if(destination==Screen.InstantChat) compose.runOnIdle {NavSignals.openInstantChat.value=id}
            else {
                compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText("Событие $id").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Событие $id").performClick()
            }
            pump()
            compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();vm.screen.value==destination};pump()
            assertEquals(destination,vm.screen.value)
            val observed=Bundle().also {actor.saveInstanceState(it)}
            val registryKey="androidx.lifecycle.BundlableSavedStateRegistry.key"
            val providerKey="androidx.lifecycle.internal.SavedStateHandlesProvider"
            val providers=observed.getBundle(registryKey)!!
            val saved=Bundle().apply {putBundle(registryKey,Bundle().apply {putBundle(providerKey,providers.getBundle(providerKey)!!)})}
            val output=JSONObject().put("source_commit","126bb0f63b3369ac92a7f891762011bb7f0820b1")
                .put("destination",destination.name).put("synthetic_owner",11).put("target",id)
                .put("activity_scope",providerKey)
                .put("excluded_activity_keys",JSONArray((observed.keySet()-registryKey).sorted()))
                .put("excluded_provider_keys",JSONArray((providers.keySet()-providerKey).sorted()))
                .put("activity",encode(saved)).put("compose",encode(registry.performSave()))
            File(capture,"$name.json").apply {parentFile.mkdirs();writeText(output.toString(2)+"\n")}
        } else {
            // Historical root IDs have no account binding and must not be guessed from an Int slot.
            assertEquals("Legacy destination without a durable ID must have a usable fallback",Screen.Notifications,vm.screen.value)
            compose.onNodeWithText("Уведомления").assertIsDisplayed()
            assertFalse(records.any {it.second.matches(Regex("/(instant/orders|support/tickets|incidents)/(0|$id)(/.*)?"))})
            assertFalse(records.any {it.first!="GET" && it.second!="/me/update"})
        }
    }
    @Test fun historicalTaxiChatHasSafeFallback()=runFixture("126-chat",Screen.InstantChat,43,"chat")
    @Test fun historicalSupportTicketHasSafeFallback()=runFixture("126-support",Screen.SupportTicket,45,"support")
    @Test fun historicalIncidentHasSafeFallback()=runFixture("126-incident",Screen.IncidentDetail,44,"incident")
    @Test fun nextAccountCannotRestoreHistoricalTaxiId() {
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        runFixture("126-chat",Screen.InstantChat,43,"chat")
    }
    @Test fun nextAccountCannotRestoreHistoricalSupportId() {
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        runFixture("126-support",Screen.SupportTicket,45,"support")
    }
    @Test fun nextAccountCannotRestoreHistoricalIncidentId() {
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        runFixture("126-incident",Screen.IncidentDetail,44,"incident")
    }
    private fun currentChatWithHistoricalRegistry(fresh:Boolean) {
        val fixture=JSONObject(javaClass.getResource("/legacy-navigation/126-chat.json")!!.readText())
        val intent=android.content.Intent(context,MainActivity::class.java)
        if(fresh) intent.putExtra("type","order_chat").putExtra("id","99").putExtra("recipient_user_id","11")
            .putExtra("yuldash_navigation_delivery_id","fresh-legacy-control")
        actor=Robolectric.buildActivity(MainActivity::class.java,intent).create(decode(fixture.getJSONObject("activity")) as Bundle)
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        @Suppress("UNCHECKED_CAST")
        val restored=decode(fixture.getJSONObject("compose")) as Map<String,List<Any?>>
        registry=SaveableStateRegistry(restored) {true}
        if(!fresh) {vm.instantChatOrderId.value=99;vm.screen.value=Screen.InstantChat;vm.persistNav()}
        mount()
        compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);records.any {it.second=="/instant/orders/99/messages"}}
        assertEquals(Screen.InstantChat,vm.screen.value);assertEquals(99,vm.instantChatOrderId.value)
        assertFalse(records.any {it.second=="/instant/orders/43/messages" || it.second=="/instant/orders/0/messages"})
        assertFalse(records.any {it.first!="GET" && it.second!="/me/update"})
    }
    @Test fun currentNamedIdWinsOverHistoricalNumericSlot()=currentChatWithHistoricalRegistry(false)
    @Test fun freshMarkedNotificationWinsOverLegacyFallback()=currentChatWithHistoricalRegistry(true)
    @Test fun observedFixturesRoundTripWithoutLosingTheirTypedValues() {
        for(name in listOf("126-chat","126-support","126-incident")) {
            val fixture=JSONObject(javaClass.getResource("/legacy-navigation/$name.json")!!.readText())
            for(part in listOf("activity","compose")) {
                val encoded=fixture.getJSONObject(part)
                assertEquals("Typed codec changed $name/$part",canonical(encoded),canonical(encode(decode(encoded))))
            }
        }
    }
    private fun canonical(value:Any?):String=when(value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}") {JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") {canonical(value.get(it))}
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun missingRoot(screen:Screen,fresh:Boolean=false) {
        val fixture=JSONObject(javaClass.getResource("/legacy-navigation/126-chat.json")!!.readText())
        actor=Robolectric.buildActivity(MainActivity::class.java).create(decode(fixture.getJSONObject("activity")) as Bundle)
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value=screen;vm.navPrev.value=screen;vm.navHistory.clear();vm.navHistory.addAll(listOf(Screen.Home,screen))
        @Suppress("UNCHECKED_CAST")
        val restored=decode(fixture.getJSONObject("compose")) as Map<String,List<Any?>>
        registry=SaveableStateRegistry(restored) {true}
        if(fresh) MainActivity::class.java.getDeclaredMethod("onNewIntent",android.content.Intent::class.java).apply {isAccessible=true}.invoke(
            actor.get(),android.content.Intent(context,MainActivity::class.java).putExtra("type","order_chat").putExtra("id","99")
                .putExtra("recipient_user_id","11").putExtra("yuldash_navigation_delivery_id","root-fresh-control"))
        mount()
        if(fresh) {
            compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();records.any {it.second=="/instant/orders/99/messages"}}
            assertEquals(Screen.InstantChat,vm.screen.value);assertEquals(99,vm.instantChatOrderId.value)
        } else {
            assertEquals(Screen.Notifications,vm.screen.value);assertEquals(Screen.Notifications,vm.navPrev.value)
            assertFalse(vm.navHistory.contains(screen));compose.onNodeWithText("Уведомления").assertIsDisplayed()
        }
        assertFalse(records.any {it.second.matches(Regex("/(trips|instant/orders|parcels|drivers)/0(/.*)?"))})
        assertFalse(records.any {it.first!="GET" && it.second!="/me/update"})
    }
    @Test fun missingLegacyReceiptContextDoesNotMountReceiptZero()=missingRoot(Screen.TripReceipt)
    @Test fun missingLegacyTaxiReceiptContextDoesNotMountReceiptZero()=missingRoot(Screen.TaxiReceipt)
    @Test fun missingLegacyParcelChatContextDoesNotMountChatZero()=missingRoot(Screen.ParcelChat)
    @Test fun missingLegacyDriverTripContextDoesNotMountOrderZero()=missingRoot(Screen.InstantDriverTrip)
    @Test fun missingLegacyDriverProfileDoesNotMountDriverZero()=missingRoot(Screen.DriverProfile)
    @Test fun missingLegacyPhotoModeDoesNotSelectTheWrongRole()=missingRoot(Screen.CarPhoto)
    @Test fun freshNotificationStillWinsWhenRootContextIsMissing()=missingRoot(Screen.TripReceipt,true)
    /** Actual production Saver generates the field packet; its target is synthetic schema input. */
    private fun taggedRoot(screen:Screen,field:String,id:Int,path:String,corruption:String?=null) {
        actor=Robolectric.buildActivity(MainActivity::class.java).create()
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value=Screen.Notifications;vm.persistNav();registry=SaveableStateRegistry(null) {true};mount()
        val saved=compose.runOnIdle {registry.performSave()}.toMutableMap()
        // Compose may store several providers under one implicit key. Keep all
        // other provider values and their ordering exactly as production saved them.
        val slot=saved.flatMap { (key,values) ->
            values.mapIndexedNotNull { index,value ->
                val packet=value as? List<*>
                if(packet?.getOrNull(1)==field) Triple(key,index,packet) else null
            }
        }.single()
        val record=slot.third.toMutableList()
        assertEquals(4,record.size);assertEquals(11,record[2]);record[3]=id
        when(corruption) {"version"->record[0]="unknown-version";"field"->record[1]="anotherField";"type"->record[3]=id.toString();"owner"->record[2]=12}
        val providers=saved.getValue(slot.first).toMutableList()
        providers[slot.second]=ArrayList(record)
        saved[slot.first]=providers
        compose.runOnIdle {mounted.value=false};pump()
        vm.screen.value=screen;vm.navPrev.value=screen;vm.persistNav()
        val bundle=Bundle().also {actor.saveInstanceState(it)};actor.destroy()
        actor=Robolectric.buildActivity(MainActivity::class.java).create(bundle);vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        registry=SaveableStateRegistry(saved) {true};records.clear()
        compose.runOnIdle {mounted.value=true};pump()
        if(corruption==null) {
            compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();records.any {it.second==path}}
            assertEquals(screen,vm.screen.value)
        } else {
            assertEquals(Screen.Notifications,vm.screen.value);assertFalse(records.any {it.second==path})
        }
        assertFalse(records.any {it.second.matches(Regex("/(trips|instant/orders|parcels|drivers)/0(/.*)?"))})
        assertFalse(records.any {it.first!="GET" && it.second!="/me/update"})
    }
    @Test fun currentTaggedReceiptIdRestoresForTheSameOwner()=taggedRoot(Screen.TripReceipt,"receiptBookingId",42,"/trips/42/receipt")
    @Test fun currentTaggedParcelIdRestoresForTheSameOwner()=taggedRoot(Screen.ParcelChat,"parcelChatId",43,"/parcels/43/messages")
    @Test fun currentTaggedProfileIdRestoresForTheSameOwner()=taggedRoot(Screen.DriverProfile,"driverProfileId",44,"/drivers/44/public")
    @Test fun unknownRootVersionCannotRestoreAReceiptId()=taggedRoot(Screen.TripReceipt,"receiptBookingId",42,"/trips/42/receipt","version")
    @Test fun anotherRootFieldCannotBecomeAReceiptId()=taggedRoot(Screen.TripReceipt,"receiptBookingId",42,"/trips/42/receipt","field")
    @Test fun wronglyTypedTaggedRootIdCannotBecomeAReceiptId()=taggedRoot(Screen.TripReceipt,"receiptBookingId",42,"/trips/42/receipt","type")
    @Test fun anotherRootOwnerCannotRestoreAReceiptId()=taggedRoot(Screen.TripReceipt,"receiptBookingId",42,"/trips/42/receipt","owner")
    @Test fun currentReceiptCallbackBeforeQueuedFallbackKeepsItsNewContext() = receiptCallbackBeforeQueuedFallback(false)
    @Test fun currentReceiptCallbackSurvivesLostGlobalSnapshotNotification() = receiptCallbackBeforeQueuedFallback(true)
    private fun receiptCallbackBeforeQueuedFallback(dropAutomaticNotification:Boolean) {
        completedBooking=true
        actor=Robolectric.buildActivity(MainActivity::class.java).create()
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.activeBookingId.value=42;vm.screen.value=Screen.ActiveTrip;vm.persistNav()
        registry=SaveableStateRegistry(null) {true}
        var armed=false;var calls=0;var receiptClick:(()->Boolean)?=null
        compose.mainClock.autoAdvance=false
        compose.setContent {
            if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalSaveableStateRegistry provides registry) {
                YuldashTheme {
                    // Earlier remember observer supplies the ID after composition,
                    // before the root fallback's LaunchedEffect observer is dispatched.
                    DisposableEffect(vm.screen.value) {
                        if(armed && vm.screen.value==Screen.TripReceipt) {
                            armed=false;calls++;assertTrue(receiptClick!!.invoke())
                        }
                        onDispose { }
                    }
                    YuldashApp()
                }
            }
        }
        compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(records.any {it.second=="/bookings/42/role"})
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        compose.onNodeWithText("Квитанция").assertIsDisplayed().assertIsEnabled()
        receiptClick=compose.onNodeWithText("Квитанция").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val checkCallback = {
            compose.runOnIdle {armed=true;vm.screen.value=Screen.TripReceipt};pump()
            assertEquals(1,calls)
            assertEquals("A callback that supplies a current ID must win over the queued missing-ID fallback",Screen.TripReceipt,vm.screen.value)
            compose.waitUntil(12000) {Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();records.any {it.second=="/trips/42/receipt"}}
            assertFalse(records.any {it.second=="/trips/0/receipt"})
            assertFalse(records.any {it.first!="GET" && it.second!="/me/update"})
        }
        if(dropAutomaticNotification) withLostGlobalSnapshotNotification(checkCallback) else checkCallback()
    }
    @After fun cleanup() {
        if(::actor.isInitialized) {compose.runOnIdle {mounted.value=false};pump();actor.destroy()}
        NavSignals.openInstantChat.value=0;DeepLink.pendingRequestResponsesId.value=null
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
    }
    private fun encode(value:Any?):JSONObject {
        val (type,data)=when(value) {
            null -> "null" to JSONObject.NULL
            is Bundle -> "bundle" to JSONObject().also {o->value.keySet().sorted().forEach {o.put(it,encode(value.get(it)))}}
            is MutableState<*> -> "state" to encode(value.value)
            is Screen -> "screen" to value.name
            is HomeTab -> "tab" to value.name
            is AppLanguage -> "language" to value.name
            is Map<*,*> -> "mapEntries" to JSONArray().also {a->value.forEach {(k,v)->a.put(JSONObject().put("key",encode(k)).put("value",encode(v)))}}
            is List<*> -> "list" to JSONArray().also {a->value.forEach {a.put(encode(it))}}
            is IntArray -> "ints" to JSONArray(value.toList())
            is String -> "string" to value
            is Int -> "int" to value
            is Long -> "long" to value
            is Float -> "floatBits" to value.toRawBits()
            is Double -> "doubleBits" to value.toRawBits()
            is Boolean -> "boolean" to value
            is android.os.Parcelable -> {
                require(value.javaClass.name=="androidx.compose.foundation.lazy.layout.DefaultLazyKey")
                val p=android.os.Parcel.obtain()
                try {value.writeToParcel(p,0);"lazyKeyParcel" to java.util.Base64.getEncoder().encodeToString(p.marshall())}
                finally {p.recycle()}
            }
            else -> error("Unsupported historical value ${value.javaClass.name}")
        }
        return JSONObject().put("type",type).put("value",data)
    }
    private fun decode(node:JSONObject):Any? {
        val type=node.getString("type")
        return when(type) {
            "null" -> null
            "string" -> node.getString("value")
            "int" -> node.getInt("value")
            "long" -> node.getLong("value")
            "floatBits" -> Float.fromBits(node.getInt("value"))
            "doubleBits" -> Double.fromBits(node.getLong("value"))
            "boolean" -> node.getBoolean("value")
            "screen" -> Screen.valueOf(node.getString("value"))
            "tab" -> HomeTab.valueOf(node.getString("value"))
            "language" -> AppLanguage.valueOf(node.getString("value"))
            "state" -> mutableStateOf(decode(node.getJSONObject("value")))
            "list" -> node.getJSONArray("value").let {a->ArrayList((0 until a.length()).map {decode(a.getJSONObject(it))})}
            "ints" -> node.getJSONArray("value").let {a->IntArray(a.length()){a.getInt(it)}}
            "mapEntries" -> node.getJSONArray("value").let {a->(0 until a.length()).associate {a.getJSONObject(it).let {decode(it.getJSONObject("key")) to decode(it.getJSONObject("value"))}}}
            "lazyKeyParcel" -> {
                val p=android.os.Parcel.obtain()
                try {
                    val bytes=java.util.Base64.getDecoder().decode(node.getString("value"));p.unmarshall(bytes,0,bytes.size);p.setDataPosition(0)
                    val creator=Class.forName("androidx.compose.foundation.lazy.layout.DefaultLazyKey").getDeclaredField("CREATOR").apply {isAccessible=true}.get(null) as android.os.Parcelable.Creator<*>
                    creator.createFromParcel(p)
                } finally {p.recycle()}
            }
            "map","bundle" -> node.getJSONObject("value").let {o->
                val map=o.keys().asSequence().associateWith {decode(o.getJSONObject(it))}
                if(type=="map")map else Bundle().also {b->map.forEach {(k,v)->when(v) {
                    null -> b.putString(k,null)
                    is Bundle -> b.putBundle(k,v)
                    is String -> b.putString(k,v)
                    is Int -> b.putInt(k,v)
                    is Long -> b.putLong(k,v)
                    is Boolean -> b.putBoolean(k,v)
                    is IntArray -> b.putIntArray(k,v)
                    is java.io.Serializable -> b.putSerializable(k,v)
                    else -> error("Unsupported Bundle value $k ${v.javaClass.name}")
                }}}
            }
            else -> error("Unknown fixture type $type")
        }
    }
}
