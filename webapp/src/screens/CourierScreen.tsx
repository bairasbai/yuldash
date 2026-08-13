// ================================================================
//  «Режим курьера» (C1). RequireAuth → /courier. Только одобренным
//  курьерам (гейт GET /courier/me). Тумблер «на линии» (POST
//  /courier/online|offline) + выбор зоны (чипы). Три вкладки:
//   • Доступные заказы — GET /courier/available (БЕЗ телефона, «Взять»
//     → POST /parcels/{id}/accept). Пере-запрос при смене зоны.
//   • Везу — GET /parcels/carrying (телефон виден, статусы «В пути»/
//     «Доставлено»→код; buy_bring — ввод стоимости товара).
//   • Кабинет — рейтинг + выписка (earned/owed/paid) + «Оплатить
//     комиссию» (СБП «на доверии») + текущая ступень + пауза.
//
//  Мягкая деградация: courier/* появятся после мержа release
//  (404/405 → «скоро», 403 → онбординг/пауза).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchCourierMe,
  courierOnline,
  courierOffline,
  fetchCourierAvailable,
  payCourierCommission,
  setGoodsCost,
  type CourierMe,
  type CourierZone,
  type CommissionPayment,
} from "../api/courier";
import {
  fetchCarrying,
  acceptParcel,
  setParcelStatus,
  type Parcel,
} from "../api/parcels";
import { rubLabel, formatWhen } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { AvailableParcelCard, CarryParcelCard, CodeDialog } from "../components/parcelUi";
import ParcelProblemActions from "../components/ParcelProblemActions";
import ParcelPhoto from "../components/ParcelPhoto";
import CityField from "../components/CityField";
import { IconStar, IconCheck, IconCopy, IconBox, IconTrend } from "../components/Icons";
import { YuCourierWalk } from "../components/BrandIcons";

type Boot = "loading" | "error" | "soon" | "need-approval" | "ready";
type Tab = "available" | "carry" | "cabinet";

const ZONES: { key: CourierZone; ru: string; ba: string }[] = [
  { key: "city", ru: "По городу", ba: "Ҡала эсендә" },
  { key: "intercity", ru: "Между городами", ba: "Ҡалалар араһы" },
  { key: "region", ru: "По республике", ba: "Республика буйынса" },
];

