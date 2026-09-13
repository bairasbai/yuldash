// ================================================================
//  «Мой бизнес» — кабинет партнёра «Скидки по пути» (coupons.py). RequireAuth.
//  Зеркало Android PartnerCabinetScreen.kt. Ветвление по GET /partner/me:
//   • нет бизнеса → форма регистрации (POST /partner) → на модерацию;
//   • pending → «Бизнес на проверке» (круг с часами, имя, текст);
//   • rejected → красная карточка с причиной + та же форма («Сохранить и отправить снова»);
//   • active → дашборд: шапка бизнеса, золотая «Погасить код клиента» (карточка-диалог),
//     «Подписка» (GET /partner/plans + POST /partner/subscribe, СБП «на доверии» — экран «Тарифы»),
//     «Выписка» (statement), «Мои купоны» + «Создать» → экран купона
//     (GET/POST /partner/coupons, POST …/{id}, …/{id}/status).
//  Premium-показ купона — только при has_premium (премиум-подписка).
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
  type PartnerStatement,
} from "../api/coupons";
import { kopExactLabel } from "../utils/format";
import { RideCardSkeleton } from "../components/States";
import { ListedEmpty, ListedError } from "../components/adminUi";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconClock, IconStar, IconStore, IconTicket } from "../components/Icons";
import { track } from "../analytics";

type Status = "loading" | "error" | "soon" | "ready";
type View = "dashboard" | "coupon-form" | "subscribe" | "biz-edit";

/** Категории бизнеса — тот же перечень, что в приложении (partnerCategories). */
const CATEGORIES = ["cafe", "restaurant", "food", "beauty", "auto", "pharmacy", "fuel", "shop"];

/** couponCategoryLabel: подпись категории; незнакомую отдаём как есть. */
function categoryLabel(category: string, appText: (r: string, b: string) => string): string {
  switch (category.toLowerCase()) {
    case "cafe":
      return appText("Кафе", "Кафе");
    case "restaurant":
      return appText("Ресторан", "Ресторан");
    case "food":
    case "grocery":
      return appText("Продукты", "Аҙыҡ-түлек");
    case "beauty":
      return appText("Красота", "Матурлыҡ");
    case "auto":
    case "car":
      return appText("Авто", "Авто");
    case "pharmacy":
      return appText("Аптека", "Дарыухана");
    case "fuel":
    case "gas":
      return appText("Заправка", "Заправка");
    case "shop":
    case "store":
      return appText("Магазин", "Кибет");
    case "":
      return appText("Заведение", "Урын");
    default:
      return category;
  }
}

/** couponCategoryIcon: витрина для еды, звезда для красоты, ярлык для прочего. */
function categoryIcon(category: string, size: number) {
  const c = category.toLowerCase();
  if (["cafe", "restaurant", "food", "кафе", "ресторан", "еда"].includes(c)) return <IconStore size={size} />;
  if (["beauty", "салон", "красота"].includes(c)) return <IconStar size={size} />;
  return <IconTicket size={size} />;
}

/** «гггг-мм-дд» из ISO или пусто. */
function shortDate(iso: string | null | undefined): string {
  return iso && iso.length >= 10 ? iso.slice(0, 10) : "";
}

const EMPTY_BIZ: PartnerIn = { name: "", category: "cafe", city: "", address: "", phone: "", description: "" };

