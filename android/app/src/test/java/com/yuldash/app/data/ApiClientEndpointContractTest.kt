package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
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

    // ---------------------------------------------------------------------------------------
    // Два крайних ответа сервера: «пришло всё» и «не пришло ничего».
    //
    // Разбор ответа — самое незаметное место в приложении. Ошибка здесь не роняет экран,
    // а тихо подставляет пустое значение: телефон водителя становится пустой строкой, цена —
    // нулём, дата — «не указано». Человек видит рабочий экран с неправильными данными.
    //
    // Ловится это ровно двумя крайностями:
    //  • ПОЛНЫЙ ответ — все поля, которые приложение когда-либо читает, разом. Если какой-то
    //    разборщик ждёт список, а получит строку (или наоборот) — он бросит исключение здесь,
    //    а не у человека в дороге.
    //  • ПУСТОЙ ответ — ни одного поля. Так выглядит ответ, когда на сервере поле переименовали
    //    или выключили фичу. Приложение обязано подставить значения по умолчанию и жить дальше.
    //
    // Списки ключей собраны из самого `ApiClient.kt` — всё, что он читает через `opt*`.
    // ---------------------------------------------------------------------------------------

    /** числовые поля: id, цены, счётчики, координаты */
    private val numberKeys = listOf(
        "activations", "active", "active_incidents", "amount", "amount_kop", "applied",
        "autocheck_score", "avg", "avg_search_sec_today", "balance_kop", "balance_rub", "base_kop",
        "base_price", "blocked_user_id", "booking_id", "budget_kop", "cancel_fee_kop",
        "cancelled_today", "clicks", "co2_saved_kg", "cod_amount_kop", "collected_fee_kop",
        "commission_earned_kop", "commission_kop", "commission_min_kop", "commission_owed_kop",
        "commission_paid_kop", "commission_percent", "contact_id", "count", "coupon_id", "credits",
        "ctr", "current_fee_percent", "days_in_service", "days_left", "days_with_yuldash", "debt_id",
        "declared_value_kop", "delivered_count", "deliveries", "delivery_attempts", "delivery_kop",
        "discount_kop", "discount_used_order_id", "distance_km", "distance_kop", "donations_total",
        "done_today", "driver", "driver_id", "drivers_online", "dynamic_k", "earned_count",
        "earnings_today", "entry_id", "eta_min", "fee_kop", "fee_next_percent",
        "fee_per_redemption_kop", "fee_percent", "fee_today_kop", "fee_trips_to_next",
        "founder_limit", "founder_used",
        "from_lat", "from_lng", "fuel_estimate_kop", "goal", "goods_actual_kop", "gross_today_kop",
        "hours", "id", "impressions", "invited", "invited_by", "k", "km", "lat", "level",
        "limit_hours", "limit_per_user", "limit_sec", "limit_total", "lng", "max_kop", "max_price",
        "min_kop", "month", "my_response_id", "my_stars", "net_kop", "net_today_kop", "next_at",
        "night_k",
        "no_show_today", "online", "order_id", "orders_active", "orders_period", "orders_today",
        "owed_commission_kop",
        "owner_id", "parcels_helped", "partner_id", "passenger", "passenger_rating", "pay_amount",
        "payment_id", "pending_kop", "percent_period", "percent_today", "period_days", "perk_value",
        "plus", "points", "price_locked_sec",
        "pickup_eta_min", "pickup_k",
        "pickup_lat", "pickup_lng", "price", "price_kop", "price_with_discount", "pricing_cap_k",
        "promo_discount_kop", "ratee_id", "rating_count", "redeemed", "redeemed_count",
        "redeemed_total", "ref_id", "reliability", "remaining_sec", "requests", "ride_id",
        "saved_rub", "seats", "seconds_online", "sender_id", "size_kop", "stars",
        "strike_decay_days", "strikes", "strikes_to_limit", "strikes_to_suspend", "sum", "sum_rub",
        "surge_k", "suspend_1_days", "suspend_2_days", "suspend_3_days", "target_user_id",
        "tariff_id", "tenure_days", "threshold_kop", "to_lat", "to_lng", "to_next", "today", "total",
        "total_due_kop", "total_fee_kop", "total_net_kop", "total_price", "traffic_k", "trips",
        "trips_count", "trips_done", "unbilled_fee_kop", "unpaid_kop", "unread", "urgency_kop", "user_id", "value",
        "views", "views_period", "views_today",
        "wait_minutes", "waiting_fee_kop", "warnings", "weather_k", "week", "weight", "weight_kg",
        "window_days", "year",
    )

    /** текстовые поля: имена, города, коды, адреса */
    private val stringKeys = listOf(
        "access_token", "ad_id", "address", "author", "autocheck_data", "autocheck_result",
        "avatar_url", "ba", "bank", "block_reason", "boarding_code", "body_ba", "body_ru", "button",
        "campaign", "car", "car_class", "car_color", "car_make", "car_model", "car_photo_url",
        "car_plate", "card_last4", "cargo_type", "category_ba", "category_ru", "city", "code",
        "comment", "confirm_code", "confirmation_url", "contact", "coupon_title", "customer_name",
        "delivery_photo_url", "delivery_type", "description", "description_ba", "description_ru",
        "direction", "discount_text", "docs_status", "driver_car", "driver_name", "driver_phase",
        "driver_phone", "erid", "fee_status", "fee_tier", "flag", "for_relative_name", "from_address",
        "from_city", "from_text", "full_name", "gender", "handled_note", "image", "label",
        "last_message", "last_sender", "license_url", "message_ba", "message_ru", "name",
        "next_title_ba", "next_title_ru", "note", "note_ba", "note_ru", "package", "package_title",
        "passenger_avatar", "passenger_name", "pay_method", "payer_name", "payer_phone",
        "payment_method", "peer_avatar", "peer_name", "phone", "pickup", "pickup_photo_url",
        "placement", "plan", "pricing_version", "promo_code", "purpose", "push_token", "ratee",
        "reason", "receiver_name", "receiver_phone", "ref_kind", "refresh_token", "reject_reason",
        "relation", "reporter_name", "request_id", "resolution", "return_reason", "route",
        "route_source", "ru", "selfie_url", "sender_name", "sender_phone", "size", "standing", "sub",
        "subject", "subscription_plan", "support_ba", "support_ru", "suspend_reason", "target_name",
        "target_phone", "text", "tier", "title_ba", "title_ru", "to_address", "to_city", "to_text",
        "token", "transport", "until", "urgency", "url", "user_name", "user_phone", "user_role",
        "weather_code", "zone",
    )

    /** даты и времена — формат тот же, что шлёт сервер */
    private val dateKeys = listOf(
        "boosted_until", "chat_open_until", "confirmed_at", "created_at", "date", "depart_at",
        "desired_at", "done_at", "due_at", "ends_at", "granted_at", "reserved_at", "submitted_at",
        "unlock_at", "updated_at", "used_at", "wait_until", "watch_date",
    )

    /** галочки: подтверждён, оплачен, включён */
    private val boolKeys = listOf(
        "active_flag", "already_thanked", "blocked", "can_act", "can_invite", "commission_estimated",
        "confirmed", "contact_then_cancel", "contact_unlocked", "deleted", "discount_available",
        "driver_verified", "earned", "edited", "enabled", "expired", "fragile", "from_admin",
        "has_premium", "has_requisite", "has_tolls", "is_insider", "live", "night",
        "notify_by_default", "ok",
        "overdue", "paid", "peer_verified", "premium", "rating_shield", "read", "required",
        "responded", "return_ride_used", "rules_accepted", "settled", "sms_sent",
        "subscription_active", "verified", "with_kids",
    )

    /** вложенные списки — пустые: цикл разбора должен пережить и это */
    private val arrayKeys = listOf(
        "achievements", "benefits", "by_city", "by_day", "cities", "drivers", "fee_tier_trips",
        "fee_tiers", "options", "parcels", "placements", "prefs", "price_factors", "reviews",
        "rides", "routes", "weeks", "zones",
    )

    /** вложенные объекты — пустые: поля возьмут значения по умолчанию */
    private val objectKeys = listOf(
        "application", "boost", "breakdown", "by_role", "coupon", "courier", "donate", "from",
        "funnel", "how",
        "message", "money", "next", "night_note", "offer", "order", "partner", "payee", "profile", "promo",
        "promo_note", "rank", "rating", "sbp", "settlement", "statement", "surge_note", "title", "to",
        "top_route", "user",
    )

    /** Поля, где важно конкретное значение: иначе разбор уйдёт не в ту ветку. */
    private val exactKeys = mapOf(
        "category" to "regular", "condition" to "clear", "currency" to "RUB", "day" to "2026-08-04",
        "kind" to "regular", "lang" to "ru", "method" to "sbp", "period" to "week",
        "platform" to "android", "role" to "driver", "state" to "ok", "status" to "active",
        "target" to "ride", "traffic_type" to "jams", "type" to "message",
    )

    /** Объект, в котором есть ВСЁ, что приложение умеет читать. */
    private fun fullItem(): JSONObject = JSONObject().apply {
        numberKeys.forEach { put(it, 7) }
        stringKeys.forEach { put(it, "текст") }
        dateKeys.forEach { put(it, "2026-08-05T08:00:00") }
        boolKeys.forEach { put(it, true) }
        arrayKeys.forEach { put(it, JSONArray()) }
        objectKeys.forEach { put(it, JSONObject()) }
        exactKeys.forEach { (k, v) -> put(k, v) }
        put("phone", "+79990000001")
        put("driver_phone", "+79990000001")
        put("url", "https://example.org/x")
        put("confirmation_url", "https://example.org/pay")
    }

    private fun fullBody(): String {
        val item = fullItem()
        // Часть ручек отдаёт список, часть — один объект. Кладём и так, и так.
        return JSONObject(item.toString()).put("items", JSONArray().put(fullItem())).toString()
    }

    /** Ответ, в котором нет ни одного поля: так выглядит переименование поля на сервере. */
    private fun emptyBody(): String =
        JSONObject().put("items", JSONArray().put(JSONObject())).toString()

    private fun okResponse() = MockResponse().setResponseCode(200).setBody(fullBody())

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
        Ep("confirmDebt", "POST", "/admin/debts/1/confirm") { ApiClient.confirmDebt(debtId = 1, amountKop = 50_000) },
        Ep("rejectDebt", "POST", "/admin/debts/1/reject") { ApiClient.rejectDebt(debtId = 1, amountKop = 50_000) },
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
    fun `ответ без единого поля не роняет разбор`() = runBlocking {
        // Поле переименовали на сервере или выключили фичу — приложение обязано подставить
        // значения по умолчанию и жить дальше, а не упасть с пустым экраном.
        val bad = mutableListOf<String>()
        for (ep in endpoints()) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBody()))
            val res = runCatching { ep.call() }
            drain()
            if (res.isFailure) bad += "${ep.name}: упал на ответе без полей — ${res.exceptionOrNull()}"
            val r = res.getOrNull()
            if (r is Result<*> && r.isFailure) {
                val e = r.exceptionOrNull()
                // ApiException тут быть не может (ответ 200) — значит это упавший разбор.
                if (e !is ApiException) bad += "${ep.name}: разбор пустого ответа дал ошибку — $e"
            }
        }
        assertTrue("Разбор ломается на ответе без полей:\n" + bad.joinToString("\n"), bad.isEmpty())
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