export default function CourierScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [me, setMe] = useState<CourierMe | null>(null);
  const [tab, setTab] = useState<Tab>("available");
  const [zone, setZone] = useState<CourierZone>("city");
  const [onlineBusy, setOnlineBusy] = useState(false);
  // Город работы: по нему сервер отбирает заказы. Без него курьеру сыпалось всё подряд.
  const [workCity, setWorkCity] = useState("");
  const [cityDirty, setCityDirty] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCourierMe(signal)
      .then((data) => {
        setMe(data);
        if (data.profile?.zone) setZone(data.profile.zone as CourierZone);
        if (data.profile?.work_city) setWorkCity(data.profile.work_city);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 403) setBoot("need-approval");
        else if (e instanceof ApiError && (e.status === 404 || e.status === 405)) setBoot("soon");
        else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const online = !!me?.profile?.online;
  const paused = !!me?.paused_until && new Date(me.paused_until).getTime() > Date.now();

  async function toggleOnline() {
    if (onlineBusy || !me) return;
    setOnlineBusy(true);
    try {
      const prof = online
        ? await courierOffline()
        : await courierOnline({ zone, work_city: workCity.trim() });
      setMe({ ...me, profile: prof });
    } catch (e) {
      // 403 = мягкая пауза по качеству → перечитаем кабинет (покажет плашку).
      if (e instanceof ApiError && e.status === 403) load();
    } finally {
      setOnlineBusy(false);
    }
  }

  async function changeZone(z: CourierZone) {
    if (z === zone) return;
    setZone(z);
    if (online && me) {
      try {
        const prof = await courierOnline({ zone: z, work_city: workCity.trim() });
        setMe({ ...me, profile: prof });
      } catch {
        /* оставим локальную зону — список всё равно пере-запросится */
      }
    }
  }

  // ---------------- Состояния гейта ----------------
  if (boot === "loading") {
    return (
      <>
        <SubHeader title={appText("Режим курьера", "Курьер режимы")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }
  if (boot === "error") {
    return (
      <>
        <SubHeader title={appText("Режим курьера", "Курьер режимы")} onBack={() => navigate(-1)} />
        <ErrorState onRetry={() => load()} />
      </>
    );
  }
  if (boot === "soon") {
    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <IconBox size={34} />
          </div>
          <h2>{appText("Курьер скоро запустится", "Курьер тиҙҙән асыла")}</h2>
          <p>{appText("А пока доступна доставка «по пути» — довези посылку соседу.", "Ә әлегә «юл ыңғайы» доставка бар — күршегә бандероль еткер.")}</p>
          <button type="button" className="btn-primary" onClick={() => navigate("/parcels")}>
            {appText("К посылкам", "Бандеролдәргә")}
          </button>
        </div>
      </>
    );
  }
  if (boot === "need-approval") {
    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <YuCourierWalk size={36} />
          </div>
          <h2>{appText("Сначала стань курьером", "Башта курьер бул")}</h2>
          <p>{appText("Чтобы брать заказы, нужна одобренная заявка. Это займёт пару минут.", "Заказ алыр өсөн хупланған ғариза кәрәк. Был бер-ике минут.")}</p>
          <button type="button" className="btn-primary" onClick={() => navigate("/courier-onboarding")}>
            {appText("Стать курьером", "Курьер булыу")}
          </button>
        </div>
      </>
    );
  }

  // ---------------- На линии ----------------
  return (
    <>
      <SubHeader
        title={appText("Режим курьера", "Курьер режимы")}
        subtitle={appText("Бери доставки рядом и зарабатывай", "Яҡындағы доставкаларҙы ал һәм эшлә")}
        onBack={() => navigate(-1)}
      />

      {paused && (
        <div className="courier-pause">
          🌿 {appText(
            "Небольшая пауза по качеству. Отдышись — скоро снова в строю.",
            "Сифат буйынса бәләкәй тәнәфес. Тын ал — тиҙҙән яңынан сафта."
          )}
        </div>
      )}

      {/* Тумблер «на линии» */}
      <button
        type="button"
        className={"onb__simple" + (online ? " is-active" : "")}
        onClick={toggleOnline}
        disabled={onlineBusy || paused}
        style={{ marginTop: 14 }}
      >
        <span className={"status-dot" + (online ? " status-dot--on" : "")} aria-hidden />
        <span className="onb__simple-text">
          <b>{appText("Я на линии", "Мин линияла")}</b>
          <span>
            {online
              ? appText("Ищем для тебя доставки рядом", "Һиңә яҡын доставкалар эҙләйбеҙ")
              : appText("Включи, когда готов брать заказы", "Заказ алырға әҙер булғас ҡабыҙ")}
          </span>
        </span>
        <span className={"switch" + (online ? " on" : "")} />
      </button>

      {/* Город работы: сервер по нему отбирает заказы, поэтому спрашиваем до выхода на линию */}
      <label className="field" style={{ marginTop: 14 }}>
        <span className="field__label">{appText("Город работы", "Эш ҡалаһы")}</span>
        <input
          className="field__input"
          value={workCity}
          onChange={(e) => {
            setWorkCity(e.target.value);
            setCityDirty(true);
          }}
          maxLength={80}
          placeholder={appText("Например: Баймак", "Мәҫәлән: Баймаҡ")}
        />
        {cityDirty && (
          <span className="field__hint">
            {online
              ? appText(
                  "Подтверди новый город — до этого заказы остаются по прежнему.",
                  "Яңы ҡаланы раҫла — шунға тиклем заказдар элеккесә ҡала."
                )
              : appText(
                  "Город применится, когда выйдешь на линию.",
                  "Ҡала линияға сыҡҡас ҡулланыла."
                )}
          </span>
        )}
      </label>
      {cityDirty && online && (
        <button
          type="button"
          className="btn-soft"
          style={{ width: "100%", marginTop: 8 }}
          onClick={async () => {
            try {
              const prof = await courierOnline({ zone, work_city: workCity.trim() });
              if (me) setMe({ ...me, profile: prof });
              setCityDirty(false);
            } catch {
              /* не применилось — прежний город остаётся, экран не ломаем */
            }
          }}
        >
          {appText("Сохранить город", "Ҡаланы һаҡларға")}
        </button>
      )}

      {/* Зона работы */}
      <span className="field__label" style={{ marginTop: 14, display: "block" }}>
        {appText("Зона работы", "Эш зонаһы")}
      </span>
      <div className="chips">
        {ZONES.map((z) => (
          <button key={z.key} type="button" className={"chip" + (zone === z.key ? " chip--on" : "")} onClick={() => changeZone(z.key)}>
            {appText(z.ru, z.ba)}
          </button>
        ))}
      </div>

      {/* Вкладки */}
      <div className="taxi-when parcel-tabs" style={{ marginTop: 16 }}>
        <button type="button" className={"taxi-when__tab" + (tab === "available" ? " is-active" : "")} onClick={() => setTab("available")}>
          {appText("Заказы", "Заказдар")}
        </button>
        <button type="button" className={"taxi-when__tab" + (tab === "carry" ? " is-active" : "")} onClick={() => setTab("carry")}>
          {appText("Везу", "Йөрөтәм")}
        </button>
        <button type="button" className={"taxi-when__tab" + (tab === "cabinet" ? " is-active" : "")} onClick={() => setTab("cabinet")}>
          {appText("Кабинет", "Кабинет")}
        </button>
      </div>

      {tab === "available" && <AvailableOrders zone={zone} />}
      {tab === "carry" && <CarryOrders />}
      {tab === "cabinet" && me && (
        <>
          <Cabinet me={me} onReload={() => load()} />
          {/* Заработок отдельно от комиссии: иначе работа выглядит одним сплошным долгом. */}
          <button
            type="button"
            className="btn-soft"
            style={{ width: "100%", marginTop: 12 }}
            onClick={() => navigate("/courier-earnings")}
          >
            <IconTrend size={18} /> {appText("Мой заработок", "Минең табыш")}
          </button>
        </>
      )}
    </>
  );
}