export default function PartnerCabinetScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [me, setMe] = useState<PartnerMe | null>(null);
  const [view, setView] = useState<View>("dashboard");
  const [editCoupon, setEditCoupon] = useState<PartnerCoupon | null>(null);

  const partner: Partner | null = me?.partner ?? null;

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchPartnerMe(signal)
      .then((res) => {
        setMe(res);
        setStatus("ready");
        setView("dashboard");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // ================= Состояния загрузки =================
  if (status === "loading") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнес")} onBack={() => navigate(-1)} />
        <div className="alist">
          <RideCardSkeleton />
          <RideCardSkeleton />
        </div>
      </>
    );
  }
  if (status === "soon") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнес")} onBack={() => navigate(-1)} />
        <div className="alist">
          <ListedEmpty
            icon={<IconStore size={34} />}
            title={appText("Бизнес-кабинет скоро", "Бизнес-кабинет тиҙҙән")}
            subtitle={appText("Раздел «Скидки по пути» для бизнеса включится после обновления сервиса.", "Бизнес өсөн «Юлда ташламалар» бүлеге яңыртыуҙан һуң эшләй башлар.")}
          />
        </div>
      </>
    );
  }
  if (status === "error") {
    return (
      <>
        <SubHeader title={appText("Мой бизнес", "Минең бизнес")} onBack={() => navigate(-1)} />
        <div className="alist">
          <ListedError onRetry={() => load()} />
        </div>
      </>
    );
  }

  // ================= Экраны активного кабинета =================
  if (partner && view === "coupon-form") {
    return (
      <CouponForm
        partner={partner}
        initial={editCoupon}
        onBack={() => setView("dashboard")}
        onSaved={() => {
          setEditCoupon(null);
          load();
        }}
      />
    );
  }
  if (partner && view === "subscribe") {
    return <SubscribeView partner={partner} onBack={() => setView("dashboard")} onSubscribed={() => load()} />;
  }
  if (partner && view === "biz-edit") {
    /* Есть только в вебе: правка данных активного бизнеса. Та же форма, что при регистрации. */
    return (
      <>
        <SubHeader title={appText("Данные бизнеса", "Бизнес мәғлүмәте")} onBack={() => setView("dashboard")} />
        <div className="alist">
          <PartnerForm initial={partner} onDone={() => load()} embedded />
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader title={appText("Мой бизнес", "Минең бизнес")} onBack={() => navigate(-1)} />
      <div className="alist">
        {!partner && <PartnerForm initial={null} onDone={() => load()} />}

        {partner?.status === "pending" && (
          /* PartnerPendingView: круг с часами, «Бизнес на проверке», имя, текст — по центру. */
          <div className="pstate">
            <span className="pstate__icon"><IconClock size={36} /></span>
            <h2>{appText("Бизнес на проверке", "Бизнес тикшереүҙә")}</h2>
            <strong>{partner.name}</strong>
            <p>
              {appText(
                "Мы смотрим твою заявку — обычно это недолго. Как одобрим, ты сможешь публиковать купоны и привлекать клиентов по маршрутам.",
                "Заявкаңды ҡарайбыҙ — ғәҙәттә оҙаҡ түгел. Раҫлаһаҡ, купон баҫтырып, маршруттар буйынса клиент йыя алырһың."
              )}
            </p>
          </div>
        )}

        {partner?.status === "rejected" && (
          /* PartnerRejectedView: красная карточка с причиной + встроенная форма. */
          <>
            <div className="preject">
              <strong>{appText("Заявка отклонена", "Заявка кире ҡағылды")}</strong>
              {partner.reject_reason && <p>{partner.reject_reason}</p>}
              <span>{appText("Поправь данные и отправь снова.", "Мәғлүмәтте төҙәтеп ҡабат ебәр.")}</span>
            </div>
            <PartnerForm initial={partner} onDone={() => load()} embedded />
          </>
        )}

        {partner && partner.status !== "pending" && partner.status !== "rejected" && (
          <PartnerDashboard
            partner={partner}
            statement={me?.statement}
            onCreateCoupon={() => {
              setEditCoupon(null);
              setView("coupon-form");
            }}
            onEditCoupon={(c) => {
              setEditCoupon(c);
              setView("coupon-form");
            }}
            onSubscribe={() => setView("subscribe")}
            onEditBiz={() => setView("biz-edit")}
          />
        )}
      </div>
    </>
  );
}

// ─────────────────────────── Форма регистрации / правки ───────────────────────────

