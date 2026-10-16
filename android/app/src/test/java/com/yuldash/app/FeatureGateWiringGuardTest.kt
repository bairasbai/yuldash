package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож: каждая дверь в направление, которое сервер включает отдельно (запуск волнами,
 * 13.10.2026), стоит за `FeatureGate` — включая вход по пушу и старой ссылке.
 *
 * Зачем сторож, а не только экранный тест. Дверей много и они в разных файлах: вкладка
 * режима, профиль, навигация по экранам. Забытая одна — и человек из волны 1 попадает
 * в кабинет рекламы, который сервер отклонит, хотя все остальные двери закрыты. Снаружи
 * это не видно, пока кто-то не нажмёт. Заодно сторож следит за обратным: админка флагами
 * не закрывается — Александр готовит направления заранее.
 */
class FeatureGateWiringGuardTest {

    private val root = File("src/main/java/com/yuldash/app")
    private fun src(name: String): String = File(root, name).readText()

    /** Ветка экрана в `when` начинается с гейта (`FeatureGate` или его обёртки `ScreenGate`). */
    private fun gated(code: String, branch: String): Boolean =
        Regex("""${Regex.escape(branch)}\s*->\s*(?:FeatureGate|ScreenGate)\(""").containsMatchIn(code)

    @Test
    fun `кабинеты рекламы и бизнеса и витрина скидок за выключателями`() {
        val app = src("YuldashApp.kt")
        val missing = listOf("Screen.AdsCabinet", "Screen.AdEditor", "Screen.Coupons", "Screen.PartnerCabinet")
            .filterNot { gated(app, it) }
        assertTrue("эти экраны открываются в обход выключателя направления: $missing", missing.isEmpty())
    }

    @Test
    fun `отправка посылок и попутная лента курьера — за выключателями`() {
        assertTrue(
            "вкладка «Отправить» открывает форму в обход выключателя",
            src("ParcelsScreen.kt").contains("0 -> ParcelsSendOrSoon("),
        )
        assertTrue(
            "«Режим курьера» без работы курьером обходит выключатель",
            src("CourierScreen.kt").contains("!courierOn || application?.status != \"approved\" -> CourierPoputkaOrApply("),
        )
    }

    @Test
    fun `работа курьера за выключателями, набор курьеров — открыт`() {
        val nav = src("AppNavOrders.kt")
        val missing = listOf("Screen.Courier", "Screen.CourierEarnings").filterNot { gated(nav, it) }
        assertTrue("курьерские экраны открываются в обход выключателя: $missing", missing.isEmpty())
        assertTrue(
            "анкету курьера закрывать нельзя: набор идёт и до запуска",
            !gated(nav, "Screen.CourierOnboarding"),
        )
        assertTrue(
            "отдельный экран посылок должен знать, что делать с «найти попутку»",
            nav.contains("Screen.Parcels -> ParcelsScreen(onBack = { onBack() }, onFindRide = onFindRide)"),
        )
    }

    @Test
    fun `вкладка «Курьер» на главной за выключателем доставки`() {
        val hub = src("ModeSwitchHome.kt")
        assertTrue("вкладка режима «Курьер» открывается в обход выключателя", gated(hub, "RideMode.Courier"))
        assertTrue("реклама на карте должна гаснуть вместе с флагом", hub.contains("ads = visibleAds"))
    }

    @Test
    fun `профиль не зовёт в закрытые направления`() {
        val profile = src("ProfileScreen.kt").lines()
        fun lineWith(text: String) = profile.firstOrNull { it.contains(text) }.orEmpty()
        assertTrue(
            "плитка «Скидки по пути» видна при выключенных купонах",
            lineWith("appText(\"Скидки по пути\"").trimStart().startsWith("if (features.isOn(Feature.Business))"),
        )
        assertTrue(
            "плитка «Посылки» видна при выключенной доставке",
            lineWith("appText(\"Посылки\", \"Бандеролдәр\"), appText(\"Отправить с попутчиком").trimStart()
                .startsWith("if (features.deliveryOn)"),
        )
        assertTrue(
            "плитка «Режим курьера» видна при выключенной доставке",
            lineWith("appText(\"Режим курьера\", \"Курьер режимы\"), appText(\"Возить заказы").trimStart()
                .startsWith("if (features.deliveryOn)"),
        )
        assertTrue(
            "кабинет бизнеса виден при выключенных купонах",
            lineWith("appText(\"Мой бизнес\", \"Минең бизнес\"), appText(\"Купоны, подписка").contains("features.isOn(Feature.Business)"),
        )
        assertTrue(
            "кабинет рекламы виден при выключенной рекламе",
            lineWith("appText(\"Кабинет рекламы\"").contains("features.isOn(Feature.Ads)"),
        )
        assertTrue(
            "реклама в профиле должна гаснуть вместе с флагом",
            profile.any { it.contains("val profileAd = if (features.isOn(Feature.Ads))") },
        )
    }