// ============================ Доступные заказы ============================
function AvailableOrders({ zone }: { zone: CourierZone }) {
  const { appText } = useLang();
  const [boot, setBoot] = useState<"loading" | "error" | "ready">("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  // Снимок «взял целой» до того, как посылка стала твоей: первая граница ответственности.
  const [photos, setPhotos] = useState<Record<number, string>>({});
  // Куда еду сегодня. Курьер обычно едет в конкретную сторону, и заказы
  // в противоположную для него просто шум — фильтрует сервер, не браузер.
  const [dir, setDir] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCourierAvailable(dir.trim() ? { to_city: dir.trim() } : {}, signal)
      .then((rows) => {
        setItems(rows);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
          setItems([]);
          setBoot("ready");
        } else setBoot("error");
      });
  }, [dir]);

  // Пере-запрос при смене зоны (сервер фильтрует по зоне профиля).
  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load, zone]);

  async function onTake(id: number) {
    setBusyId(id);
    try {
      await acceptParcel(id, photos[id]);
      setItems((prev) => prev.filter((x) => x.id !== id));
    } catch {
      setItems((prev) => prev.filter((x) => x.id !== id));
    } finally {
      setBusyId(null);
    }
  }

  const dirFilter = (
    <CityField
      label={appText("Куда еду", "Ҡайҙа барам")}
      value={dir}
      onChange={setDir}
      placeholder={appText("Любое направление", "Теләһә ниндәй йүнәлеш")}
    />
  );

  if (boot === "loading")
    return (
      <>
        {dirFilter}
        <LoadingList count={3} />
      </>
    );
  if (boot === "error")
    return (
      <>
        {dirFilter}
        <ErrorState onRetry={() => load()} />
      </>
    );
  if (items.length === 0) {
    return (
      <>
      {dirFilter}
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon">
          <IconBox size={34} />
        </div>
        <h2>
          {dir.trim()
            ? appText("По этому направлению пусто", "Был йүнәлештә буш")
            : appText("Пока нет заказов", "Әле заказдар юҡ")}
        </h2>
        <p>
          {dir.trim()
            ? appText(
                "Убери фильтр направления — возможно, заказы есть в другую сторону.",
                "Йүнәлеш фильтрын алып ташла — башҡа яҡта заказдар булыуы мөмкин."
              )
            : appText(
                "В твоей зоне сейчас пусто. Оставайся на линии — заказ появится со временем.",
                "Зонаңда хәҙер буш. Линияла ҡал — заказ ваҡыт менән сыға."
              )}
        </p>
        {dir.trim() && (
          <button type="button" className="btn-soft" onClick={() => setDir("")}>
            {appText("Любое направление", "Теләһә ниндәй йүнәлеш")}
          </button>
        )}
      </div>
      </>
    );
  }
  return (
    <div style={{ marginTop: 4 }}>
      {dirFilter}
      {items.map((p) => (
        <AvailableParcelCard
          key={p.id}
          p={p}
          busy={busyId === p.id}
          onTake={() => onTake(p.id)}
          photo={photos[p.id]}
          onPhoto={(url) => setPhotos((prev) => ({ ...prev, [p.id]: url }))}
        />
      ))}
    </div>
  );
}

