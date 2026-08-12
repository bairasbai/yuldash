// ================================================================
//  «Мой бизнес» — кабинет партнёра «Скидки по пути» (coupons.py). RequireAuth.
//  Ветвление по GET /partner/me:
//   • нет бизнеса → форма регистрации (POST /partner) → на модерацию;
//   • pending → «на проверке»;
//   • rejected → причина + правка данных (POST /partner/{id});
//   • active → кабинет: подписка (GET /partner/plans + POST /partner/subscribe,
//     СБП «на доверии»), выписка (statement), купоны CRUD
//     (GET/POST /partner/coupons, POST …/{id}, …/{id}/status),
//     погашение кода клиента (POST /coupons/redeem).
//  Premium-метка купона — только при has_premium (premium-подписка).
//  Появится на проде после мержа release → мягкая деградация 404/405.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchPartnerMe,
  registerPartner,
  updatePartner,
  fetchPartnerPlans,
  subscribePartner,
  fetchPartnerCoupons,
  createPartnerCoupon,
  updatePartnerCoupon,
  setPartnerCouponStatus,
  redeemCoupon,
  type PartnerMe,
  type Partner,
  type PartnerPlan,
  type PartnerCoupon,
  type PartnerIn,
  type CouponIn,
  type RedeemResult,
} from "../api/coupons";
import { rubLabel } from "../utils/format";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import {
  IconStar,
  IconStore,
  IconCar,
  IconHospital,
  IconGift,
  IconHeart,
  IconCheck,
  IconClock,
  IconReceipt,
  IconTicket,
  IconWarn,
} from "../components/Icons";

type Status = "loading" | "error" | "soon" | "ready";
type Mode = "cabinet" | "biz-form" | "coupon-form";

/** Категории бизнеса (код → линиевая иконка + подпись). */
const CATEGORIES: { code: string; Icon: typeof IconStore; ru: string; ba: string }[] = [
  { code: "cafe", Icon: IconStore, ru: "Кафе/еда", ba: "Кафе/аш" },
  { code: "shop", Icon: IconStore, ru: "Магазин", ba: "Магазин" },
  { code: "auto", Icon: IconCar, ru: "Авто", ba: "Авто" },
  { code: "beauty", Icon: IconStar, ru: "Красота", ba: "Матурлыҡ" },
  { code: "health", Icon: IconHospital, ru: "Здоровье", ba: "Һаулыҡ" },
  { code: "fun", Icon: IconGift, ru: "Досуг", ba: "Ял" },
  { code: "other", Icon: IconStore, ru: "Другое", ba: "Башҡа" },
];

/** Подпись статуса купона + класс бейджа. */
function couponStatusMeta(s: string, ru: boolean): { label: string; cls: string } {
  switch (s) {
    case "active":
      return { label: ru ? "Показывается" : "Күрһәтелә", cls: "badge badge--mint" };
    case "paused":
      return { label: ru ? "На паузе" : "Туҡтатылған", cls: "badge" };
    case "archived":
      return { label: ru ? "В архиве" : "Архивта", cls: "badge" };
    default:
      return { label: ru ? "Черновик" : "Ҡаралама", cls: "badge" };
  }
}

const EMPTY_BIZ: PartnerIn = { name: "", category: "other", city: "", address: "", phone: "", description: "" };
const EMPTY_COUPON: CouponIn = { title: "", discount_text: "", description: "", city: "", limit_total: 0, limit_per_user: 1, premium: false };

