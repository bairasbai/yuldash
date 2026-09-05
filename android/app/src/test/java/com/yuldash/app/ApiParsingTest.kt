package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.AdminAdDto
import com.yuldash.app.data.AdminAdsDto
import com.yuldash.app.data.AdDto
import com.yuldash.app.data.AdPackageDto
import com.yuldash.app.data.AdStatsDto
import com.yuldash.app.data.BlockDto
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.BookingMineDto
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.BoostResultDto
import com.yuldash.app.data.ContactDto
import com.yuldash.app.data.ConversationDto
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.DriverStatusDto
import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.MyAdDto
import com.yuldash.app.data.NearbyPage
import com.yuldash.app.data.NotifDto
import com.yuldash.app.data.PaymentsSummaryDto
import com.yuldash.app.data.PendingDriverDto
import com.yuldash.app.data.PendingPaymentDto
import com.yuldash.app.data.PopularRouteDto
import com.yuldash.app.data.PriceHintDto
import com.yuldash.app.data.ReferralDto
import com.yuldash.app.data.ReportableUserDto
import com.yuldash.app.data.RequestDto
import com.yuldash.app.data.RequestFeedDto
import com.yuldash.app.data.RequestNearDto
import com.yuldash.app.data.ResponseDto
import com.yuldash.app.data.ReviewItem
import com.yuldash.app.data.RideDto
import com.yuldash.app.data.TripStateDto
import com.yuldash.app.data.parseAdDto
import com.yuldash.app.data.parseAdminAdDto
import com.yuldash.app.data.parseAdminReportDto
import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.parseBookingMineDto
import com.yuldash.app.data.parseBoostResultDto
import com.yuldash.app.data.parseContactDto
import com.yuldash.app.data.parseConversationDto
import com.yuldash.app.data.parseDriverBookingDto
import com.yuldash.app.data.parseDriverStatusDto
import com.yuldash.app.data.parseFeedDto
import com.yuldash.app.data.parseMessageDto
import com.yuldash.app.data.parseNotifDto
import com.yuldash.app.data.parsePendingDriverDto
import com.yuldash.app.data.parsePendingPaymentDto
import com.yuldash.app.data.parsePaymentsSummaryDto
import com.yuldash.app.data.parsePopularRouteDto
import com.yuldash.app.data.parseReportableUserDto
import com.yuldash.app.data.parseRequestDto
import com.yuldash.app.data.parseRequestFeedDto
import com.yuldash.app.data.parseResponseDto
import com.yuldash.app.data.parseReviewItem
import com.yuldash.app.data.toRequestNearDto
import com.yuldash.app.data.toRideDto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiParsingTest {

    @Test
    fun parseMessageDto_readsTextMessageFlagsAndSender() {
        val dto = parseMessageDto(
            JSONObject()
                .put("id", 91)
                .put("text", "hello")
                .put("sender_id", 17)
                .put("deleted", true)
                .put("edited", true)
        )

        assertEquals(MessageDto(91, "hello", 17, deleted = true, edited = true), dto)
        assertNull(dto.voiceUrl)
    }

    @Test
    fun parseMessageDto_keepsRealVoiceUrlButDropsNullLikeValues() {
        assertEquals(
            "https://yulbash.ru/media/voice.m4a",
            parseMessageDto(JSONObject().put("id", 1).put("sender_id", 2).put("voice_url", "https://yulbash.ru/media/voice.m4a")).voiceUrl,
        )
        assertNull(parseMessageDto(JSONObject().put("id", 1).put("sender_id", 2).put("voice_url", JSONObject.NULL)).voiceUrl)
        assertNull(parseMessageDto(JSONObject().put("id", 1).put("sender_id", 2).put("voice_url", "")).voiceUrl)
        assertNull(parseMessageDto(JSONObject().put("id", 1).put("sender_id", 2).put("voice_url", "null")).voiceUrl)
    }

    @Test
    fun apiDtoContracts_keepServerFieldsStable() {
        val ride = RideDto(
            id = 5,
            fromCity = "Baymak",
            toCity = "Sibay",
            departAt = "2026-07-03T12:30:00",
            seatsTotal = 4,
            seatsLeft = 3,
            price = 350,
            category = "regular",
            driverName = "Driver",
            driverRating = 4.8,
            driverVerified = true,
            driverCar = "Kia",
            distanceKm = 42.4,
            boosted = true,
        )
        val page = NearbyPage(items = listOf(ride), total = 9)

        assertEquals(5, ride.id)
        assertTrue(ride.driverVerified)
        assertEquals(42.4, ride.distanceKm!!, 0.0)
        assertTrue(ride.boosted)
        assertEquals(9, page.total)
        assertEquals(ride, page.items.single())
    }

    @Test
    fun bookingAndTripDtos_keepPrivateAndStatusFieldsSeparate() {
        val booking = BookingDetailsDto(
            bookingId = 7,
            rideId = 11,
            role = "passenger",
            status = "confirmed",
            contactUnlocked = true,
            fromCity = "A",
            toCity = "B",
            departAt = "2026-07-03T10:00:00",
            seats = 2,
            price = 800,
            driverName = "Driver",
            driverVerified = true,
            driverPhone = "+70000000000",
            driverCar = "Lada",
            pickup = "Hidden point",
            pickupLat = 53.1,
            pickupLng = 58.2,
            fromLat = 53.0,
            fromLng = 58.0,
            toLat = 54.0,
            toLng = 59.0,
        )
        val mine = BookingMineDto(7, 11, 2, 800, "pending", "", "A", "B", "2026-07-03T10:00:00", "Driver", false)
        val state = TripStateDto(role = "driver", status = "onboard", driverPhase = "arriving")

        assertTrue(booking.contactUnlocked)
        assertEquals("+70000000000", booking.driverPhone)
        assertEquals("pending", mine.status)
        assertEquals("arriving", state.driverPhase)
    }

    @Test
    fun requestAndSafetyDtos_coverOptionalFields() {
        val request = RequestDto(3, "A", "B", 1, "regular", true, 500, "comment", "Mom", "active", desiredAt = null)
        val nearby = RequestNearDto(3, "Passenger", "A", "B", null, null, 1, "", null)
        val feed = RequestFeedDto(3, "Passenger", "A", "B", 1, "comment", responded = false, prefs = listOf("child_seat"))
        val response = ResponseDto(4, 8, "Driver", null, 450, "", "pending")
        val contact = ContactDto(9, "Name", "Friend", "+7", true)
        val block = BlockDto(10, "Blocked")
        val reportable = ReportableUserDto(11, "Target")

        assertTrue(request.withKids)
        assertNull(request.desiredAt)
        assertNull(nearby.distanceKm)
        assertFalse(feed.responded)
        assertNull(response.driverRating)
        assertTrue(contact.notifyByDefault)
        assertEquals(10, block.blockedUserId)
        assertEquals(11, reportable.id)
    }

    @Test
    fun driverAndAdminDtos_keepModerationFields() {
        val driver = DriverStatusDto(
            docsStatus = "pending",
            verified = false,
            carMake = "Lada",
            carModel = "Vesta",
            carColor = "white",
            carPlate = "A001AA",
            seats = 4,
            licenseUrl = "/secure/docs/license.jpg",
            carPhotoUrl = "/secure/docs/car.jpg",
            online = true,
            autocheckResult = "needs_human",
            autocheckData = "{}",
        )
        val pending = PendingDriverDto(2, "Driver", "+7", "Lada Vesta", "license", "car", autocheckResult = "pass", autocheckScore = 0.93)
        val report = com.yuldash.app.data.AdminReportDto(1, "Reporter", "Target", "+7", "spam", "now")
        val review = ReviewItem(1, "Name", "City", 5, "Text")

        assertFalse(driver.verified)
        assertTrue(driver.online)
        assertEquals("pass", pending.autocheckResult)
        assertEquals(0.93, pending.autocheckScore, 0.0)
        assertEquals("spam", report.reason)
        assertEquals(5, review.stars)
    }

    @Test
    fun adsPaymentsAndDiscoveryDtos_keepMoneyInExpectedUnits() {
        val ad = AdDto("ad1", "Title", "Text", "Open", "erid", "profile", partner = "Partner", city = "Baymak")
        val stats = AdStatsDto(impressions = 200, clicks = 25)
        val admin = AdminAdsDto(
            founderUsed = 1,
            founderLimit = 10,
            items = listOf(AdminAdDto("ad1", "Partner", "Title", "Text", "premium", "active", "profile", "erid", null, live = true, expired = false)),
        )
        val mine = MyAdDto("ad1", "Title", "Text", "Open", "https://example.com", "erid", "draft", "", "city", "City", 100_000, 30, "profile", "Baymak", paid = false, submittedAt = null)
        val pkg = AdPackageDto("city", "City", "Ҡала", amountKop = 100_000, periodDays = 30)
        val payment = PendingPaymentDto(1, "ad", "city", 1000, null, "Payer", "+7", "now")
        val summary = PaymentsSummaryDto(donateCount = 2, donateSum = 500, boostCount = 3, boostSum = 900)
        val boost = BoostResultDto("pending", "sbp_manual", 1, 1000, null, "+7", "Bank", "Name")

        assertEquals("Baymak", ad.city)
        assertEquals(25, stats.clicks)
        assertTrue(admin.items.single().live)
        assertEquals(100_000, mine.budgetKop)
        assertEquals(100_000, pkg.amountKop)
        assertEquals(1000, payment.amount)
        assertEquals(1400, summary.donateSum + summary.boostSum)
        assertNull(boost.confirmationUrl)
    }

    @Test
    fun lightweightDtos_keepFeedReferralAndConversationContracts() {
        val feed = FeedDto(today = 1, week = 7, month = 30, year = 365, drivers = 12, topFrom = "A", topTo = "B", topCount = 5, donationsTotal = 1234)
        val route = PopularRouteDto("A", "B", 5)
        val referral = ReferralDto("ABC123", invited = 2, credits = 300, redeemed = true)
        val conversation = ConversationDto(bookingId = 8, peerName = "Peer", route = "A -> B", lastMessage = "Hi", departAt = null)
        // Уведомление приходит сразу на двух языках — по одному полю на язык.
        val notif = NotifDto(
            id = 1, type = "system",
            titleRu = "Заголовок", titleBa = "Баш",
            bodyRu = "Текст", bodyBa = "Текст BA",
            refKind = "", refId = null, read = false, createdAt = "2026-09-05T00:00:00Z",
        )
        val price = PriceHintDto(avg = 350, count = 4)
        val booking = DriverBookingDto(1, "Passenger", null, "A -> B", "pending")
        val boostPlan = BoostPlanDto("day", "One day", 99, 24)

        assertEquals(1234, feed.donationsTotal)
        assertEquals(5, route.count)
        assertTrue(referral.redeemed)
        assertNull(conversation.departAt)
        assertEquals("Заголовок", notif.titleRu)
        assertEquals("Баш", notif.titleBa)
        assertEquals(350, price.avg)
        assertNull(booking.passengerRating)
        assertEquals(24, boostPlan.hours)
    }

    @Test
    fun toRideDto_parsesFullRideJsonContract() {
        val dto = JSONObject()
            .put("id", 77)
            .put("from_city", "Baymak")
            .put("to_city", "Sibay")
            .put("depart_at", "2026-07-03T12:30:00")
            .put("seats_total", 4)
            .put("seats_left", 2)
            .put("price", 450)
            .put("category", "regular")
            .put("driver_name", "Driver")
            .put("driver_rating", 4.8)
            .put("driver_verified", true)
            .put("driver_car", "Kia Rio")
            .put("driver_avatar", "avatar.jpg")
            .put("driver_online", true)
            .put("pets_allowed", true)
            .put("child_seat", true)
            .put("women_only", true)
            .put("smoking", true)
            .put("baggage", true)
            .put("air_conditioner", true)
            .put("pickup", "Station")
            .put("pickup_lat", 52.1)
            .put("pickup_lng", 58.2)
            .put("distance_km", 3.4)
            .put("boosted", true)
            .put("receiver_name", "Receiver")
            .put("parcel_size", "small")
            .toRideDto()

        assertEquals(77, dto.id)
        assertEquals("Baymak", dto.fromCity)
        assertEquals("Sibay", dto.toCity)
        assertEquals(2, dto.seatsLeft)
        assertEquals(450, dto.price)
        assertEquals(4.8, dto.driverRating, 0.0)
        assertTrue(dto.driverVerified)
        assertTrue(dto.driverOnline)
        assertTrue(dto.petsAllowed)
        assertTrue(dto.childSeat)
        assertTrue(dto.womenOnly)
        assertTrue(dto.smoking)
        assertTrue(dto.baggage)
        assertTrue(dto.airConditioner)
        assertEquals(52.1, dto.pickupLat!!, 0.0)
        assertEquals(58.2, dto.pickupLng!!, 0.0)
        assertEquals(3.4, dto.distanceKm!!, 0.0)
        assertTrue(dto.boosted)
        assertEquals("Receiver", dto.receiverName)
        assertEquals("small", dto.parcelSize)
    }

    @Test
    fun toRideDto_keepsNullableLocationFieldsNull() {
        val dto = JSONObject()
            .put("pickup_lat", JSONObject.NULL)
            .put("pickup_lng", JSONObject.NULL)
            .put("distance_km", JSONObject.NULL)
            .toRideDto()

        assertNull(dto.pickupLat)
        assertNull(dto.pickupLng)
        assertNull(dto.distanceKm)
        assertFalse(dto.boosted)
    }

    @Test
    fun toRequestNearDto_parsesNearbyPassengerRequestAndDefaultsName() {
        val dto = JSONObject()
            .put("id", 18)
            .put("passenger_name", "")
            .put("from_city", "Ufa")
            .put("to_city", "Sterlitamak")
            .put("from_lat", 54.7)
            .put("from_lng", 55.9)
            .put("seats", 3)
            .put("comment", "Need child seat")
            .put("distance_km", 1.2)
            .toRequestNearDto()

        assertEquals(18, dto.id)
        assertEquals("Пассажир", dto.passengerName)
        assertEquals("Ufa", dto.fromCity)
        assertEquals("Sterlitamak", dto.toCity)
        assertEquals(54.7, dto.fromLat!!, 0.0)
        assertEquals(55.9, dto.fromLng!!, 0.0)
        assertEquals(3, dto.seats)
        assertEquals("Need child seat", dto.comment)
        assertEquals(1.2, dto.distanceKm!!, 0.0)
    }

    @Test
    fun staticPackageParsers_readArraysInBackendOrder() {
        val adPackages = ApiClient.parseAdPackages(
            JSONArray()
                .put(JSONObject().put("code", "city").put("title", "City").put("title_ba", "Ҡала").put("amount_kop", 100_000).put("period_days", 30))
                .put(JSONObject().put("code", "route").put("title", "Route").put("title_ba", "Маршрут").put("amount_kop", 200_000).put("period_days", 14))
        )
        val boostPlans = ApiClient.parseBoostPlans(
            JSONArray()
                .put(JSONObject().put("tier", "day").put("title", "Day").put("price", 99).put("hours", 24))
                .put(JSONObject().put("tier", "week").put("title", "Week").put("price", 499).put("hours", 168))
        )

        assertEquals(listOf("city", "route"), adPackages.map { it.code })
        assertEquals(100_000, adPackages.first().amountKop)
        assertEquals("Ҡала", adPackages.first().titleBa)
        assertEquals(listOf("day", "week"), boostPlans.map { it.tier })
        assertEquals(168, boostPlans.last().hours)
    }

    @Test
    fun parseMyAd_readsArraysAsCsvAndBlankSubmittedAtAsNull() {
        val dto = ApiClient.parseMyAd(
            JSONObject()
                .put("id", "ad-1")
                .put("title", "Title")
                .put("text", "Text")
                .put("button", "Open")
                .put("target", "https://example.com")
                .put("erid", "erid")
                .put("status", "draft")
                .put("reject_reason", "")
                .put("package", "city")
                .put("package_title", "City")
                .put("budget_kop", 100_000)
                .put("period_days", 30)
                .put("placements", JSONArray().put("profile").put("route"))
                .put("cities", JSONArray().put("Baymak").put("Sibay"))
                .put("paid", false)
                .put("submitted_at", "")
        )

        assertEquals("ad-1", dto.id)
        assertEquals("profile,route", dto.placements)
        assertEquals("Baymak,Sibay", dto.cities)
        assertEquals(100_000, dto.budgetKop)
        assertFalse(dto.paid)
        assertNull(dto.submittedAt)
    }

    @Test
    fun extractedApiParsers_keepLiveDriverChatFeedAndAdsContracts() {
        val driverStatus = parseDriverStatusDto(
            JSONObject()
                .put("docs_status", "verified")
                .put("verified", true)
                .put("car_make", "Kia")
                .put("car_model", "Rio")
                .put("car_color", "white")
                .put("car_plate", "A001AA")
                .put("seats", 4)
                .put("license_url", "/secure/license.jpg")
                .put("car_photo_url", "/secure/car.jpg")
                .put("online", true)
                .put("autocheck_result", "pass")
                .put("autocheck_data", "{\"score\":0.9}")
        )
        val booking = parseDriverBookingDto(
            JSONObject()
                .put("booking_id", 22)
                .put("passenger_name", "Passenger")
                .put("passenger_rating", JSONObject.NULL)
                .put("route", "A -> B")
                .put("status", "pending")
        )
        val conversation = parseConversationDto(
            JSONObject()
                .put("booking_id", 33)
                .put("peer_name", "Peer")
                .put("route", "A -> B")
                .put("last_message", "Hello")
                .put("peer_avatar", "avatar.jpg")
                .put("depart_at", "")
        )
        val feed = parseFeedDto(
            JSONObject()
                .put("today", 5)
                .put("week", 30)
                .put("month", 100)
                .put("year", 1000)
                .put("drivers", 12)
                .put("donations_total", 5000)
                .put("top_route", JSONObject().put("from_city", "A").put("to_city", "B").put("count", 7))
        )
        val ad = parseAdDto(
            JSONObject()
                .put("id", "ad-1")
                .put("title", "Title")
                .put("text", "Text")
                .put("button", "Open")
                .put("erid", "erid")
                .put("placement", "profile")
                .put("partner", "Partner")
                .put("contact", "+7")
                .put("target", "https://example.com")
                .put("image", "image.jpg")
                .put("city", "Baymak")
        )

        assertTrue(driverStatus.verified)
        assertEquals("pass", driverStatus.autocheckResult)
        assertNull(booking.passengerRating)
        assertEquals("pending", booking.status)
        assertNull(conversation.departAt)
        assertEquals("avatar.jpg", conversation.peerAvatar)
        assertEquals(7, feed.topCount)
        assertEquals(5000, feed.donationsTotal)
        assertEquals("Partner", ad.partner)
        assertEquals("Baymak", ad.city)
    }

    @Test
    fun parseFeedDto_handlesMissingTopRouteAsEmptyRoute() {
        val feed = parseFeedDto(JSONObject().put("today", 1))

        assertEquals(1, feed.today)
        assertEquals("", feed.topFrom)
        assertEquals("", feed.topTo)
        assertEquals(0, feed.topCount)
    }

    @Test
    fun extractedApiParsers_keepRequestsContactsModerationAndBookingsContracts() {
        val request = parseRequestDto(
            JSONObject()
                .put("id", 1)
                .put("from_city", "A")
                .put("to_city", "B")
                .put("seats", 2)
                .put("category", "regular")
                .put("with_kids", true)
                .put("max_price", 500)
                .put("comment", "comment")
                .put("for_relative_name", "")
                .put("status", "open")
                .put("desired_at", "")
        )
        val contact = parseContactDto(JSONObject().put("id", 2).put("name", "Mom").put("relation", "family").put("phone", "+7").put("notify_by_default", true))
        val feedRequest = parseRequestFeedDto(
            JSONObject()
                .put("id", 3)
                .put("passenger_name", "Passenger")
                .put("from_city", "A")
                .put("to_city", "B")
                .put("seats", 1)
                .put("comment", "comment")
                .put("responded", true)
                .put("passenger_avatar", "avatar")
                .put("prefs", JSONArray().put("quiet").put("bags"))
        )
        val response = parseResponseDto(
            JSONObject()
                .put("id", 4)
                .put("driver_id", 5)
                .put("driver_name", "Driver")
                .put("driver_rating", 4.9)
                .put("price", 450)
                .put("comment", "ok")
                .put("status", "accepted")
                .put("driver_avatar", "driver.jpg")
        )
        val pendingDriver = parsePendingDriverDto(
            JSONObject()
                .put("user_id", 6)
                .put("name", "Driver")
                .put("phone", "+7")
                .put("car", "Kia")
                .put("license_url", "license")
                .put("car_photo_url", "car")
                .put("autocheck_result", "needs_human")
                .put("autocheck_score", 0.75)
                .put("autocheck_data", "{}")
        )
        val report = parseAdminReportDto(JSONObject().put("id", 7).put("reporter_name", "A").put("target_name", "B").put("target_phone", "+7").put("reason", "spam").put("created_at", "now"))
        val reportable = parseReportableUserDto(JSONObject().put("id", 8).put("name", "Target"))
        val review = parseReviewItem(JSONObject().put("id", 9).put("name", "User").put("city", "City").put("text", "Great"))
        val booking = parseBookingMineDto(
            JSONObject()
                .put("id", 10)
                .put("ride_id", 11)
                .put("seats", 2)
                .put("price", 900)
                .put("status", "confirmed")
                .put("boarding_code", "1234")
                .put("from_city", "A")
                .put("to_city", "B")
                .put("depart_at", "2026-07-03T12:00:00")
                .put("driver_name", "Driver")
                .put("driver_verified", true)
        )

        assertEquals(500, request.maxPrice)
        assertNull(request.forRelativeName)
        assertNull(request.desiredAt)
        assertTrue(contact.notifyByDefault)
        assertEquals(listOf("quiet", "bags"), feedRequest.prefs)
        assertEquals(4.9, response.driverRating!!, 0.0)
        assertEquals(0.75, pendingDriver.autocheckScore, 0.0)
        assertEquals("spam", report.reason)
        assertEquals("Target", reportable.name)
        assertEquals(5, review.stars)
        assertTrue(booking.driverVerified)
    }

    @Test
    fun extractedApiParsers_keepDiscoveryAdminAdsAndPaymentContracts() {
        val route = parsePopularRouteDto(JSONObject().put("from_city", "A").put("to_city", "B").put("count", 12))
        val notification = parseNotifDto(
            JSONObject()
                .put("id", 3).put("type", "message")
                .put("title_ru", "Заголовок").put("title_ba", "Баш")
                .put("body_ru", "Текст").put("body_ba", "Текст BA")
                .put("ref_kind", "booking").put("ref_id", 7)
                .put("read", true).put("created_at", "2026-09-05T00:00:00Z")
        )
        val adminAd = parseAdminAdDto(
            JSONObject()
                .put("id", "ad-2")
                .put("partner", "Partner")
                .put("title", "Title")
                .put("text", "Text")
                .put("plan", "premium")
                .put("status", "active")
                .put("placements", JSONArray().put("profile").put("route"))
                .put("erid", "erid")
                .put("ends_at", "")
                .put("live", true)
                .put("expired", false)
                .put("button", "Open")
                .put("target", "https://example.com")
                .put("cities", JSONArray().put("Baymak").put("Sibay"))
                .put("reject_reason", "")
                .put("owner_id", JSONObject.NULL)
        )
        val boost = parseBoostResultDto(
            JSONObject()
                .put("status", "pending")
                .put("method", "sbp_manual")
                .put("payment_id", JSONObject.NULL)
                .put("amount", 1000)
                .put("confirmation_url", "")
                .put("payee", JSONObject().put("phone", "+7").put("bank", "Bank").put("name", "Name"))
        )
        val payment = parsePendingPaymentDto(
            JSONObject()
                .put("payment_id", 31)
                .put("purpose", "boost")
                .put("tier", "day")
                .put("amount", 1000)
                .put("ride_id", JSONObject.NULL)
                .put("payer_name", "Payer")
                .put("payer_phone", "+7")
                .put("created_at", "now")
                .put("note", "note")
        )

        assertEquals(12, route.count)
        assertEquals("Текст", notification.bodyRu)
        assertEquals("Текст BA", notification.bodyBa)
        assertEquals(7, notification.refId)
        assertTrue(notification.read)
        assertEquals("profile,route", adminAd.placements)
        assertEquals("Baymak,Sibay", adminAd.cities)
        assertNull(adminAd.ownerId)
        assertNull(adminAd.endsAt)
        assertTrue(adminAd.live)
        assertEquals(0, boost.paymentId)
        assertNull(boost.confirmationUrl)
        assertEquals("Bank", boost.payeeBank)
        assertNull(payment.rideId)
        assertEquals("note", payment.note)
    }

    @Test
    fun parseBookingDetailsDto_keepsPrivatePickupNullableUntilUnlocked() {
        val dto = parseBookingDetailsDto(
            JSONObject()
                .put("booking_id", 41)
                .put("ride_id", 42)
                .put("role", "passenger")
                .put("status", "pending")
                .put("contact_unlocked", false)
                .put("from_city", "Temyasovo")
                .put("to_city", "Ufa")
                .put("depart_at", "2026-07-03T12:44:19")
                .put("seats", 2)
                .put("price", 1800)
                .put("driver_name", "Driver")
                .put("driver_verified", true)
                .put("driver_phone", "")
                .put("driver_car", "Kia Rio")
                .put("pickup", "")
                .put("pickup_lat", JSONObject.NULL)
                .put("pickup_lng", JSONObject.NULL)
                .put("from_lat", 52.7)
                .put("from_lng", 58.1)
                .put("to_lat", 54.7)
                .put("to_lng", 55.9)
        )

        assertEquals(41, dto.bookingId)
        assertEquals("pending", dto.status)
        assertFalse(dto.contactUnlocked)
        assertEquals("", dto.driverPhone)
        assertNull(dto.pickupLat)
        assertNull(dto.pickupLng)
        assertEquals(52.7, dto.fromLat!!, 0.0)
        assertEquals(55.9, dto.toLng!!, 0.0)
    }

    @Test
    fun parsePaymentsSummaryDto_defaultsMissingSectionsToZero() {
        val full = parsePaymentsSummaryDto(
            JSONObject()
                .put("donate", JSONObject().put("count", 2).put("sum_rub", 500))
                .put("boost", JSONObject().put("count", 3).put("sum_rub", 900))
        )
        val empty = parsePaymentsSummaryDto(JSONObject())

        assertEquals(2, full.donateCount)
        assertEquals(500, full.donateSum)
        assertEquals(3, full.boostCount)
        assertEquals(900, full.boostSum)
        assertEquals(0, empty.donateCount)
        assertEquals(0, empty.boostSum)
    }
}