// ============================ Везу ============================
function CarryOrders() {
  const { appText } = useLang();
  const [boot, setBoot] = useState<"loading" | "error" | "ready">("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [codeFor, setCodeFor] = useState<Parcel | null>(null);
  // Снимки «взял целой» / «отдал целой» по посылкам: id → url.
  // Держим до отправки — сервер принимает их вместе со сменой статуса.
  const [photos, setPhotos] = useState<Record<number, string>>({});
  const [codeBusy, setCodeBusy] = useState(false);
  const [codeErr, setCodeErr] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCarrying(signal)
      .then((rows) => {
        setItems(rows);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setItems([]);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onDepart(id: number) {
    setBusyId(id);
    try {
      const p = await setParcelStatus(id, "in_transit");
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо */
    } finally {
      setBusyId(null);
    }
  }

  async function onGoods(id: number, kop: number) {
    setBusyId(id);
    try {
      const r = await setGoodsCost(id, kop);
      setItems((prev) => prev.map((x) => (x.id === id ? { ...x, settlement: r.settlement } : x)));
    } catch {
      /* тихо — курьер повторит */
    } finally {
      setBusyId(null);
    }
  }

  async function onDeliver(code: string) {
    if (!codeFor) return;
    setCodeBusy(true);
    setCodeErr(null);
    try {
      await setParcelStatus(codeFor.id, "delivered", code.trim(), photos[codeFor.id]);
      setItems((prev) => prev.filter((x) => x.id !== codeFor.id));
      setCodeFor(null);
    } catch (e) {
      setCodeErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп ҡабат ҡара.")
      );
    } finally {
      setCodeBusy(false);
    }
  }

  if (boot === "loading") return <LoadingList count={2} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon">
          <YuCourierWalk size={36} />
        </div>
        <h2>{appText("Ты пока ничего не везёшь", "Һин бер нәмә лә йөрөтмәйһең")}</h2>
        <p>{appText("Возьми заказ во вкладке «Заказы» — он появится здесь.", "«Заказдар» бүлегендә заказ ал — ул бында күренер.")}</p>
      </div>
    );
  }
  return (
    <div style={{ marginTop: 4 }}>
      {items.map((p) => (
        <div key={p.id}>
          <CarryParcelCard
            p={p}
            busy={busyId === p.id}
            onDepart={() => onDepart(p.id)}
            onDeliver={() => { setCodeErr(null); setCodeFor(p); }}
            onGoodsCost={(kop) => onGoods(p.id, kop)}
          />
          {/* Граница ответственности: снимок «отдал целой» до ввода кода.
              В споре его отсутствие говорит само за себя. */}
          <ParcelPhoto
            kind="delivery"
            url={photos[p.id] ?? null}
            onReady={(url) => setPhotos((prev) => ({ ...prev, [p.id]: url }))}
          />

          {/* Не вручилось: попытка, возврат, спор — вместо «бросить заявку висеть» */}
          <ParcelProblemActions parcel={p} role="courier" onChanged={() => load()} />
        </div>
      ))}
      {codeFor && (
        <CodeDialog busy={codeBusy} error={codeErr} onSubmit={onDeliver} onClose={() => setCodeFor(null)} />
      )}
    </div>
  );
}