    @Test
    fun `кабинеты прячут рабочие двери такси при выключенном такси`() {
        val code = src("ProfileScreen.kt")
        val profile = code.lines()
        fun lineWith(text: String) = profile.firstOrNull { it.contains(text) }.orEmpty()
        listOf(
            "appText(\"Мои предзаказы\"",
            "appText(\"Мои поездки на такси\"",
            "appText(\"Мои поездки такси\"",
            "appText(\"Готовность к работе\"",
        ).forEach { title ->
            assertTrue(
                "строка $title видна при выключенном такси",
                lineWith(title).trimStart().startsWith("if (taxiOn) SettingsNavRow("),
            )
        }
        assertTrue(
            "карточка блокеров линии видна при выключенном такси",
            Regex("""if \(taxiOn\) item \{\s*DriverBlockersCard\(""").containsMatchIn(code),
        )
        assertTrue(
            "тумблер «Я на линии» виден при выключенном такси",
            code.contains("if (taxiOn && taxiApproved) {"),
        )
        assertTrue(
            "панель такси (заработок, смена) видна при выключенном такси",
            code.contains("if (taxiOn && workday != null && taxiApproved) {"),
        )
        assertTrue(
            "фоновая слежка линии (TaxiLineService) может запуститься при выключенном такси",
            code.contains("val taxiAllowed = taxiOn && (!taxiAppLoaded || taxiApp?.status == \"approved\")"),
        )
        assertTrue(
            "оба кабинета обязаны знать флаг такси",
            code.contains("taxiOn = taxiOn,") && code.contains("val taxiOn = rememberFeatures().isOn(Feature.Taxi)"),
        )
        assertTrue(
            "анкету таксиста закрывать нельзя: набор идёт и до запуска",
            code.contains("if (taxiOn || !taxiApproved) item {") &&
                code.contains("TaxiOnboardingCta(taxiApplication, onTaxiOnboarding)"),
        )
    }

    @Test
    fun `админка выключателями не закрывается`() {
        val app = src("YuldashApp.kt") + src("AppNavAdmin.kt")
        val wrongly = Regex("""Screen\.(Admin\w+)\s*->\s*(?:FeatureGate|ScreenGate)\(""").findAll(app).map { it.groupValues[1] }.toList()
        assertTrue("админские экраны спрятаны за выключателем: $wrongly", wrongly.isEmpty())
    }

    @Test fun `старые купоны и погашение идут через owner adapters при закрытом бизнесе`() {
        val app = src("YuldashApp.kt")
        assertTrue(app.contains("closedContent = { ExistingCouponHistoryScreen("))
        assertTrue(app.contains("closedContent = { ExistingPartnerRedeemScreen("))
        val owners = src("ExistingBenefits.kt")
        assertTrue(owners.contains("MyCouponsTab(onFindRide, allowNewCoupons = false, owner = owner)"))
        assertTrue(owners.contains("RedeemDialog(onDismiss = { owner.publish { redeem = false } }, owner = owner)"))
        assertTrue(src("CouponsScreen.kt").contains("launchBenefitOperation(scope, owner, { ApiClient.getMyCoupons() })"))
        assertTrue(src("PartnerCabinetScreen.kt").contains("launchBenefitOperation(scope, owner, { ApiClient.redeemCoupon(code.trim()) })"))
        assertTrue(src("ProfileScreen.kt").contains("ExistingBenefitActions(onCoupons, onPartnerCabinet)"))
    }
}
