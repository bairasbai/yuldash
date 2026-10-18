package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.yuldash.app.data.RequestFeedDto
import com.yuldash.app.data.parseResponseDto
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerSegmentContentTest {
    @get:Rule val compose = createComposeRule()
    private fun ride() = Ride(id="7", from="Сибай", to="Акъяр", time="", driver="Водитель", car="", price=500,
        seats=3, rating=5.0, verified=true, boosted=false, waypoints=listOf("Ургаза", "Бурибай"))
    private fun request() = RequestFeedDto(id=5, passengerName="Пассажир", from="Ургаза", to="Бурибай", seats=1, comment="", responded=false,
        matchedRideId=7, matchedRideFromCity="Сибай", matchedRideToCity="Акъяр", matchedRideDepartAt="2030-01-02T10:00:00Z",
        matchedPickupStop="Ургаза", matchedDropoffStop="Бурибай", detourKm=42)

    @Test fun changingBoardingClearsBackwardDropoffAndRequiresNewExplicitChoice() {
        val r=ride(); val pickup=mutableStateOf<String?>(null); val dropoff=mutableStateOf<String?>(null)
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BookingSegmentPicker(r,pickup.value,dropoff.value,{ pickup.value=it;dropoff.value=keepValidDropoff(r,it,dropoff.value) },{dropoff.value=it})
            }
        } }
        compose.onNodeWithText("Сначала выбери место посадки.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ургаза").performScrollTo().performClick()
        compose.onAllNodesWithText("Бурибай").onLast().performScrollTo().performClick()
        compose.runOnIdle { assertTrue(segmentReady(r,pickup.value,dropoff.value)) }
        compose.onAllNodesWithText("Бурибай").onFirst().performScrollTo().performClick()
        compose.runOnIdle { assertNull(dropoff.value); assertFalse(segmentReady(r,pickup.value,dropoff.value)) }
        compose.onNodeWithText("Конец маршрута · Акъяр").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(segmentReady(r,pickup.value,dropoff.value)) }
    }

    @Test fun routeFilterUsesServerMatchEvenWhenDetourIsLarge() {
        val matched=request(); val unMatched=matched.copy(id=6, from="Чужой город", to="Другое место", matchedRideId=null, detourKm=1)
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            RequestsFeedContent(false,false,listOf(matched,unMatched),{}, {}, selectedFilter=RequestsFeedFilter.AlongTheWay)
        } }
        compose.onNodeWithText("Ургаза → Бурибай").assertExists()
        compose.onNodeWithText("Чужой город → Другое место").assertDoesNotExist()
    }

    @Test fun openingOfferDoesNotSubmitAndRequiresExplicitBindingChoice() {
        val bind=mutableStateOf<Boolean?>(null); var sends=0
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            RequestOfferSheet(request(),"500","","",false,{},{},{},{sends++},bind.value,{bind.value=it})
        } }
        compose.runOnIdle { assertEquals(0,sends);assertFalse(requestOfferReady(request(),bind.value)) }
        compose.onNodeWithText("Отправить предложение").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Место в моём рейсе").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true,bind.value);assertEquals(0,sends) }
        compose.onNodeWithText("Отправить предложение").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1,sends) }
    }

    @Test fun passengerSeesSegmentAndParentRideBeforeAcceptAndUnknownLegacyIsNotEnd() {
        val response=parseResponseDto(JSONObject("""{"id":9,"ride_id":7,"ride_from_city":"Сибай","ride_to_city":"Акъяр","ride_depart_at":"2030-01-02T10:00:00Z","pickup_stop":"Ургаза","dropoff_stop":"Бурибай"}"""))
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            Column { BoundResponseRouteNote(response); DropoffStopNote(null,"Акъяр") }
        } }
        compose.onNodeWithText("Участок: Ургаза → Бурибай").assertExists()
        compose.onNodeWithText("Рейс: Сибай → Акъяр").assertExists()
        compose.onNodeWithText("Место высадки не указано — уточни в чате").assertExists()
        compose.onNodeWithText("Высадка: конец маршрута · Акъяр").assertDoesNotExist()
    }

    @Test fun bashkirRouteEmptyDistinguishesNoOwnRides() {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
            RequestsFeedContent(false,false,emptyList(),{},{},selectedFilter=RequestsFeedFilter.AlongTheWay,hasOwnRides=false)
        } }
        compose.onNodeWithText("Баҫтырылған сәфәрҙәр юҡ").assertExists()
    }
}