// ============================ Кабинет курьера ============================
const TIER_LABEL: Record<string, { ru: string; ba: string }> = {
  tier1: { ru: "Новичок", ba: "Яңы" },
  tier2: { ru: "Опытный", ba: "Тәжрибәле" },
  tier3: { ru: "Ветеран", ba: "Ветеран" },
  promo: { ru: "Промо запуска", ba: "Старт промоһы" },
};

function Cabinet({ me, onReload }: { me: CourierMe; onReload: () => void }) {
  const { appText } = useLang();
  const s = me.statement;
  const [busy, setBusy] = useState(false);
  const [pay, setPay] = useState<CommissionPayment | null>(null);
  const [msg, setMsg] = useState<{ ru: string; ba: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const tier = TIER_LABEL[s.fee_tier] ?? TIER_LABEL.tier3;

  async function onPay() {
    if (busy) return;
    setBusy(true);
    setMsg(null);
    try {
      const r = await payCourierCommission();
      if (r.status === "succeeded") {
        setMsg({ ru: "Комиссия оплачена. Спасибо! 💚", ba: "Комиссия түләнде. Рәхмәт! 💚" });
        onReload();
      } else if (r.method === "yookassa" && r.confirmation_url) {
        window.location.href = r.confirmation_url;
      } else {
        setPay(r); // sbp_manual — реквизиты
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setMsg({ ru: "Комиссии к оплате нет.", ba: "Түләргә комиссия юҡ." });
      } else if (e instanceof ApiError && e.status === 503) {
        setMsg({ ru: "Оплата скоро будет доступна.", ba: "Түләү тиҙҙән асыла." });
      } else {
        setMsg({ ru: "Не получилось. Попробуй снова.", ba: "Булманы. Ҡабат ҡара." });
      }
    } finally {
      setBusy(false);
    }
  }

  function copyPhone(phone: string) {
    navigator.clipboard?.writeText(phone).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      },
      () => {}
    );
  }

  // Реквизиты СБП «на доверии»
  if (pay?.method === "sbp_manual" && pay.payee) {
    return (
      <div className="pay-sbp" style={{ marginTop: 16 }}>
        <div className="pay-sbp__amount">{rubLabel(pay.amount_kop)}</div>
        <p className="pay-sbp__hint">
          {appText(
            "Переведи комиссию по номеру через СБП. Как получим — отметим оплату (по-соседски, на доверии).",
            "Комиссияны СБП аша номерға күсер. Алғас — түләүҙе билдәләйбеҙ (күршеләрсә, ышаныс менән)."
          )}
        </p>
        <div className="pay-sbp__row">
          <div>
            <div className="pay-sbp__label">{appText("Номер (СБП)", "Номер (СБП)")}</div>
            <div className="pay-sbp__value">{pay.payee.phone}</div>
          </div>
          <button type="button" className="btn-soft" onClick={() => copyPhone(pay.payee!.phone)}>
            {copied ? <IconCheck size={18} /> : <IconCopy size={18} />}
            {copied ? appText("Скопировано", "Күсерелде") : appText("Копировать", "Күсереү")}
          </button>
        </div>
        <div className="pay-sbp__row">
          <div>
            <div className="pay-sbp__label">{appText("Банк", "Банк")}</div>
            <div className="pay-sbp__value">{pay.payee.bank}</div>
          </div>
        </div>
        <div className="pay-sbp__row">
          <div>
            <div className="pay-sbp__label">{appText("Получатель", "Алыусы")}</div>
            <div className="pay-sbp__value">{pay.payee.name}</div>
          </div>
        </div>
        <button type="button" className="btn-primary submit-btn" onClick={() => { setPay(null); onReload(); }}>
          {appText("Я оплатил", "Түләнем")}
        </button>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 16 }}>
      {/* Рейтинг */}
      <div className="courier-rating">
        <div className="courier-rating__val">
          <IconStar size={22} /> {me.rating.avg != null ? me.rating.avg.toFixed(1) : "—"}
        </div>
        <div className="courier-rating__meta">
          {me.rating.count > 0
            ? appText(`${me.rating.count} оценок доставки`, `${me.rating.count} доставка баһаһы`)
            : appText("Пока нет оценок", "Әле баһа юҡ")}
        </div>
      </div>

      {/* Текущая ступень комиссии */}
      <div className="courier-fee">
        <div>
          <div className="courier-fee__pct">{s.current_fee_percent}%</div>
          <div className="courier-fee__label">{appText("Сейчас платишь комиссию", "Хәҙер комиссия түләйһең")}</div>
        </div>
        <span className="badge badge--mint">{appText(tier.ru, tier.ba)}</span>
      </div>

      {/* Выписка */}
      <div className="info-list">
        <div className="info-row">
          <span className="info-row__k">{appText("Доставлено заказов", "Еткерелгән заказ")}</span>
          <span className="info-row__v">{s.delivered_count}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Комиссия всего", "Барлыҡ комиссия")}</span>
          <span className="info-row__v">{rubLabel(s.commission_earned_kop)}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Уже оплачено", "Түләнгән")}</span>
          <span className="info-row__v">{rubLabel(s.commission_paid_kop)}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("К оплате сейчас", "Хәҙер түләргә")}</span>
          <span className="info-row__v">{rubLabel(s.commission_owed_kop)}</span>
        </div>
      </div>

      {msg && <div className="consents__status ok" style={{ marginTop: 12 }}>{appText(msg.ru, msg.ba)}</div>}

      <button
        type="button"
        className="btn-primary submit-btn"
        style={{ marginTop: 14 }}
        onClick={onPay}
        disabled={busy || s.commission_owed_kop <= 0}
      >
        {busy
          ? appText("Готовим оплату…", "Түләү әҙерләйбеҙ…")
          : s.commission_owed_kop > 0
            ? appText(`Оплатить комиссию · ${rubLabel(s.commission_owed_kop)}`, `Комиссия түләргә · ${rubLabel(s.commission_owed_kop)}`)
            : appText("Комиссия оплачена", "Комиссия түләнгән")}
      </button>

      <p className="receipt__foot">
        {appText(
          "Комиссия маленькая и честная — растёт со стажем (3→5→8%). Оплата «на доверии», по-соседски.",
          "Комиссия бәләкәй һәм ғәҙел — стаж менән үҫә (3→5→8%). Түләү «ышаныс менән», күршеләрсә."
        )}
      </p>

      {me.application?.reviewed_at && (
        <p className="receipt__foot" style={{ marginTop: 4 }}>
          {appText("Курьер с", "Курьер")} {formatWhen(me.application.reviewed_at, true)}
        </p>
      )}
    </div>
  );
}