export default function PartnerCabinetScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [me, setMe] = useState<PartnerMe | null>(null);
  const [plans, setPlans] = useState<PartnerPlan[]>([]);
  const [coupons, setCoupons] = useState<PartnerCoupon[]>([]);
  const [mode, setMode] = useState<Mode>("cabinet");

  // Формы бизнеса и купона.
  const [biz, setBiz] = useState<PartnerIn>(EMPTY_BIZ);
  const [coupon, setCoupon] = useState<CouponIn>(EMPTY_COUPON);
  const [editCouponId, setEditCouponId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Подписка.
  const [subBusy, setSubBusy] = useState<string | null>(null);
  const [subPaid, setSubPaid] = useState<{ amount: number } | null>(null);

  // Погашение кода.
  const [code, setCode] = useState("");
  const [redeemBusy, setRedeemBusy] = useState(false);
  const [redeemRes, setRedeemRes] = useState<RedeemResult | null>(null);
  const [redeemErr, setRedeemErr] = useState<string | null>(null);

  const partner: Partner | null = me?.partner ?? null;

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchPartnerMe(signal)
      .then(async (res) => {
        setMe(res);
        if (res.partner?.status === "active") {
          const [pl, cp] = await Promise.all([
            fetchPartnerPlans(signal).catch(() => [] as PartnerPlan[]),
            fetchPartnerCoupons(signal).catch(() => [] as PartnerCoupon[]),
          ]);
          setPlans(pl);
          setCoupons(cp);
        }
        setStatus("ready");
        setMode("cabinet");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(
          e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error"
        );
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // ---- Сохранение бизнеса (регистрация или правка) ----
  async function saveBiz() {
    if (!biz.name.trim() || !biz.city.trim() || busy) return;
    setBusy(true);
    setFormError(null);
    try {
      if (partner) await updatePartner(partner.id, biz);
      else await registerPartner(biz);
      load();
    } catch (e) {
      setFormError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Попробуй снова.", "Һаҡларға булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  // ---- Подписка (СБП «на доверии») ----
  async function subscribe(plan: string) {
    setSubBusy(plan);
    setFormError(null);
    try {
      const res = await subscribePartner(plan);
      setSubPaid({ amount: Math.round(res.amount_kop / 100) });
    } catch (e) {
      setFormError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось оформить подписку. Попробуй снова.", "Яҙылыуҙы рәсмиләштерергә булманы. Ҡабат ҡара.")
      );
    } finally {
      setSubBusy(null);
    }
  }

  // ---- Купон: сохранить (создать/править) ----
  async function saveCoupon() {
    if (!coupon.title.trim() || busy) return;
    setBusy(true);
    setFormError(null);
    try {
      if (editCouponId != null) await updatePartnerCoupon(editCouponId, coupon);
      else await createPartnerCoupon(coupon);
      const cp = await fetchPartnerCoupons();
      setCoupons(cp);
      setMode("cabinet");
    } catch (e) {
      setFormError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить купон. Попробуй снова.", "Купонды һаҡларға булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function toggleCouponStatus(c: PartnerCoupon) {
    const next = c.status === "active" ? "paused" : "active";
    try {
      const updated = await setPartnerCouponStatus(c.id, next);
      setCoupons((list) => list.map((x) => (x.id === c.id ? updated : x)));
    } catch {
      /* мягко — оставляем как есть */
    }
  }

  async function onRedeem() {
    const c = code.trim().toUpperCase();
    if (!c || redeemBusy) return;
    setRedeemBusy(true);
    setRedeemErr(null);
    setRedeemRes(null);
    try {
      const res = await redeemCoupon(c);
      setRedeemRes(res);
      setCode("");
      const cp = await fetchPartnerCoupons().catch(() => coupons);
      setCoupons(cp);
    } catch (e) {
      setRedeemErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Код не найден или уже погашен.", "Код табылманы йәки ҡулланылған инде.")
      );
    } finally {
      setRedeemBusy(false);
    }
  }

  function startRegister() {
    setBiz(EMPTY_BIZ);
    setFormError(null);
    setMode("biz-form");
  }
  function startEditBiz() {
    if (!partner) return;
    setBiz({
      name: partner.name,
      category: partner.category || "other",
      city: partner.city,
      address: partner.address,
      phone: partner.phone,
      description: partner.description,
    });
    setFormError(null);
    setMode("biz-form");
  }
  function startNewCoupon() {
    setEditCouponId(null);
    setCoupon({ ...EMPTY_COUPON, city: partner?.city || "" });
    setFormError(null);
    setMode("coupon-form");
  }
  function startEditCoupon(c: PartnerCoupon) {
    setEditCouponId(c.id);
    setCoupon({
      title: c.title,
      discount_text: c.discount_text,
      description: c.description,
      city: c.city,
      limit_total: c.limit_total,
      limit_per_user: c.limit_per_user,
      premium: c.premium,
    });
    setFormError(null);
    setMode("coupon-form");
  }

  // ================= Экраны состояний =================
  if (status === "loading") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнесым")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }
  if (status === "soon") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнесым")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon"><IconStore size={34} /></div>
          <h2>{appText("Бизнес-кабинет скоро", "Бизнес-кабинет тиҙҙән")}</h2>
          <p>{appText("Раздел «Скидки по пути» для бизнеса включится после обновления сервиса.", "Бизнес өсөн «Юлда ташламалар» бүлеге яңыртыуҙан һуң эшләй башлар.")}</p>
        </div>
      </>
    );
  }
  if (status === "error") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнесым")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      </>
    );
  }

  // Заявка на подписку создана (СБП «на доверии»)
  if (subPaid) {
    return (
      <>
        <SubHeader title={appText("Оплата подписки", "Яҙылыу түләүе")} onBack={() => { setSubPaid(null); load(); }} />
        <div className="state" style={{ paddingTop: 20 }}>
          <div className="state__icon"><IconReceipt size={34} /></div>
          <h2>{appText("Заявка на оплату создана", "Түләү заявкаһы булдырылды")}</h2>
          <p>
            {appText(
              `Переведи ${subPaid.amount.toLocaleString("ru-RU")} ₽ по СБП «на доверии». Как получим — подписка включится, и купоны появятся в витрине.`,
              `${subPaid.amount.toLocaleString("ru-RU")} ₽ СБП аша «ышаныс менән» күсер. Алғас — яҙылыу ҡабына, купондар витринала күренә.`
            )}
          </p>
          <button type="button" className="btn-soft" style={{ minHeight: 48 }} onClick={() => navigate("/payment-info")}>
            {appText("Как оплатить", "Нисек түләргә")}
          </button>
          <button type="button" className="btn-primary" style={{ marginTop: 10 }} onClick={() => { setSubPaid(null); load(); }}>
            {appText("Готово", "Әҙер")}
          </button>
        </div>
      </>
    );
  }

  // ================= Форма бизнеса (регистрация/правка) =================
  if (mode === "biz-form") {
    const isNew = !partner;
    return (
      <>
        <SubHeader
          title={isNew ? appText("Расскажи о бизнесе", "Бизнес тураһында һөйлә") : appText("Данные бизнеса", "Бизнес мәғлүмәте")}
          subtitle={appText("Проверим и подключим к «Скидкам по пути»", "Тикшереп «Юлда ташламалар»ға ҡушабыҙ")}
          onBack={() => setMode("cabinet")}
        />
        <div className="form">
          <label className="field">
            <span className="field__label">{appText("Название", "Исем")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={120}
              value={biz.name}
              onChange={(e) => setBiz({ ...biz, name: e.target.value })}
              placeholder={appText("Например, Кафе «Юлдаш»", "Мәҫәлән, «Юлдаш» кафеһы")}
            />
          </label>

          <div className="field">
            <span className="field__label">{appText("Категория", "Категория")}</span>
            <div className="chips">
              {CATEGORIES.map((c) => (
                <button
                  key={c.code}
                  type="button"
                  className={"chip" + (biz.category === c.code ? " chip--on" : "")}
                  onClick={() => setBiz({ ...biz, category: c.code })}
                >
                  <c.Icon size={16} /> {ru ? c.ru : c.ba}
                </button>
              ))}
            </div>
          </div>

          <label className="field">
            <span className="field__label">{appText("Город", "Ҡала")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={80}
              value={biz.city}
              onChange={(e) => setBiz({ ...biz, city: e.target.value })}
              placeholder={appText("Например, Уфа", "Мәҫәлән, Өфө")}
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Адрес", "Адрес")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={200}
              value={biz.address}
              onChange={(e) => setBiz({ ...biz, address: e.target.value })}
              placeholder={appText("Улица, дом", "Урам, йорт")}
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Телефон бизнеса", "Бизнес телефоны")}</span>
            <input
              className="field__input"
              type="tel"
              maxLength={40}
              value={biz.phone}
              onChange={(e) => setBiz({ ...biz, phone: e.target.value })}
              placeholder="+7…"
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Описание", "Тасуирлама")}</span>
            <textarea
              className="field__input field__area"
              maxLength={2000}
              value={biz.description}
              onChange={(e) => setBiz({ ...biz, description: e.target.value })}
              placeholder={appText("Пара слов о заведении", "Заведение тураһында бер-ике һүҙ")}
            />
          </label>
        </div>

        {formError && <div className="auth__error">{formError}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={saveBiz}
          disabled={busy || !biz.name.trim() || !biz.city.trim()}
        >
          {busy
            ? appText("Сохраняем…", "Һаҡлайбыҙ…")
            : isNew
            ? appText("Отправить на проверку", "Тикшереүгә ебәреү")
            : appText("Сохранить", "Һаҡлау")}
        </button>
        <p className="receipt__foot">
          {appText(
            "Юлдаш берёт деньги только с бизнеса (подписка), а не с пассажиров. Скидку по купону даёшь ты сам.",
            "Юлдаш аҡсаны тик бизнестан ала (яҙылыу), юлаусынан түгел. Купон буйынса ташламаны үҙең бирәһең."
          )}
        </p>
      </>
    );
  }

  // ================= Форма купона (создать/править) =================
  if (mode === "coupon-form") {
    return (
      <>
        <SubHeader
          title={editCouponId != null ? appText("Правка купона", "Купонды төҙәтеү") : appText("Новый купон", "Яңы купон")}
          subtitle={appText("Реальная скидка для попутчиков", "Юлдаштар өсөн ысын ташлама")}
          onBack={() => setMode("cabinet")}
        />
        <div className="form">
          <label className="field">
            <span className="field__label">{appText("Заголовок", "Исем")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={120}
              value={coupon.title}
              onChange={(e) => setCoupon({ ...coupon, title: e.target.value })}
              placeholder={appText("Например, Кофе в подарок", "Мәҫәлән, Бүләккә ҡәһүә")}
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Размер скидки", "Ташлама күләме")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={80}
              value={coupon.discount_text}
              onChange={(e) => setCoupon({ ...coupon, discount_text: e.target.value })}
              placeholder={appText("−20% или подарок", "−20% йәки бүләк")}
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Описание", "Тасуирлама")}</span>
            <textarea
              className="field__input field__area"
              maxLength={2000}
              value={coupon.description}
              onChange={(e) => setCoupon({ ...coupon, description: e.target.value })}
              placeholder={appText("Условия скидки", "Ташлама шарттары")}
            />
          </label>

          <label className="field">
            <span className="field__label">{appText("Город", "Ҡала")}</span>
            <input
              className="field__input"
              type="text"
              maxLength={80}
              value={coupon.city || ""}
              onChange={(e) => setCoupon({ ...coupon, city: e.target.value })}
              placeholder={partner?.city || appText("Город показа", "Күрһәтеү ҡалаһы")}
            />
          </label>

          <div className="field-row">
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Всего купонов", "Барлыҡ купон")}</span>
              <input
                className="field__input"
                type="number"
                min={0}
                value={coupon.limit_total ?? 0}
                onChange={(e) => setCoupon({ ...coupon, limit_total: Math.max(0, Number(e.target.value) || 0) })}
              />
            </label>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("На человека", "Бер кешегә")}</span>
              <input
                className="field__input"
                type="number"
                min={1}
                value={coupon.limit_per_user ?? 1}
                onChange={(e) => setCoupon({ ...coupon, limit_per_user: Math.max(1, Number(e.target.value) || 1) })}
              />
            </label>
          </div>
          <p className="field__label" style={{ fontWeight: 400, marginTop: -6 }}>
            {appText("0 — без общего ограничения.", "0 — дөйөм сикләүһеҙ.")}
          </p>

          {/* Premium-метка — только при premium-подписке */}
          {partner?.has_premium ? (
            <button
              type="button"
              className={"list-row list-row--check" + (coupon.premium ? " is-on" : "")}
              onClick={() => setCoupon({ ...coupon, premium: !coupon.premium })}
            >
              <span className="list-row__icon"><IconStar size={20} /></span>
              <div className="list-row__main">
                <div className="list-row__title">{appText("Premium-метка", "Premium билдәһе")}</div>
                <div className="list-row__sub">{appText("Купон выше и заметнее в витрине", "Купон витринала юғарыраҡ һәм күренеклерәк")}</div>
              </div>
              <span className={coupon.premium ? "badge badge--gold" : "badge"}>
                {coupon.premium ? appText("Вкл", "Ялғаулы") : appText("Выкл", "Һүнек")}
              </span>
            </button>
          ) : (
            <p className="field__label" style={{ fontWeight: 400 }}>
              {appText(
                "Premium-метка доступна на тарифе «Премиум».",
                "Premium билдәһе «Премиум» тарифында бар."
              )}
            </p>
          )}
        </div>

        {formError && <div className="auth__error">{formError}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={saveCoupon}
          disabled={busy || !coupon.title.trim()}
        >
          {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить купон", "Купонды һаҡлау")}
        </button>
        <p className="receipt__foot">
          {appText(
            "Новый купон создаётся черновиком — включи показ в кабинете, когда будешь готов.",
            "Яңы купон ҡаралама булып барлыҡҡа килә — әҙер булғас кабинетта күрһәтеүҙе ҡабыҙ."
          )}
        </p>
      </>
    );
  }

  // ================= Кабинет (по статусу бизнеса) =================
  return (
    <>
      <SubHeader
        title={appText("Мой бизнес", "Минең бизнесым")}
        subtitle={appText("Скидки по пути — для своих", "Юлда ташламалар — үҙебеҙ өсөн")}
        onBack={() => navigate(-1)}
      />

      {/* Нет бизнеса → приглашение зарегистрировать */}
      {!partner && (
        <>
          <div className="biz-banner">
            <div className="biz-banner__emoji"><IconStore size={28} /></div>
            <h2>{appText("Подключи свой бизнес", "Бизнесыңды ҡуш")}</h2>
            <p>
              {appText(
                "Разместил скидку — попутчики увидят её в «Скидках по пути». Платит бизнес, а не пассажиры.",
                "Ташлама ҡуйҙың — юлдаштар уны «Юлда ташламалар»ҙа күрер. Бизнес түләй, юлаусылар түгел."
              )}
            </p>
          </div>
          <button type="button" className="btn-primary submit-btn" onClick={startRegister}>
            {appText("Добавить бизнес", "Бизнес ҡушыу")}
          </button>
        </>
      )}

      {/* На модерации */}
      {partner?.status === "pending" && (
        <div className="biz-banner is-warn">
          <div className="biz-banner__emoji"><IconClock size={28} /></div>
          <h2>{appText("Бизнес на проверке", "Бизнес тикшереүҙә")}</h2>
          <p>{appText("Обычно это недолго. Как одобрим — сможешь выбрать тариф и разместить купоны.", "Ғәҙәттә оҙаҡ түгел. Раҫлағас — тариф һайлап, купондар ҡуя алаһың.")}</p>
        </div>
      )}

      {/* Отклонён */}
      {partner?.status === "rejected" && (
        <>
          <div className="biz-banner is-warn">
            <div className="biz-banner__emoji"><IconHeart size={28} /></div>
            <h2>{appText("Нужно поправить", "Төҙәтергә кәрәк")}</h2>
            <p>{partner.reject_reason || appText("Проверь данные бизнеса и отправь снова.", "Бизнес мәғлүмәтен тикшереп ҡабат ебәр.")}</p>
          </div>
          <button type="button" className="btn-primary submit-btn" onClick={startEditBiz}>
            {appText("Исправить данные", "Мәғлүмәтте төҙәтеү")}
          </button>
        </>
      )}

      {/* Активный бизнес → полный кабинет */}
      {partner?.status === "active" && (
        <>
          {/* Шапка бизнеса */}
          <div className="biz-item">
            <div className="biz-item__head">
              <div>
                <div className="biz-item__title">{partner.name}</div>
                <div className="biz-item__meta">
                  {[partner.city, partner.address].filter(Boolean).join(", ") || appText("Адрес не указан", "Адрес күрһәтелмәгән")}
                </div>
              </div>
              <button type="button" className="btn-soft" style={{ minHeight: 40 }} onClick={startEditBiz}>
                {appText("Изменить", "Үҙгәртеү")}
              </button>
            </div>
          </div>

          {/* Подписка */}
          <h2 className="section-title">{appText("Подписка", "Яҙылыу")}</h2>
          {partner.subscription_active ? (
            <div className="biz-item">
              <div className="biz-item__head">
                <div>
                  <div className="biz-item__title">{appText("Подписка активна", "Яҙылыу әүҙем")}</div>
                  <div className="biz-item__meta">
                    {partner.subscription_until
                      ? appText(
                          `Действует до ${new Date(partner.subscription_until).toLocaleDateString("ru-RU")}`,
                          `${new Date(partner.subscription_until).toLocaleDateString("ru-RU")} тиклем ғәмәлдә`
                        )
                      : ""}
                  </div>
                </div>
                <span className="badge badge--mint">{appText("Активна", "Әүҙем")}</span>
              </div>
            </div>
          ) : (
            <>
              <p className="receipt__foot" style={{ marginTop: 6 }}>
                {appText(
                  "Выбери тариф — купоны появятся в витрине после оплаты.",
                  "Тариф һайла — купондар түләүҙән һуң витринала күренә."
                )}
              </p>
              <div className="plan-grid">
                {plans.map((p) => (
                  <button
                    key={p.code}
                    type="button"
                    className={"plan-card" + (subBusy === p.code ? " is-active" : "")}
                    onClick={() => subscribe(p.code)}
                    disabled={subBusy !== null}
                  >
                    <div className="plan-card__icon">{p.premium ? <IconStar size={22} /> : <IconStore size={22} />}</div>
                    <div className="plan-card__title">{ru ? p.title : p.title_ba}</div>
                    <div className="plan-card__hours">{appText(`${p.period_days} дней`, `${p.period_days} көн`)}</div>
                    <div className="plan-card__price">{(p.amount_kop / 100).toLocaleString("ru-RU")} ₽</div>
                  </button>
                ))}
              </div>
              {formError && <div className="auth__error">{formError}</div>}
            </>
          )}

          {/* Выписка */}
          {me?.statement && (
            <>
              <h2 className="section-title">{appText("Выписка", "Иҫәп-хисап")}</h2>
              <div className="money-card">
                <div className="money-card__label">{appText("К оплате Юлдашу за погашения", "Ҡулланыуҙар өсөн Юлдашҡа түләргә")}</div>
                <div className="money-card__amount">{rubLabel(me.statement.amount_kop)}</div>
                <p className="money-card__hint">
                  {appText(
                    `Погашено купонов: ${me.statement.redeemed_total} · ${rubLabel(me.statement.fee_per_redemption_kop)} за каждое.`,
                    `Ҡулланылған купон: ${me.statement.redeemed_total} · һәр береһе өсөн ${rubLabel(me.statement.fee_per_redemption_kop)}.`
                  )}
                </p>
              </div>
            </>
          )}

          {/* Погашение кода клиента */}
          <h2 className="section-title">{appText("Погасить код", "Кодты ҡулланыу")}</h2>
          {redeemRes ? (
            <div className="biz-banner">
              <div className="biz-banner__emoji"><IconCheck size={28} /></div>
              <h2>{appText("Код погашен", "Код ҡулланылды")}</h2>
              <p>
                {redeemRes.coupon_title}
                {redeemRes.discount_text ? ` · ${redeemRes.discount_text}` : ""}
                {redeemRes.customer_name ? appText(` · ${redeemRes.customer_name}`, ` · ${redeemRes.customer_name}`) : ""}
              </p>
              <button type="button" className="btn-soft" style={{ minHeight: 46, marginTop: 12 }} onClick={() => setRedeemRes(null)}>
                {appText("Погасить ещё", "Тағы ҡулланыу")}
              </button>
            </div>
          ) : (
            <div className="invite-redeem" style={{ marginTop: 10 }}>
              <div className="invite-redeem__row">
                <input
                  className="auth__name"
                  type="text"
                  maxLength={12}
                  placeholder={appText("Код клиента", "Клиент коды")}
                  value={code}
                  onChange={(e) => {
                    setCode(e.target.value.toUpperCase().replace(/\s/g, ""));
                    setRedeemErr(null);
                  }}
                  aria-label={appText("Код купона", "Купон коды")}
                />
                <button type="button" className="btn-primary" onClick={onRedeem} disabled={redeemBusy || code.trim().length === 0}>
                  {redeemBusy ? appText("…", "…") : appText("Погасить", "Ҡулланыу")}
                </button>
              </div>
              {redeemErr && <div className="invite-redeem__msg">{redeemErr}</div>}
              <p className="invite-hint" style={{ marginTop: 12 }}>
                {appText(
                  "Клиент показывает код на кассе — ты вводишь его здесь. Мы покажем только имя, без телефона.",
                  "Клиент кассала кодты күрһәтә — һин уны бында индерәһең. Беҙ тик исемде күрһәтәбеҙ, телефонһыҙ."
                )}
              </p>
            </div>
          )}

          {/* Купоны */}
          <h2 className="section-title">{appText("Мои купоны", "Купондарым")}</h2>
          <button type="button" className="btn-primary submit-btn" style={{ marginTop: 4 }} onClick={startNewCoupon}>
            {appText("Создать купон", "Купон булдырыу")}
          </button>

          {coupons.length === 0 ? (
            <div className="state" style={{ paddingTop: 16 }}>
              <div className="state__icon"><IconTicket size={34} /></div>
              <p>{appText("Пока нет купонов. Создай первый — и он появится в витрине.", "Әле купон юҡ. Беренсен булдыр — ул витринала күренер.")}</p>
            </div>
          ) : (
            coupons.map((c) => {
              const m = couponStatusMeta(c.status, ru);
              return (
                <div key={c.id} className="biz-item">
                  <div className="biz-item__head">
                    <div>
                      <div className="biz-item__title">
                        {c.title}
                        {c.premium && <span className="badge badge--gold" style={{ marginLeft: 8 }}>PREMIUM</span>}
                      </div>
                      {c.discount_text && <div className="biz-item__meta" style={{ color: "var(--canon-star)", fontWeight: 700 }}>{c.discount_text}</div>}
                    </div>
                    <span className={m.cls}>{m.label}</span>
                  </div>

                  <div className="biz-item__stats">
                    <div className="biz-item__stat">
                      <b>{c.activations}</b>
                      <span>{appText("Активаций", "Активлаштырыу")}</span>
                    </div>
                    <div className="biz-item__stat">
                      <b>{c.redeemed_count}</b>
                      <span>{appText("Погашено", "Ҡулланылған")}</span>
                    </div>
                    <div className="biz-item__stat">
                      <b>{c.limit_total > 0 ? Math.max(0, c.limit_total - c.redeemed_count) : "∞"}</b>
                      <span>{appText("Осталось", "Ҡалды")}</span>
                    </div>
                  </div>

                  {/* Почему купон не виден людям. Без этого владелец думает, что сломался
                      сайт, и создаёт копию за копией — а модерация заворачивает их снова. */}
                  {(c.review === "held" || c.review === "blocked" || (c.reports_count ?? 0) > 0) && (
                    <div className="act-card act-card--warn" style={{ marginTop: 10 }}>
                      <div className="act-card__title">
                        <IconWarn size={18} />
                        {c.review === "blocked"
                          ? appText("Купон снят администратором", "Купонды администратор алды")
                          : c.review === "held"
                            ? appText("Текст не прошёл проверку", "Текст тикшереүҙе үтмәне")
                            : appText("На купон жалуются", "Купонға зарланалар")}
                      </div>
                      <p className="act-card__text" style={{ marginBottom: 0 }}>
                        {c.review_note
                          ? c.review_note
                          : c.review === "blocked"
                            ? appText(
                                "Поправь текст и сохрани — он снова уйдёт на проверку.",
                                "Текстты төҙәт тә һаҡла — ул яңынан тикшереүгә китә."
                              )
                            : appText(
                                "Убери телефон, ссылку или резкие слова — и сохрани.",
                                "Телефонды, һылтанманы йәки ҡаты һүҙҙәрҙе алып ташла ла һаҡла."
                              )}
                        {(c.reports_count ?? 0) > 0 && (
                          <>
                            <br />
                            {appText(
                              `Жалоб от людей: ${c.reports_count}`,
                              `Кешеләрҙән зарланыу: ${c.reports_count}`
                            )}
                          </>
                        )}
                      </p>
                    </div>
                  )}

                  <div className="biz-item__actions">
                    <button type="button" className="btn-soft" onClick={() => startEditCoupon(c)}>
                      {appText("Редактировать", "Төҙәтеү")}
                    </button>
                    {c.status !== "archived" && (
                      <button type="button" className="btn-primary" onClick={() => toggleCouponStatus(c)}>
                        {c.status === "active" ? appText("Скрыть", "Йәшереү") : appText("Показать", "Күрһәтеү")}
                      </button>
                    )}
                  </div>
                </div>
              );
            })
          )}

          <p className="receipt__foot">
            {appText(
              "Скидку по купону даёт само заведение. Юлдаш не берёт денег с клиента и не хранит его телефон при погашении.",
              "Купон буйынса ташламаны заведение үҙе бирә. Юлдаш клиенттан аҡса алмай һәм ҡулланғанда телефонын һаҡламай."
            )}
          </p>
        </>
      )}
    </>
  );
}
