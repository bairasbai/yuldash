package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Контракт сетевого слоя: каждая ручка идёт по своему адресу своим методом.
 *
 * Зачем. В [ApiClient] больше трёхсот методов, и почти каждый — одна строка вида «дёрни
 * POST /parcels/42/release». Опечатка в адресе или GET вместо POST компилируется молча:
 * приложение соберётся, экран нарисуется, а кнопка будет вечно возвращать 404. Замер покрытия
 * 2026-08-04 показал, что 178 таких ручек не тронуты ни одним тестом — то есть их адреса
 * не проверял никто, даже машинно.
 *
 * Что проверяем на каждой:
 *  1. запрос вообще уходит (а не теряется в сборке пути);
 *  2. HTTP-метод тот, что задуман (GET не меняет данные, POST не «читает»);
 *  3. путь совпадает с ожидаемым (подстановки id заменены на 1);
 *  4. обычный ответ 200 разбирается без исключения;
 *  5. ответ 500 честно превращается в ошибку, а не в «пустой успех» — см. [OfflineHonestyTest],
 *     приложение уже один раз врало «Заявок пока нет» вместо «сервер недоступен».
 *
 * Таблица сгенерирована из самого `ApiClient.kt`, поэтому не разъезжается с кодом.
 * Что НЕ проверяем: содержимое тела запроса и разбор конкретных полей — это в адресных
 * тестах (`ApiClientRidesTest`, `ApiClientBookingsTest` и соседних).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientEndpointContractTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    /** Строка таблицы: как зовут ручку, куда она должна пойти и как её вызвать. */
    private class Ep(
        val name: String,
        val method: String,
        val path: String,
        val call: suspend () -> Any?,
    )

    /**
     * Ответ-«толстяк»: объект с самыми частыми ключами наших DTO. Любой парсер найдёт в нём
     * что-то своё и отработает целиком, а чего нет — возьмёт значение по умолчанию (в `ApiClient`
     * везде `opt*`, строгих геттеров нет, поэтому лишние и недостающие ключи безопасны).
     */
    private val fatItem: String = (
        """{"id":1,"user_id":1,"booking_id":1,"ride_id":1,"order_id":1,"owner_id":1,"partner":1,""" +
            """"status":"active","state":"ok","kind":"regular","category":"regular","role":"driver",""" +
            """"name":"Айгуль","title":"Заголовок","title_ru":"Заголовок","title_ba":"Баш",""" +
            """"description":"Текст","text":"Сообщение","note":"Комментарий","statement":"Пояснение",""" +
            """"reason":"Причина","reject_reason":"Не подошло","code":"ABC123","method":"sbp",""" +
            """"payee":"Ринат","bank":"Сбер","phone":"+79990000001","address":"ул. Ленина, 1",""" +
            """"city":"Сибай","route":"Сибай - Уфа","from_city":"Сибай","to_city":"Уфа",""" +
            """"depart_at":"2026-08-05T08:00:00","created_at":"2026-08-04T10:00:00",""" +
            """"updated_at":"2026-08-04T10:00:00","day":"2026-08-04","price":350,"amount":350,""" +
            """"amount_kop":35000,"commission_kop":3500,"net_kop":31500,"seats":2,"count":3,""" +
            """"unread":1,"trips":5,"rating":4.8,"stars":5,"level":2,"lat":52.5911,"lng":58.3178,""" +
            """"distance_km":12.5,"driver_name":"Айдар","driver_verified":true,"enabled":true,""" +
            """"active":true,"online":true,"paid":true,"premium":true,"invited":true,""" +
            """"url":"https://example.org/x","confirmation_url":"https://example.org/pay",""" +
            """"payment_id":"p-1","target":"ride","ru":"по-русски","ba":"башҡортса",""" +
            """"limit_total":10,"limit_per_user":1,"redeemed_count":2,"perk_value":100,""" +
            """"period_days":30,"items":[]}"""
        )

    private val fat: String = """{"items":[$fatItem],"total":1,"ok":true,"detail":"ok"}"""

    private fun okResponse() = MockResponse().setResponseCode(200).setBody(fat)

    /** Берём первый запрос ручки и сливаем возможные добавочные (у некоторых их два). */
    private fun drain(): RecordedRequest? {
        val first = server.takeRequest(5, TimeUnit.SECONDS) ?: return null
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* хвосты */ }
        return first
    }

    private fun endpoints(): List<Ep> = listOf(
        Ep("healthOk", "GET", "/health") { ApiClient.healthOk() },
        Ep("updateCity", "POST", "/me/update") { ApiClient.updateCity(city = "x") },
        Ep("minAppVersion", "GET", "/version/min") { ApiClient.minAppVersion() },
        Ep("getRide", "GET", "/rides/1") { ApiClient.getRide(id = 1) },
        Ep("getPickupPoints", "GET", "/pickup-points") { ApiClient.getPickupPoints(city = "x") },
        Ep("getMedicalPartners", "GET", "/medical-partners") { ApiClient.getMedicalPartners() },
        Ep("getRidesToPartner", "GET", "/medical-partners/1/rides") { ApiClient.getRidesToPartner(partnerId = 1) },
        Ep("getSeasonalEvents", "GET", "/seasonal-events") { ApiClient.getSeasonalEvents() },
        Ep("getMyTrust", "GET", "/me/trust") { ApiClient.getMyTrust() },
        Ep("createInvite", "POST", "/invites") { ApiClient.createInvite() },
        Ep("getMyInvites", "GET", "/invites/mine") { ApiClient.getMyInvites() },
        Ep("redeemInvite", "POST", "/invites/redeem") { ApiClient.redeemInvite(code = "x") },
        Ep("getConsents", "GET", "/me/consents") { ApiClient.getConsents() },
        Ep("setConsent", "POST", "/me/consents") { ApiClient.setConsent(kind = "x") },
        Ep("shareInstantTrip", "POST", "/instant/orders/1/share") { ApiClient.shareInstantTrip(orderId = 1, contactId = 1) },
        Ep("roadsideHelp", "POST", "/bookings/1/stuck") { ApiClient.roadsideHelp(bookingId = 1, lat = null, lng = null, note = "x") },
        Ep("winterCheck", "POST", "/bookings/1/winter-check") { ApiClient.winterCheck(bookingId = 1) },
        Ep("winterCheckOk", "POST", "/bookings/1/winter-check/ok") { ApiClient.winterCheckOk(bookingId = 1) },
        Ep("getMyRestrictions", "GET", "/me/restrictions") { ApiClient.getMyRestrictions() },
        Ep("rateInstantOrder", "POST", "/instant/orders/1/rate") { ApiClient.rateInstantOrder(orderId = 1, stars = 1) },
        Ep("matchRides", "GET", "/match/rides") { ApiClient.matchRides(requestId = 1) },
        Ep("getMyResponses", "GET", "/responses/mine") { ApiClient.getMyResponses() },
        Ep("counterOffer", "POST", "/responses/1/counter") { ApiClient.counterOffer(responseId = 1, price = 1) },
        Ep("declineResponse", "POST", "/responses/1/decline") { ApiClient.declineResponse(responseId = 1) },
        Ep("deleteResponse", "DELETE", "/responses/1") { ApiClient.deleteResponse(responseId = 1) },
        Ep("adminResolveReport", "POST", "/admin/reports/1/resolve") { ApiClient.adminResolveReport(id = 1, resolution = "x") },
        Ep("adminRejectReport", "POST", "/admin/reports/1/reject") { ApiClient.adminRejectReport(id = 1) },
        Ep("adminQualityPause", "POST", "/admin/quality/1/pause") { ApiClient.adminQualityPause(userId = 1, hours = 1) },
        Ep("adminQualityUnpause", "POST", "/admin/quality/1/unpause") { ApiClient.adminQualityUnpause(userId = 1) },
        Ep("getOrderMessages", "GET", "/instant/orders/1/messages") { ApiClient.getOrderMessages(orderId = 1) },
        Ep("sendOrderMessage", "POST", "/instant/orders/1/messages") { ApiClient.sendOrderMessage(orderId = 1, text = "x") },
        Ep("getParcelMessages", "GET", "/parcels/1/messages") { ApiClient.getParcelMessages(parcelId = 1) },
        Ep("sendParcelMessage", "POST", "/parcels/1/messages") { ApiClient.sendParcelMessage(parcelId = 1, text = "x") },
        Ep("setDriverGender", "POST", "/driver/gender") { ApiClient.setDriverGender(gender = "x") },
        Ep("createDriverSchedule", "POST", "/driver/schedule") { ApiClient.createDriverSchedule(fromCity = "x", toCity = "x", weekdays = "x", time = "x") },
        Ep("getMyDriverSchedules", "GET", "/driver/schedule") { ApiClient.getMyDriverSchedules() },
        Ep("getPublicDriverSchedules", "GET", "/drivers/1/schedule") { ApiClient.getPublicDriverSchedules(driverId = 1) },
        Ep("deleteDriverSchedule", "DELETE", "/driver/schedule/1") { ApiClient.deleteDriverSchedule(scheduleId = 1) },
        Ep("markNotificationsRead", "POST", "/notifications/read") { ApiClient.markNotificationsRead() },
        Ep("getSupportTickets", "GET", "/support/tickets") { ApiClient.getSupportTickets() },
        Ep("getSupportTicket", "GET", "/support/tickets/1") { ApiClient.getSupportTicket(id = 1) },
        Ep("createSupportTicket", "POST", "/support/tickets") { ApiClient.createSupportTicket(subject = null, body = "x") },
        Ep("postSupportMessage", "POST", "/support/tickets/1/messages") { ApiClient.postSupportMessage(id = 1, body = "x") },
        Ep("closeSupportTicket", "POST", "/support/tickets/1/close") { ApiClient.closeSupportTicket(id = 1) },
        Ep("getRouteWatches", "GET", "/route-watch") { ApiClient.getRouteWatches() },
        Ep("deleteRouteWatch", "DELETE", "/route-watch/1") { ApiClient.deleteRouteWatch(id = 1) },
        Ep("getMyAdsStats", "GET", "/ads/mine/stats") { ApiClient.getMyAdsStats() },
        Ep("revokeBookingShare", "DELETE", "/bookings/1/share/1") { ApiClient.revokeBookingShare(bookingId = 1, shareId = 1) },
        Ep("getInstantShares", "GET", "/instant/orders/1/shares") { ApiClient.getInstantShares(orderId = 1) },
        Ep("revokeInstantShare", "DELETE", "/instant/orders/1/share/1") { ApiClient.revokeInstantShare(orderId = 1, shareId = 1) },
        Ep("getDriverPublic", "GET", "/drivers/1/public") { ApiClient.getDriverPublic(driverId = 1) },
        Ep("markNoShow", "POST", "/bookings/1/no-show") { ApiClient.markNoShow(bookingId = 1) },
        Ep("confirmBooking", "POST", "/bookings/1/confirm") { ApiClient.confirmBooking(bookingId = 1) },
        Ep("cancelRide", "POST", "/rides/1/cancel") { ApiClient.cancelRide(rideId = 1) },
        Ep("completeRide", "POST", "/rides/1/complete") { ApiClient.completeRide(rideId = 1) },
        Ep("editRide", "POST", "/rides/1/edit") { ApiClient.editRide(rideId = 1) },
        Ep("supportDonate", "POST", "/support/donate") { ApiClient.supportDonate(amountKop = 1) },
        Ep("getPaymentStatus", "GET", "/payments/1/status") { ApiClient.getPaymentStatus(paymentId = 1) },
        Ep("getDriverDebt", "GET", "/driver/debt") { ApiClient.getDriverDebt() },
        Ep("declareDebtPaid", "POST", "/driver/debt/paid") { ApiClient.declareDebtPaid() },
        Ep("getAdminDebts", "GET", "/admin/debts") { ApiClient.getAdminDebts() },
        Ep("confirmDebt", "POST", "/admin/debts/1/confirm") { ApiClient.confirmDebt(debtId = 1) },
        Ep("rejectDebt", "POST", "/admin/debts/1/reject") { ApiClient.rejectDebt(debtId = 1) },
        Ep("getTaxiPulse", "GET", "/admin/taxi/pulse") { ApiClient.getTaxiPulse() },
        Ep("instantPresence", "POST", "/instant/presence") { ApiClient.instantPresence(lat = 1.0, lng = 1.0) },
        Ep("getInstantDemand", "GET", "/instant/demand") { ApiClient.getInstantDemand() },
        Ep("getDriverOffer", "GET", "/instant/driver/offer") { ApiClient.getDriverOffer() },
        Ep("instantAccept", "POST", "/instant/orders/1/accept") { ApiClient.instantAccept(id = 1) },
        Ep("instantArrived", "POST", "/instant/orders/1/arrived") { ApiClient.instantArrived(id = 1) },
        Ep("instantOnboard", "POST", "/instant/orders/1/onboard") { ApiClient.instantOnboard(id = 1) },
        Ep("instantDone", "POST", "/instant/orders/1/done") { ApiClient.instantDone(id = 1) },
        Ep("scheduleInstantOrder", "POST", "/instant/schedule") { ApiClient.scheduleInstantOrder(fromLat = 1.0, fromLng = 1.0, toLat = 1.0, toLng = 1.0, scheduledAt = "x") },
        Ep("getScheduledOrders", "GET", "/instant/scheduled") { ApiClient.getScheduledOrders() },
        Ep("activateScheduledOrder", "POST", "/instant/scheduled/1/activate") { ApiClient.activateScheduledOrder(id = 1) },
        Ep("cancelScheduledOrder", "POST", "/instant/scheduled/1/cancel") { ApiClient.cancelScheduledOrder(id = 1) },
        Ep("applyTaxi", "POST", "/taxi/apply") { ApiClient.applyTaxi(inn = "x", permitNumber = "x", birthDate = "x", licenseSinceYear = 1, permitPhotoUrl = "x", osagoUrl = "x", selfieUrl = "x", criminalRecordUrl = "x") },
        Ep("updateTaxiDocuments", "POST", "/taxi/documents") { ApiClient.updateTaxiDocuments() },
        Ep("getPretrip", "GET", "/taxi/pretrip") { ApiClient.getPretrip() },
        Ep("confirmPretrip", "POST", "/taxi/pretrip") { ApiClient.confirmPretrip() },
        Ep("getMyTaxiApplication", "GET", "/taxi/application") { ApiClient.getMyTaxiApplication() },
        Ep("adminTaxiApplications", "GET", "/admin/taxi-applications") { ApiClient.adminTaxiApplications() },
        Ep("adminApproveTaxiApplication", "POST", "/admin/taxi-applications/1/approve") { ApiClient.adminApproveTaxiApplication(id = 1) },
        Ep("adminRejectTaxiApplication", "POST", "/admin/taxi-applications/1/reject") { ApiClient.adminRejectTaxiApplication(id = 1, comment = "x") },
        Ep("adminTaxiCities", "GET", "/admin/taxi-cities") { ApiClient.adminTaxiCities() },
        Ep("adminAddTaxiCity", "POST", "/admin/taxi-cities") { ApiClient.adminAddTaxiCity(city = "x", enabled = true) },
        Ep("adminDeleteTaxiCity", "DELETE", "/admin/taxi-cities/1") { ApiClient.adminDeleteTaxiCity(id = 1) },
        Ep("joinWaitlist", "POST", "/waitlist") { ApiClient.joinWaitlist(phone = "x", city = "x", role = "x") },
        Ep("getAdminWaitlist", "GET", "/admin/waitlist") { ApiClient.getAdminWaitlist() },
        Ep("adminWaitlistInvite", "POST", "/admin/waitlist/invite") { ApiClient.adminWaitlistInvite(ids = emptyList()) },
        Ep("searchSettlements", "GET", "/settlements") { ApiClient.searchSettlements(q = "x") },
        Ep("getSettlementPopularRoutes", "GET", "/settlements/popular-routes") { ApiClient.getSettlementPopularRoutes() },
        Ep("getInstantZone", "GET", "/instant/zone") { ApiClient.getInstantZone() },
        Ep("setInstantZone", "POST", "/instant/zone") { ApiClient.setInstantZone(workZone = "x") },
        Ep("getNearbyDrivers", "GET", "/instant/nearby-drivers") { ApiClient.getNearbyDrivers(lat = 1.0, lng = 1.0) },
        Ep("getTaxiWorkday", "GET", "/instant/workday") { ApiClient.getTaxiWorkday() },
        Ep("getMyStats", "GET", "/me/stats") { ApiClient.getMyStats() },
        Ep("getCoupons", "GET", "/coupons") { ApiClient.getCoupons() },
        Ep("getCoupon", "GET", "/coupons/1") { ApiClient.getCoupon(id = 1) },
        Ep("activateCoupon", "POST", "/coupons/1/activate") { ApiClient.activateCoupon(id = 1) },
        Ep("getMyCoupons", "GET", "/my/coupons") { ApiClient.getMyCoupons() },
        Ep("getPartnerPlans", "GET", "/partner/plans") { ApiClient.getPartnerPlans() },
        Ep("getPartnerMe", "GET", "/partner/me") { ApiClient.getPartnerMe() },
        Ep("subscribePartner", "POST", "/partner/subscribe") { ApiClient.subscribePartner(plan = "x") },
        Ep("getPartnerCoupons", "GET", "/partner/coupons") { ApiClient.getPartnerCoupons() },
        Ep("setPartnerCouponStatus", "POST", "/partner/coupons/1/status") { ApiClient.setPartnerCouponStatus(id = 1, status = "x") },
        Ep("getPartnerCouponStats", "GET", "/partner/coupons/1/stats") { ApiClient.getPartnerCouponStats(id = 1) },
        Ep("redeemCoupon", "POST", "/coupons/redeem") { ApiClient.redeemCoupon(code = "x") },
        Ep("getAdminPartners", "GET", "/admin/partners") { ApiClient.getAdminPartners() },
        Ep("approvePartner", "POST", "/admin/partners/1/approve") { ApiClient.approvePartner(id = 1) },
        Ep("rejectPartner", "POST", "/admin/partners/1/reject") { ApiClient.rejectPartner(id = 1, reason = "x") },
        Ep("getPromoStats", "GET", "/promo/X/stats") { ApiClient.getPromoStats(code = "x") },
        Ep("adminListPromo", "GET", "/admin/promo") { ApiClient.adminListPromo() },
        Ep("adminCreatePromo", "POST", "/admin/promo") { ApiClient.adminCreatePromo(code = "x", title = "x", description = "x", campaign = "x", kind = "x", perkValue = 1, limitTotal = 1, limitPerUser = 1) },
        Ep("adminSetPromoStatus", "POST", "/admin/promo/1/status") { ApiClient.adminSetPromoStatus(id = 1, active = true) },
        Ep("cancelParcel", "POST", "/parcels/1/cancel") { ApiClient.cancelParcel(id = 1) },
        Ep("acceptParcel", "POST", "/parcels/1/accept") { ApiClient.acceptParcel(id = 1) },
        Ep("adminListParcels", "GET", "/admin/parcels") { ApiClient.adminListParcels() },
        Ep("applyCourier", "POST", "/courier/apply") { ApiClient.applyCourier(transport = "x", selfieUrl = "x") },
        Ep("getCourierEarnings", "GET", "/courier/earnings") { ApiClient.getCourierEarnings() },
        Ep("getCourierApplication", "GET", "/courier/application") { ApiClient.getCourierApplication() },
        Ep("courierOnline", "POST", "/courier/online") { ApiClient.courierOnline(zone = "x") },
        Ep("courierOffline", "POST", "/courier/offline") { ApiClient.courierOffline() },
        Ep("courierEstimate", "GET", "/courier/estimate") { ApiClient.courierEstimate(fromLat = 1.0, fromLng = 1.0, toLat = 1.0, toLng = 1.0, size = "x", urgency = "x") },
        Ep("setGoodsCost", "POST", "/courier/orders/1/goods-cost") { ApiClient.setGoodsCost(id = 1, actualKop = 1) },
        Ep("disputeParcel", "POST", "/parcels/1/dispute") { ApiClient.disputeParcel(id = 1, reason = "x", type = "x", evidenceUrls = emptyList()) },
        Ep("getCourierMe", "GET", "/courier/me") { ApiClient.getCourierMe() },
        Ep("rateParcel", "POST", "/parcels/1/rate") { ApiClient.rateParcel(id = 1, stars = 1) },
        Ep("payCommission", "POST", "/courier/pay-commission") { ApiClient.payCommission() },
        Ep("adminListCourierApps", "GET", "/admin/courier-applications") { ApiClient.adminListCourierApps() },
        Ep("adminApproveCourier", "POST", "/admin/courier-applications/1/approve") { ApiClient.adminApproveCourier(id = 1) },
        Ep("adminRejectCourier", "POST", "/admin/courier-applications/1/reject") { ApiClient.adminRejectCourier(id = 1, reason = "x") },
        Ep("getWalletBalance", "GET", "/wallet/balance") { ApiClient.getWalletBalance() },
        Ep("getWalletLedger", "GET", "/wallet/ledger") { ApiClient.getWalletLedger() },
        Ep("getPayoutStatus", "GET", "/wallet/payout/status") { ApiClient.getPayoutStatus() },
        Ep("savePayoutRequisite", "POST", "/wallet/payout/requisite") { ApiClient.savePayoutRequisite(cardLast4 = "x") },
        Ep("requestPayout", "POST", "/wallet/payout") { ApiClient.requestPayout(amountKop = 1, idempotencyKey = "x") },
        Ep("payBooking", "POST", "/bookings/1/pay") { ApiClient.payBooking(bookingId = 1, methodKey = "x") },
        Ep("payInstantOrder", "POST", "/instant/orders/1/pay") { ApiClient.payInstantOrder(orderId = 1, methodKey = "x") },
        Ep("getDriverEarnings", "GET", "/driver/earnings") { ApiClient.getDriverEarnings() },
        Ep("getSavedPlaces", "GET", "/places/saved") { ApiClient.getSavedPlaces() },
        Ep("saveSavedPlace", "POST", "/places/saved") { ApiClient.saveSavedPlace(kind = "x", label = "x", address = "x", lat = 1.0, lng = 1.0) },
        Ep("deleteSavedPlace", "DELETE", "/places/saved/1") { ApiClient.deleteSavedPlace(id = 1) },
        Ep("getRecentPlaces", "GET", "/places/recent") { ApiClient.getRecentPlaces() },
        Ep("addRecentPlace", "POST", "/places/recent") { ApiClient.addRecentPlace(address = "x", lat = 1.0, lng = 1.0) },
        Ep("getTripReceipt", "GET", "/trips/1/receipt") { ApiClient.getTripReceipt(bookingId = 1) },
        Ep("instantRoadsideHelp", "POST", "/instant/orders/1/stuck") { ApiClient.instantRoadsideHelp(orderId = 1, lat = null, lng = null) },
        Ep("waitForDriver", "POST", "/instant/orders/1/wait") { ApiClient.waitForDriver(orderId = 1) },
        Ep("getInstantReceipt", "GET", "/instant/orders/1/receipt") { ApiClient.getInstantReceipt(orderId = 1) },
        Ep("instantCashReceived", "POST", "/instant/orders/1/cash-received") { ApiClient.instantCashReceived(orderId = 1) },
        Ep("instantLostItem", "POST", "/instant/orders/1/lost-item") { ApiClient.instantLostItem(orderId = 1) },
        Ep("getInstantTipInfo", "GET", "/instant/orders/1/tip") { ApiClient.getInstantTipInfo(orderId = 1) },
        Ep("sayInstantThanks", "POST", "/instant/orders/1/thanks") { ApiClient.sayInstantThanks(orderId = 1) },
        Ep("getDriverTaxiRides", "GET", "/driver/taxi-rides") { ApiClient.getDriverTaxiRides() },
        Ep("parcelRelease", "POST", "/parcels/1/release") { ApiClient.parcelRelease(parcelId = 1) },
        Ep("parcelReturnStart", "POST", "/parcels/1/return-start") { ApiClient.parcelReturnStart(parcelId = 1) },
        Ep("parcelReturnDone", "POST", "/parcels/1/return-done") { ApiClient.parcelReturnDone(parcelId = 1) },
        Ep("adminSosList", "GET", "/admin/sos") { ApiClient.adminSosList() },
        Ep("adminSosHandle", "POST", "/admin/sos/1/handle") { ApiClient.adminSosHandle(eventId = 1) },
        Ep("adminForgiveDebt", "POST", "/admin/debts/1/forgive") { ApiClient.adminForgiveDebt(debtId = 1) },
        Ep("adminParcelCancel", "POST", "/admin/parcels/1/cancel") { ApiClient.adminParcelCancel(parcelId = 1) },
        Ep("adminParcelReleaseCourier", "POST", "/admin/parcels/1/release-courier") { ApiClient.adminParcelReleaseCourier(parcelId = 1) },
        Ep("getMyAchievements", "GET", "/me/achievements") { ApiClient.getMyAchievements() },
        Ep("createParcelTrackLink", "POST", "/parcels/1/track-link") { ApiClient.createParcelTrackLink(parcelId = 1) },
        Ep("revokeParcelTrackLink", "DELETE", "/parcels/1/track-link") { ApiClient.revokeParcelTrackLink(parcelId = 1) },
        Ep("adminPendingRatings", "GET", "/admin/ratings/pending") { ApiClient.adminPendingRatings() },
        Ep("adminPublishRating", "POST", "/admin/ratings/1/publish") { ApiClient.adminPublishRating(ratingId = 1) },
        Ep("adminExcludeRating", "POST", "/admin/ratings/1/exclude") { ApiClient.adminExcludeRating(ratingId = 1) },
        Ep("getMyIncidents", "GET", "/incidents/mine") { ApiClient.getMyIncidents() },
        Ep("getIncident", "GET", "/incidents/1") { ApiClient.getIncident(id = 1) },
        Ep("fileIncident", "POST", "/incidents") { ApiClient.fileIncident(respondentId = 1, type = "x", description = "x") },
        Ep("respondIncident", "POST", "/incidents/1/respond") { ApiClient.respondIncident(id = 1, statement = "x") },
        Ep("appealIncident", "POST", "/incidents/1/appeal") { ApiClient.appealIncident(id = 1, text = "x") },
        Ep("withdrawIncident", "POST", "/incidents/1/withdraw") { ApiClient.withdrawIncident(id = 1) },
        Ep("getMyStanding", "GET", "/me/standing") { ApiClient.getMyStanding() },
        Ep("getSafetyPolicy", "GET", "/safety/policy") { ApiClient.getSafetyPolicy() },
        Ep("adminIncidents", "GET", "/admin/incidents") { ApiClient.adminIncidents() },
        Ep("adminResolveIncident", "POST", "/admin/incidents/1/resolve") { ApiClient.adminResolveIncident(id = 1, resolution = "x", fault = "x", note = "x") },
        Ep("adminParcelClose", "POST", "/admin/parcels/1/close") { ApiClient.adminParcelClose(parcelId = 1) },
    )

    @Test
    fun `каждая ручка идёт по своему адресу своим методом`() = runBlocking {
        val bad = mutableListOf<String>()
        for (ep in endpoints()) {
            server.enqueue(okResponse())
            val res = runCatching { ep.call() }
            val rec = drain()
            when {
                res.isFailure -> bad += "${ep.name}: вызов упал с исключением — ${res.exceptionOrNull()}"
                rec == null -> bad += "${ep.name}: запрос не ушёл вовсе"
                else -> {
                    if (rec.method != ep.method) {
                        bad += "${ep.name}: метод ${rec.method}, а задуман ${ep.method}"
                    }
                    val path = rec.path?.substringBefore('?')
                    if (path != ep.path) bad += "${ep.name}: путь $path, а задуман ${ep.path}"
                    val r = res.getOrNull()
                    if (r is Result<*> && r.isFailure) {
                        bad += "${ep.name}: ответ 200 превратился в ошибку — ${r.exceptionOrNull()}"
                    }
                }
            }
        }
        assertTrue("Ручки разъехались с задуманным:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `каждая ручка на ошибку сервера отвечает ошибкой, а не пустым успехом`() = runBlocking {
        val bad = mutableListOf<String>()
        for (ep in endpoints()) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
            val res = runCatching { ep.call() }
            drain()
            val r = res.getOrNull()
            when {
                res.isFailure -> Unit   // исключение наружу — тоже честно, экран его увидит
                r is Result<*> && r.isSuccess -> bad += "${ep.name}: сервер ответил 500, а метод сказал «успех»"
                r is Boolean && r -> bad += "${ep.name}: сервер ответил 500, а метод вернул true"
            }
        }
        assertTrue("Ручки прячут ошибку сервера:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `таблица не теряет ручки при правках`() {
        // Страховка от тихой потери строк: если ручку убрали из таблицы, тест не «позеленеет
        // молча», а скажет, что проверок стало меньше, чем было на момент написания.
        assertTrue(
            "В таблице ${endpoints().size} ручек, было 178. Строку удалили или забыли добавить?",
            endpoints().size >= 178,
        )
    }
}
