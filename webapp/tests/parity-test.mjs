// Постоянная сверка Android Screen → маршрут PWA.
// Новый экран приложения без веб-маршрута должен ломать проверку сразу, а не
// обнаруживаться ручным просмотром через несколько релизов.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? `\n    ${extra}` : ""}`);
};

const ROUTES = {
  Splash: "/splash",
  Intro: "/intro",
  Onboarding: "/onboarding",
  Login: "/login",
  Home: "/map",
  CreateRide: "/create-ride",
  Support: "/support-yuldash",
  Boost: "/boost",
  Booking: "/booking/:id",
  ActiveTrip: "/trip/:id",
  Sos: "/sos",
  CreateRequest: "/request",
  VerifyDriver: "/verify-driver",
  Notifications: "/notifications",
  Safety: "/safety",
  Settings: "/settings",
  Privacy: "/privacy",
  MyData: "/my-data",
  Rules: "/rules",
  PaymentInfo: "/payment-info",
  PaymentMethods: "/payment-methods",
  PricingInfo: "/pricing",
  Blocklist: "/blocklist",
  Report: "/report",
  Filters: "/filters",
  AdminCabinet: "/admin",
  AdminRequest: "/admin/request",
  AdminResponses: "/admin/responses",
  AdminDrivers: "/admin/drivers",
  AdminReports: "/admin/reports",
  AdminTextFlags: "/admin/text-flags",
  AdminSupport: "/admin/support",
  AdminPaymentRequests: "/admin/payment-requests",
  RequestsFeed: "/requests-feed",
  RequestResponses: "/requests/:id/responses",
  Help: "/help",
  PassengerCabinet: "/cabinet",
  DriverCabinet: "/driver",
  AdsCabinet: "/ads",
  SimpleMode: "/simple",
  VoiceRequest: "/voice",
  FamilyOrder: "/family-order",
  TrustedContacts: "/trusted",
  RepeatTrip: "/repeat",
  CallbackHelp: "/callback",
  AppReview: "/app-review",
  AdminReviews: "/admin/reviews",
  AdminAds: "/admin/ads",
  AdEditor: "/ads/new",
  InstantOrder: "/taxi",
  InstantDriverTrip: "/taxi-drive",
  InstantChat: "/taxi-chat/:orderId",
  TaxiOnboarding: "/taxi-onboarding",
  AdminTaxi: "/admin/taxi",
  AdminWaitlist: "/admin/waitlist",
  AdminTaxiPulse: "/admin/taxi-pulse",
  IncomeCalculator: "/admin/income",
  DriverProfile: "/drivers/:id",
  RouteWatches: "/route-watches",
  Trust: "/trust",
  Invites: "/invites",
  Consents: "/consents",
  MyStats: "/stats",
  ClinicRides: "/clinics",
  Coupons: "/coupons",
  PartnerCabinet: "/partner",
  AdminPartners: "/admin/partners",
  AdminModeration: "/admin/moderation",
  PromoCode: "/promo",
  AdminPromo: "/admin/promo",
  Parcels: "/parcels",
  AdminParcels: "/admin/parcels",
  CourierOnboarding: "/courier-onboarding",
  Courier: "/courier",
  AdminCourier: "/admin/courier",
  Wallet: "/wallet",
  DriverEarnings: "/earnings",
  SavedPlaces: "/places",
  TripReceipt: "/receipt/:id",
  TaxiReceipt: "/taxi-receipt/:orderId",
  DriverTaxiRides: "/taxi-rides",
  AdminSos: "/admin/sos",
  TaxiDocuments: "/taxi-docs",
  CarPhoto: "/car-photo",
  PretripCheck: "/pretrip",
  FairnessCenter: "/fairness",
  IncidentDetail: "/incidents/:id",
  AdminIncidents: "/admin/incidents",
  AdminRatings: "/admin/ratings",
  CourierEarnings: "/courier-earnings",
  SupportTickets: "/support",
  SupportTicket: "/support/:id",
  ScheduledOrders: "/scheduled",
  DriverResponses: "/my-responses",
  MyTaxiTrips: "/my-taxi",
  ParcelChat: "/parcel-chat/:parcelId",
};

const android = readFileSync(
  join(ROOT, "android/app/src/main/java/com/yuldash/app/MainActivity.kt"),
  "utf8"
);
const enumBody = android.match(/enum class Screen\s*\{([\s\S]*?)\n\}/)?.[1] ?? "";
const androidScreens = [...enumBody.matchAll(/^\s*([A-Z][A-Za-z0-9_]*)\s*(?:,|\/\/|$)/gm)].map((m) => m[1]);
const mappedScreens = Object.keys(ROUTES);
const missingMappings = androidScreens.filter((name) => !(name in ROUTES));
const staleMappings = mappedScreens.filter((name) => !androidScreens.includes(name));
check(androidScreens.length > 90, "прочитан enum Screen из Android", `экранов: ${androidScreens.length}`);
check(missingMappings.length === 0, "каждый Android-экран сопоставлен с PWA", missingMappings.join(", "));
check(staleMappings.length === 0, "карта паритета не содержит старых экранов", staleMappings.join(", "));

const app = readFileSync(join(ROOT, "webapp/src/App.tsx"), "utf8");
const missingRoutes = Object.entries(ROUTES)
  .filter(([, route]) => !app.includes(`path="${route}"`))
  .map(([screen, route]) => `${screen} → ${route}`);