function PartnerForm({ initial, onDone, embedded = false }: { initial: Partner | null; onDone: () => void; embedded?: boolean }) {
  const { appText } = useLang();
  const [biz, setBiz] = useState<PartnerIn>(
    initial
      ? {
          name: initial.name,
          category: initial.category || "cafe",
          city: initial.city,
          address: initial.address,
          phone: initial.phone,
          description: initial.description,
        }
      : EMPTY_BIZ
  );
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const valid = !!biz.name.trim() && !!biz.city.trim();

  async function submit() {
    if (sending || !valid) return;
    setSending(true);
    setErr(null);
    const body: PartnerIn = {
      name: biz.name.trim(),
      category: biz.category,
      city: biz.city.trim(),
      address: (biz.address ?? "").trim(),
      phone: (biz.phone ?? "").trim(),
      description: (biz.description ?? "").trim(),
    };
    try {
      if (initial) await updatePartner(initial.id, body);
      else await registerPartner(body);
      onDone();
    } catch (e) {
      setErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось сохранить. Повтори.", "Һаҡлап булманы. Ҡабатла."));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="pform">
      {!embedded && (
        <p className="dl-hint">
          {appText(
            "Расскажи о заведении — после проверки сможешь размещать купоны для попутчиков по маршрутам.",
            "Урын тураһында һөйлә — тикшереүҙән һуң маршруттар буйынса юлдаштар өсөн купон ҡуя алырһың."
          )}
        </p>
      )}
      <label className="field">
        <span className="field__label">{appText("Название заведения", "Урын исеме")}</span>
        <input className="field__input" maxLength={120} value={biz.name} onChange={(e) => setBiz({ ...biz, name: e.target.value })} />
      </label>
      {/* Категория — чипы 48 с радиусом 14, активный мятный. */}
      <span className="acard__sub">{appText("Категория", "Категория")}</span>
      <div className="afilter-row" role="radiogroup" aria-label={appText("Категория", "Категория")}>
        {CATEGORIES.map((code) => (
          <button
            key={code}
            type="button"
            role="radio"
            aria-checked={biz.category === code}
            className={"afilter" + (biz.category === code ? " is-on" : "")}
            onClick={() => setBiz({ ...biz, category: code })}
          >
            {categoryLabel(code, appText)}
          </button>
        ))}
      </div>
      <label className="field">
        <span className="field__label">{appText("Город", "Ҡала")}</span>
        <input className="field__input" maxLength={80} value={biz.city} onChange={(e) => setBiz({ ...biz, city: e.target.value })} />
      </label>
      <label className="field">
        <span className="field__label">{appText("Адрес", "Адрес")}</span>
        <input className="field__input" maxLength={200} value={biz.address ?? ""} onChange={(e) => setBiz({ ...biz, address: e.target.value })} />
      </label>
      <label className="field">
        <span className="field__label">{appText("Телефон", "Телефон")}</span>
        <input className="field__input" type="tel" inputMode="tel" maxLength={40} value={biz.phone ?? ""} onChange={(e) => setBiz({ ...biz, phone: e.target.value })} />
      </label>
      <label className="field">
        <span className="field__label">{appText("Описание", "Тасуирлау")}</span>
        <textarea className="field__input field__area" rows={2} maxLength={2000} value={biz.description ?? ""} onChange={(e) => setBiz({ ...biz, description: e.target.value })} />
      </label>
      {err && <div className="auth__error">{err}</div>}
      <button type="button" className="btn-primary pform__submit" onClick={submit} disabled={sending || !valid}>
        {sending
          ? appText("Сохраняем…", "Һаҡлайбыҙ…")
          : initial
            ? appText("Сохранить и отправить снова", "Һаҡлап ҡабат ебәреү")
            : appText("Отправить на проверку", "Тикшереүгә ебәреү")}
      </button>
    </div>
  );
}

// ─────────────────────────── Дашборд активного бизнеса ───────────────────────────

function PartnerDashboard({
  partner,
  statement,
  onCreateCoupon,
  onEditCoupon,
  onSubscribe,
  onEditBiz,
}: {
  partner: Partner;
  statement?: PartnerStatement;
  onCreateCoupon: () => void;
  onEditCoupon: (c: PartnerCoupon) => void;
  onSubscribe: () => void;
  onEditBiz: () => void;
}) {
  const { appText } = useLang();
  const [coupons, setCoupons] = useState<PartnerCoupon[]>([]);
  const [loadingC, setLoadingC] = useState(true);
  const [errorC, setErrorC] = useState<string | null>(null);
  const [busyCoupon, setBusyCoupon] = useState<number>(0);
  const [couponErr, setCouponErr] = useState<string | null>(null);
  const [showRedeem, setShowRedeem] = useState(false);

  const reloadCoupons = useCallback((signal?: AbortSignal) => {
    setLoadingC(true);
    setErrorC(null);
    fetchPartnerCoupons(signal)
      .then((list) => {
        setCoupons(list);
        setLoadingC(false);
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setErrorC(e instanceof ApiError && e.message ? e.message : appText("Не удалось загрузить купоны.", "Купондарҙы йөкләп булманы."));
        setLoadingC(false);
      });
  }, [appText]);

  useEffect(() => {
    const ac = new AbortController();
    reloadCoupons(ac.signal);
    return () => ac.abort();
  }, [reloadCoupons]);

  async function toggleStatus(c: PartnerCoupon, next: string) {
    if (busyCoupon) return;
    setBusyCoupon(c.id);
    setCouponErr(null);
    try {
      await setPartnerCouponStatus(c.id, next);
      reloadCoupons();
    } catch (e) {
      setCouponErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось. Повтори.", "Булманы. Ҡабатла."));
    } finally {
      setBusyCoupon(0);
    }
  }

  const until = shortDate(partner.subscription_until);

  return (
    <>
      {/* Шапка бизнеса: мятная плитка 48 с иконкой 24, имя 19, категория · город, чип «Активен». */}
      <div className="acard__row acard__row--md">
        <span className="partner-tile partner-tile--lg" aria-hidden>{categoryIcon(partner.category, 24)}</span>
        <span className="acard__stack acard__grow">
          <strong className="pcab__name">{partner.name}</strong>
          <span className="acard__sub">
            {categoryLabel(partner.category, appText)}
            {partner.city ? `  ·  ${partner.city}` : ""}
          </span>
        </span>
        <span className="abadge abadge--ok">{appText("Активен", "Актив")}</span>
      </div>
      {/* Есть только в вебе: правка данных бизнеса. */}
      <button type="button" className="abtn abtn--text pcab__edit" onClick={onEditBiz}>
        {appText("Изменить данные бизнеса", "Бизнес мәғлүмәтен үҙгәртеү")}
      </button>

      {/* Погасить код клиента — главное действие. */}
      {!showRedeem && (
        <button type="button" className="btn-primary btn-accent pcab__redeem" onClick={() => setShowRedeem(true)}>
          <IconTicket size={20} /> {appText("Погасить код клиента", "Клиент кодын һүндереү")}
        </button>
      )}
      {showRedeem && <RedeemCard onDismiss={() => setShowRedeem(false)} onRedeemed={() => reloadCoupons()} />}

      {/* Подписка */}
      <div className="pcard">
        <strong className="pcard__title">{appText("Подписка", "Яҙылыу")}</strong>
        {partner.subscription_active ? (
          <>
            <span className="acard__text">{appText(`Тариф: ${partner.subscription_plan}`, `Тариф: ${partner.subscription_plan}`)}</span>
            {until && <span className="acard__sub">{appText(`Действует до ${until}`, `${until} тиклем ғәмәлдә`)}</span>}
            {partner.has_premium && <span className="gold-chip acard__self">{appText("Премиум-размещение", "Премиум урынлаштырыу")}</span>}
            <button type="button" className="abtn abtn--outline abtn--48" onClick={onSubscribe}>
              {appText("Сменить тариф", "Тарифты алмаштырыу")}
            </button>
          </>
        ) : (
          <>
            <span className="acard__sub">{appText("Подключи тариф, чтобы купоны появились на витрине.", "Купондар витринала күренһен өсөн тариф ҡуш.")}</span>
            <button type="button" className="abtn abtn--48 abtn--body" onClick={onSubscribe}>
              {appText("Выбрать тариф", "Тариф һайлау")}
            </button>
          </>
        )}
      </div>

      {/* Выписка */}
      {statement && (
        <div className="pcard pcard--tight">
          <strong className="pcard__title">{appText("Выписка", "Иҫәп-хисап")}</strong>
          <strong className="acard__title">{appText(`Погашено купонов: ${statement.redeemed_total}`, `Һүндерелгән купондар: ${statement.redeemed_total}`)}</strong>
          <b className="acard__amount acard__amount--green">{appText(`К оплате: ${kopExactLabel(statement.amount_kop)}`, `Түләргә: ${kopExactLabel(statement.amount_kop)}`)}</b>
          <small className="acard__date">
            {appText(
              `Это комиссия Юлдаша за приведённых клиентов (${kopExactLabel(statement.fee_per_redemption_kop)} за погашенный купон).`,
              `Был — килтерелгән клиенттар өсөн Юлдаш комиссияһы (һүндерелгән купон өсөн ${kopExactLabel(statement.fee_per_redemption_kop)}).`
            )}
          </small>
        </div>
      )}

      {/* «Мои купоны» + «Создать» */}
      <div className="acard__row">
        <strong className="pcab__name acard__grow">{appText("Мои купоны", "Минең купондар")}</strong>
        <button type="button" className="abtn pcab__create" onClick={onCreateCoupon}>
          + {appText("Создать", "Булдырыу")}
        </button>
      </div>

      {couponErr && <div className="auth__error">{couponErr}</div>}
      {loadingC && coupons.length === 0 && <RideCardSkeleton />}
      {errorC && coupons.length === 0 && <ListedError message={errorC} onRetry={() => reloadCoupons()} />}
      {!loadingC && !errorC && coupons.length === 0 && (
        <ListedEmpty
          title={appText("Купонов пока нет", "Купондар әлегә юҡ")}
          subtitle={appText(
            "Создай первый купон — попутчики увидят его на витрине «Скидки по пути».",
            "Беренсе купонды булдыр — юлдаштар уны «Юл буйынса ташламалар» витринаһында күрер."
          )}
        />
      )}
      {coupons.map((c) => (
        <PartnerCouponRow key={c.id} c={c} busy={busyCoupon === c.id} onEdit={() => onEditCoupon(c)} onToggleStatus={(next) => toggleStatus(c, next)} />
      ))}
    </>
  );
}

/** Чип состояния купона: ПРОВЕРКУ показываем вперёд статуса — людям важнее «можно ли показывать». */
function CouponStatusChip({ status, review = "approved" }: { status: string; review?: string }) {
  const { appText } = useLang();
  if (review === "held") return <span className="abadge abadge--bad">{appText("На проверке текста", "Текст тикшереүҙә")}</span>;
  if (review === "blocked") return <span className="abadge abadge--bad">{appText("Снят администратором", "Администратор алған")}</span>;
  if (status === "active") return <span className="abadge abadge--ok">{appText("Активен", "Актив")}</span>;
  if (status === "paused") return <span className="abadge abadge--wait">{appText("Пауза", "Пауза")}</span>;
  if (status === "archived") return <span className="abadge abadge--wait abadge--dim">{appText("Архив", "Архив")}</span>;
  return <span className="abadge abadge--wait">{appText("Черновик", "Ҡаралама")}</span>;
}

function PartnerCouponRow({ c, busy, onEdit, onToggleStatus }: { c: PartnerCoupon; busy: boolean; onEdit: () => void; onToggleStatus: (next: string) => void }) {
  const { appText } = useLang();
  return (
    <article className="acard">
      <div className="acard__row">
        <span className="acard__stack acard__grow">
          <strong className="acard__title">{c.title}</strong>
          <span className="atext atext--green acard__caption">{c.discount_text}</span>
        </span>
        {c.premium && <span className="gold-chip">{appText("Премиум", "Премиум")}</span>}
        <CouponStatusChip status={c.status} review={c.review} />
      </div>
      <small className="acard__date">
        {appText(`Активаций: ${c.activations}  ·  погашено: ${c.redeemed_count}`, `Активлаштырыу: ${c.activations}  ·  һүндерелгән: ${c.redeemed_count}`)}
      </small>
      {/* Почему купона нет в витрине — словами, а не кодом состояния. */}
      {c.review === "held" && (
        <small className="acard__micro acard__text--danger">
          {appText(
            "Текст не прошёл проверку: убери телефон, ссылку или резкие слова — и сохрани.",
            "Текст тикшереүҙе үтмәне: телефонды, һылтанманы йәки ҡаты һүҙҙәрҙе алып ташла ла һаҡла."
          )}
        </small>
      )}
      {c.review === "blocked" && (
        <small className="acard__micro acard__text--danger">
          {c.review_note ||
            appText(
              "Купон снят администратором. Поправь текст и сохрани — он снова уйдёт на проверку.",
              "Купонды администратор алды. Тексты төҙәт тә һаҡла — ул тағы тикшереүгә китә."
            )}
        </small>
      )}
      {c.review !== "held" && c.review !== "blocked" && (c.reports_count ?? 0) > 0 && (
        <small className="acard__micro atext--warn">{appText(`Жалоб от людей: ${c.reports_count}`, `Кешеләрҙән зар: ${c.reports_count}`)}</small>
      )}
      <div className="acard__actions">
        <button type="button" className="abtn abtn--outline abtn--42" onClick={onEdit} disabled={busy}>
          {appText("Править", "Төҙәтеү")}
        </button>
        {c.status === "active" && (
          <button type="button" className="abtn abtn--outline abtn--42 abtn--warn" onClick={() => onToggleStatus("paused")} disabled={busy}>
            {appText("Пауза", "Туҡтатылған")}
          </button>
        )}
        {(c.status === "draft" || c.status === "paused") && (
          <button type="button" className="abtn abtn--42" onClick={() => onToggleStatus("active")} disabled={busy}>
            {appText("Включить", "Ҡабыҙыу")}
          </button>
        )}
      </div>
    </article>
  );
}

// ─────────────────────────── Форма купона (создать/править) ───────────────────────────

function CouponForm({ partner, initial, onBack, onSaved }: { partner: Partner; initial: PartnerCoupon | null; onBack: () => void; onSaved: () => void }) {
  const { appText } = useLang();
  const [title, setTitle] = useState(initial?.title ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [discountText, setDiscountText] = useState(initial?.discount_text ?? "");
  const [city, setCity] = useState(initial?.city ?? partner.city);
  const [routeHint, setRouteHint] = useState(initial?.route_hint?.join(", ") ?? "");
  const [limitTotal, setLimitTotal] = useState(String(initial?.limit_total ?? 100));
  const [limitPerUser, setLimitPerUser] = useState(String(initial?.limit_per_user ?? 1));
  const [validUntil, setValidUntil] = useState(shortDate(initial?.valid_until));
  const [premium, setPremium] = useState(initial?.premium ?? false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const valid = !!title.trim() && !!discountText.trim() && !!city.trim();

  async function submit() {
    if (sending || !valid) return;
    setSending(true);
    setErr(null);
    const body: CouponIn = {
      title: title.trim(),
      description: description.trim(),
      discount_text: discountText.trim(),
      city: city.trim(),
      route_hint: routeHint
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean)
        .join(","),
      limit_total: Number(limitTotal) || 0,
      limit_per_user: Number(limitPerUser) || 1,
      premium: partner.has_premium && premium,
      valid_until: validUntil.trim() || null,
    };
    try {
      if (initial) await updatePartnerCoupon(initial.id, body);
      else await createPartnerCoupon(body);
      onSaved();
    } catch (e) {
      setErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось сохранить. Повтори.", "Һаҡлап булманы. Ҡабатла."));
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <SubHeader title={initial ? appText("Правка купона", "Купонды төҙәтеү") : appText("Новый купон", "Яңы купон")} onBack={onBack} />
      <div className="alist">
        <label className="field">
          <span className="field__label">{appText("Заголовок", "Баш")}</span>
          <input className="field__input" maxLength={120} value={title} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Скидка (например: -20%)", "Ташлама (мәҫәлән: -20%)")}</span>
          <input className="field__input" maxLength={80} value={discountText} onChange={(e) => setDiscountText(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Описание", "Тасуирлау")}</span>
          <textarea className="field__input field__area" rows={2} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Город", "Ҡала")}</span>
          <input className="field__input" maxLength={80} value={city} onChange={(e) => setCity(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Маршруты через запятую", "Маршруттар өтөр аша")}</span>
          <input className="field__input" maxLength={300} value={routeHint} onChange={(e) => setRouteHint(e.target.value)} />
        </label>
        <div className="ads-form__row">
          <label className="field">
            <span className="field__label">{appText("Всего", "Барлығы")}</span>
            <input className="field__input" inputMode="numeric" value={limitTotal} onChange={(e) => setLimitTotal(e.target.value.replace(/\D/g, ""))} />
          </label>
          <label className="field">
            <span className="field__label">{appText("На человека", "Бер кешегә")}</span>
            <input className="field__input" inputMode="numeric" value={limitPerUser} onChange={(e) => setLimitPerUser(e.target.value.replace(/\D/g, ""))} />
          </label>
        </div>
        <label className="field">
          <span className="field__label">{appText("Действует до (ГГГГ-ММ-ДД)", "Тиклем (ГГГГ-ММ-КК)")}</span>
          <input className="field__input" type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
        </label>
        {/* Премиум — только если у бизнеса есть премиум-размещение. */}
        {partner.has_premium && (
          <div className="acity">
            <span className="acity__text">
              <strong>{appText("Премиум-показ", "Премиум-күрһәтеү")}</strong>
              <small>{appText("Выше в витрине, заметная метка", "Витринала юғарыраҡ, күренекле билдә")}</small>
            </span>
            <button type="button" role="switch" aria-checked={premium} className="acity__switch" onClick={() => setPremium((v) => !v)} aria-label={appText("Премиум-показ", "Премиум-күрһәтеү")}>
              <span className={"switch" + (premium ? " on" : "")} aria-hidden />
            </button>
          </div>
        )}
        {err && <div className="auth__error">{err}</div>}
        <button type="button" className="btn-primary pform__submit" onClick={submit} disabled={sending || !valid}>
          {sending ? appText("Сохраняем…", "Һаҡлайбыҙ…") : initial ? appText("Сохранить", "Һаҡлау") : appText("Создать купон", "Купон булдырыу")}
        </button>
      </div>
    </>
  );
}

// ─────────────────────────── Подписка (выбор тарифа + оплата «на доверии») ───────────────────────────

function SubscribeView({ partner, onBack, onSubscribed }: { partner: Partner; onBack: () => void; onSubscribed: () => void }) {
  const { appText } = useLang();
  const [plans, setPlans] = useState<PartnerPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [subscribing, setSubscribing] = useState("");
  const [payAmountKop, setPayAmountKop] = useState<number | null>(null);
  const [actErr, setActErr] = useState<string | null>(null);

  const reload = useCallback((signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    fetchPartnerPlans(signal)
      .then((list) => {
        setPlans(list);
        setLoading(false);
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setError(appText("Не удалось загрузить тарифы.", "Тарифтарҙы йөкләп булманы."));
        setLoading(false);
      });
  }, [appText]);

  useEffect(() => {
    const ac = new AbortController();
    reload(ac.signal);
    return () => ac.abort();
  }, [reload]);

  async function select(plan: PartnerPlan) {
    if (subscribing) return;
    setSubscribing(plan.code);
    setActErr(null);
    try {
      const res = await subscribePartner(plan.code);
      setPayAmountKop(res.amount_kop);
    } catch (e) {
      setActErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось оформить. Повтори.", "Рәсмиләштереп булманы. Ҡабатла."));
    } finally {
      setSubscribing("");
    }
  }

  return (
    <>
      <SubHeader title={appText("Тарифы", "Тарифтар")} onBack={onBack} />
      <div className="alist">
        {payAmountKop != null ? (
          /* Инструкция оплаты «на доверии» после оформления. */
          <div className="pstatement">
            <strong className="ppay__title">{appText(`Переведи ${kopExactLabel(payAmountKop)} по СБП`, `СБП аша ${kopExactLabel(payAmountKop)} күсер`)}</strong>
            <span className="acard__text">
              {appText(
                "Оплата «на доверии»: переведи сумму по реквизитам из поддержки. Как подтвердим оплату — подписка включится, и купоны появятся на витрине.",
                "«Ышаныс менән» түләү: ярҙам биргән реквизиттар буйынса күсер. Түләүҙе раҫлағас — яҙылыу ҡабыҙыла, купондар витринала күренә."
              )}
            </span>
            <button type="button" className="btn-primary pform__submit" onClick={onSubscribed}>
              {appText("Понятно", "Аңлашыла")}
            </button>
          </div>
        ) : (
          <>
            <p className="dl-hint">{appText("Выбери тариф — оплата по СБП «на доверии», админ подтвердит.", "Тариф һайла — СБП аша «ышаныс менән» түләү, админ раҫлар.")}</p>
            {actErr && <div className="auth__error">{actErr}</div>}
            {loading && plans.length === 0 && <RideCardSkeleton />}
            {error && plans.length === 0 && <ListedError message={error} onRetry={() => reload()} />}
            {!loading && !error && plans.length === 0 && <ListedEmpty title={appText("Тарифов нет", "Тарифтар юҡ")} subtitle={appText("Загляни позже.", "Һуңыраҡ кер.")} />}
            {plans.map((plan) => {
              const current = partner.subscription_active && partner.subscription_plan === plan.code;
              return (
                <div key={plan.code} className={"plan" + (plan.premium ? " plan--premium" : "")}>
                  <div className="acard__row">
                    <strong className="pcab__name acard__grow">{appText(plan.title, plan.title_ba || plan.title)}</strong>
                    {plan.premium && <span className="gold-chip">{appText("Премиум", "Премиум")}</span>}
                  </div>
                  <b className="plan__price">
                    {kopExactLabel(plan.amount_kop)} / {plan.period_days} {appText("дн.", "көн")}
                  </b>
                  {current && (
                    <span className="abadge abadge--ok acard__self plan__current">
                      <IconCheck size={15} /> {appText("Текущий тариф", "Хәҙерге тариф")}
                    </span>
                  )}
                  <button
                    type="button"
                    className={"abtn abtn--48 abtn--body" + (plan.premium ? " abtn--gold" : "")}
                    onClick={() => select(plan)}
                    disabled={subscribing !== ""}
                  >
                    {subscribing === plan.code ? appText("…", "…") : current ? appText("Продлить", "Оҙайтыу") : appText("Оформить", "Рәсмиләштереү")}
                  </button>
                </div>
              );
            })}
          </>
        )}
      </div>
    </>
  );
}

// ─────────────────────────── Погашение кода клиента ───────────────────────────

function RedeemCard({ onDismiss, onRedeemed }: { onDismiss: () => void; onRedeemed: () => void }) {
  const { appText } = useLang();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<RedeemResult | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function redeem() {
    if (busy || !code.trim()) return;
    setBusy(true);
    setErr(null);
    try {
      const res = await redeemCoupon(code.trim());
      track("coupon_redeem");
      setResult(res);
      onRedeemed();
    } catch (e) {
      setErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось. Проверь код.", "Булманы. Кодты тикшер."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="settings-confirm settings-confirm--card settings-confirm--plain">
      <strong>{result ? appText("Скидка подтверждена", "Ташлама раҫланды") : appText("Погасить код клиента", "Клиент кодын һүндереү")}</strong>
      {result ? (
        <>
          <span className="redeem__hero">
            <IconCheck size={28} /> {result.discount_text}
          </span>
          <strong className="acard__title">{result.coupon_title}</strong>
          <span>{appText(`Клиент: ${result.customer_name}`, `Клиент: ${result.customer_name}`)}</span>
          <span>{appText("Дай скидку клиенту.", "Клиентҡа ташлама бир.")}</span>
          <div className="settings-confirm__row">
            <button type="button" className="btn-ghost inc-resolve__save" onClick={onDismiss}>
              {appText("Готово", "Әҙер")}
            </button>
          </div>
        </>
      ) : (
        <>
          <span>{appText("Введи код, который показал клиент.", "Клиент күрһәткән кодты индер.")}</span>
          <label className="field">
            <span className="field__label">{appText("Код купона", "Купон коды")}</span>
            <input
              className="field__input"
              maxLength={12}
              value={code}
              onChange={(e) => {
                setCode(e.target.value.toUpperCase().replace(/\s/g, ""));
                setErr(null);
              }}
              autoCapitalize="characters"
              autoFocus
            />
          </label>
          {err && <div className="auth__error">{err}</div>}
          <div className="settings-confirm__row">
            <button type="button" className="btn-ghost settings-confirm__muted" onClick={onDismiss} disabled={busy}>
              {appText("Отмена", "Баш тартыу")}
            </button>
            <button type="button" className="btn-ghost inc-resolve__save" onClick={redeem} disabled={busy || !code.trim()}>
              {busy ? appText("…", "…") : appText("Погасить", "Һүндереү")}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