check(missingRoutes.length === 0, "для всей карты есть реальные маршруты PWA", missingRoutes.join("; "));

const nav = readFileSync(join(ROOT, "webapp/src/components/BottomNav.tsx"), "utf8");
const navOrder = ["/map", "/rides", "/my-requests", "/chat", "/profile"];
const navPositions = navOrder.map((route) => nav.indexOf(`to: "${route}"`));
check(
  navPositions.every((position) => position >= 0) && navPositions.every((position, i) => i === 0 || navPositions[i - 1] < position),
  "нижние вкладки идут в Android-порядке: карта · поездки · заявка · чат · профиль"
);
check(
  nav.includes("nav-pill") &&
    app.includes("const showBottomNav = onMainTab && !taxiOrderOnScreen") &&
    app.includes("{showBottomNav && <BottomNav"),
  "нижняя навигация имеет золотую плашку и видна только на пяти главных вкладках"
);
check(
  app.includes('"/map", "/rides", "/my-requests", "/chat", "/profile"') &&
    !app.includes('path="/my-data" element={<BottomNav'),
  "вложенные экраны не получают нижнюю навигацию Android HomeShell"
);

const css = readFileSync(join(ROOT, "webapp/src/ui.css"), "utf8");
check(
  css.includes(".nav-item.is-active .nav-pill") && css.includes("background: var(--canon-gold)"),
  "активная вкладка использует CanonGold, как Android"
);

const myDataApi = readFileSync(join(ROOT, "webapp/src/api/myData.ts"), "utf8");
check(
  ["/me/data", "/me/export?lang=", "/me/driver-docs/delete"].every((endpoint) => myDataApi.includes(endpoint)),
  "«Мои данные» подключены к тем же трём API, что Android"
);
const payment = readFileSync(join(ROOT, "webapp/src/screens/PaymentMethodsScreen.tsx"), "utf8");
check(
  payment.includes("useActiveTaxiOrder") &&
    payment.includes("queryOrderId || activeOrder?.id") &&
    payment.includes("setPaymentMethod(orderId, method)"),
  "экран оплаты сам подхватывает живой заказ и подтверждает смену сервером"
);

const payPicker = readFileSync(join(ROOT, "webapp/src/components/PayMethodPicker.tsx"), "utf8");
check(payPicker.includes('return "cash"'), "первый способ расчёта совпадает с Android: наличные");

const instantApi = readFileSync(join(ROOT, "webapp/src/api/instant.ts"), "utf8");
const driverActions = readFileSync(join(ROOT, "webapp/src/components/TaxiDriverTripActions.tsx"), "utf8");
check(
  instantApi.includes("payment_changed?: boolean") &&
    instantApi.includes("payment_ack_overdue?: boolean") &&
    instantApi.includes("/payment/ack") &&
    driverActions.includes("DriverPaymentMethod") &&
    driverActions.includes("ackPaymentMethod(order.id)"),
  "водитель видит способ расчёта и подтверждает его смену"
);

const driverTaxi = readFileSync(join(ROOT, "webapp/src/screens/InstantDriverTripScreen.tsx"), "utf8");
check(
  instantApi.includes("blocked?: string | null") &&
    driverTaxi.includes("setOfferBlocked(r.blocked ?? null)") &&
    driverTaxi.includes("presenceFails >= 2") &&
    driverTaxi.includes("Нет связи · переподключаемся"),
  "пауза выдачи и потеря heartbeat показаны водителю честно"
);

const activeBar = readFileSync(join(ROOT, "webapp/src/components/ActiveTaxiBar.tsx"), "utf8");
check(
  activeBar.includes("fetchMyOrders(8)") && activeBar.includes("fetchInstantOrder(order.id)") && app.includes("<ActiveTaxiBar"),
  "живая поездка остаётся полоской над главными вкладками"
);

const myRequests = readFileSync(join(ROOT, "webapp/src/screens/MyRequestsScreen.tsx"), "utf8");
check(
  app.includes('path="/my-requests"') &&
    myRequests.includes("fetchMyRequests") &&
    myRequests.includes("cancelRequest") &&
    myRequests.includes("/responses") &&
    myRequests.includes("/edit"),
  "вкладка «Заявка» открывает список с откликами, правкой и отменой"
);
check(
  myRequests.includes("fetchMatchRides") &&
    myRequests.includes("MatchingRidesSection") &&
    myRequests.includes("<RideSheet"),
  "каждая заявка показывает автоподбор поездок и открывает бронирование, как Android"
);

const trust = readFileSync(join(ROOT, "webapp/src/screens/TrustScreen.tsx"), "utf8");
const simple = readFileSync(join(ROOT, "webapp/src/screens/SimpleModeScreen.tsx"), "utf8");
const cabinet = readFileSync(join(ROOT, "webapp/src/screens/PassengerCabinetScreen.tsx"), "utf8");
check(
  trust.includes('level === 1) navigate("/profile/edit")') &&
    trust.includes('level === 2) navigate("/verify-driver")') &&
    trust.includes('navigate("/consents")'),
  "экран доверия ведёт к следующему уровню и согласиям"
);
check(simple.includes('to: "/repeat"'), "простой режим содержит частые маршруты");
check(cabinet.includes('navigate("/wallet")'), "кошелёк пассажира открывает настоящий экран");

if (bad) process.exit(1);
