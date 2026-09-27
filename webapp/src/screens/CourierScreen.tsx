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
import PriorityCard from "../components/PriorityCard";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
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
  parcelArrived,
  setParcelStatus,
  type Parcel,
} from "../api/parcels";
import { formatWhen, rubLabel } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { AvailableParcelCard, CarryParcelCard, CodeDialog } from "../components/parcelUi";
import ParcelProblemActions from "../components/ParcelProblemActions";
import CompletedParcelCard, { isCompletedParcel } from "../components/CompletedParcelCard";
import ParcelPhoto from "../components/ParcelPhoto";
import ParcelTrackMap from "../components/ParcelTrackMap";
import CityField from "../components/CityField";
import { IconStar, IconCheck, IconCopy, IconBox, IconCamera, IconClock, IconTrend, IconRoute, IconWallet } from "../components/Icons";
import { YuCourierWalk, YuModeCourier } from "../components/BrandIcons";
import { serverMs } from "../utils/serverTime";
import { rememberPayment } from "../utils/pendingPayment";
import { track } from "../analytics";

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
  /** Смена зоны не дошла до сервера — сказать, иначе чип врёт. */
  const [zoneNote, setZoneNote] = useState("");

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
  const paused = !!me?.paused_until && serverMs(me.paused_until) > Date.now();

  async function toggleOnline() {
    if (onlineBusy || !me) return;
    setOnlineBusy(true);
    try {
      const prof = online
        ? await courierOffline()
        : await courierOnline({ zone, work_city: workCity.trim() });
      track(online ? "courier_offline" : "courier_online");
      track(online ? "courier_offline" : "courier_online");
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
    const prev = zone;
    setZone(z);
    setZoneNote("");
    if (online && me) {
      try {
        const prof = await courierOnline({ zone: z, work_city: workCity.trim() });
        setMe({ ...me, profile: prof });
      } catch {
        // Зону отбирает СЕРВЕР. Если он не принял новую, а чип остался
        // переключённым, курьер думает, что работает по республике,
        // а заказы приходят по-старому. Возвращаем как было и говорим.
        setZone(prev);
        setZoneNote(
          appText(
            "Не получилось сменить зону. Проверь связь и попробуй ещё раз.",
            "Зонаны алмаштырып булманы. Бәйләнеште тикшереп ҡабатла."
          )
        );
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
    // CourierNotApprovedView (Android): круг 96 мятный с иконкой, заголовок 19 Bold, текст, золотая кнопка.
    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <div className="gate">
          <span className="gate__badge gate__badge--mint" aria-hidden><YuModeCourier size={48} /></span>
          <h2 className="gate__title">{appText("Стань курьером Юлдаша", "Юлдаш курьеры бул")}</h2>
          <p className="gate__body">
            {appText(
              "Развози посылки своим и зарабатывай. Текущая ставка комиссии — в кабинете курьера.",
              "Үҙебеҙҙекеләргә бандеролдәр илт тә аҡса эшлә. Хәҙерге комиссия ставкаһы — курьер кабинетында."
            )}
          </p>
          <button type="button" className="btn-primary btn-accent gate__primary" onClick={() => navigate("/courier-onboarding")}>
            <IconRoute size={18} /> {appText("Стать курьером", "Курьер булыу")}
          </button>
        </div>
      </>
    );
  }

  // ---------------- На линии ----------------
  return (
    <>
      <SubHeader title={appText("Режим курьера", "Курьер режимы")} onBack={() => navigate(-1)} />

      {paused && (
        <div className="courier-pause">
          🌿 {appText(
            "Небольшая пауза по качеству. Отдышись — скоро снова в строю.",
            "Сифат буйынса бәләкәй тәнәфес. Тын ал — тиҙҙән яңынан сафта."
          )}
        </div>
      )}

      {/* CourierLineHero Android: карточка с градиентной шапкой на линии, тумблером и зоной работы внутри. */}
      <section className={"line-hero" + (online ? " is-online" : "")}>
        <button
          type="button"
          className="line-hero__head"
          onClick={toggleOnline}
          disabled={onlineBusy || paused}
          aria-pressed={online}
          aria-label={appText("Работа курьера", "Курьер эше")}
        >
          <span className="line-hero__tile" aria-hidden><YuModeCourier size={24} /></span>
          <span className="line-hero__text">
            <b>{online ? appText("Ты на линии", "Һин линияла") : appText("Готов к заказам?", "Заказдарға әҙерме?")}</b>
            <span>
              {online
                ? appText("Показываем подходящие доставки", "Яраҡлы доставкаларҙы күрһәтәбеҙ")
                : appText("Включи линию, когда будешь готов", "Әҙер булғас, линияны ҡабыҙ")}
            </span>
          </span>
          <span className={"switch" + (online ? " on" : "")} aria-hidden />
        </button>
        <div className="line-hero__zone">

      {/* Город работы: сервер по нему отбирает заказы, поэтому спрашиваем до выхода на линию */}
      <label className="field" style={{ marginTop: 0 }}>
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
      <span className="field__label" style={{ display: "block" }}>
        {appText("Зона работы", "Эш зонаһы")}
      </span>
      <div className="chips">
        {ZONES.map((z) => (
          <button key={z.key} type="button" className={"chip" + (zone === z.key ? " chip--on" : "")} onClick={() => changeZone(z.key)}>
            {appText(z.ru, z.ba)}
          </button>
        ))}
      </div>

      {zoneNote && (
        <div className="notice" role="status">
          {zoneNote}
        </div>
      )}
        </div>
      </section>

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

      {tab === "available" && <AvailableOrders zone={zone} online={online} onGoOnline={toggleOnline} />}
      {tab === "carry" && <CarryOrders onGoAvailable={() => setTab("available")} />}
      {tab === "cabinet" && me && <Cabinet me={me} onReload={() => load()} />}
    </>
  );
}

// ============================ Доступные заказы ============================
function AvailableOrders({
  zone,
  online,
  onGoOnline,
}: {
  zone: CourierZone;
  /** На линии ли курьер: без линии курьерских заказов не показываем. */
  online: boolean;
  onGoOnline: () => void;
}) {
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
      track("parcel_accept");
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
          {!online
            ? appText("Сначала включи линию", "Тәүҙә линияны ҡабыҙ")
            : dir.trim()
              ? appText("По этому направлению пусто", "Был йүнәлештә буш")
              : appText("Пока нет заказов", "Әле заказдар юҡ")}
        </h2>
        {/* Пусто по трём разным причинам — и человеку нужно знать, по какой:
            выключенная линия чинится одним нажатием, а «в зоне никого» — нет. */}
        <p>
          {!online
            ? appText(
                "Курьерские заказы появятся только на линии — так никто не возьмёт заказ случайно. Заказы «по пути» можно смотреть и без линии.",
                "Курьер заказдары тик линияла күренә — шулай заказды осраҡлы алып булмай. «Юл ыңғайы» заказдарын линияһыҙ ҙа ҡарарға була."
              )
            : dir.trim()
              ? appText(
                  "Убери фильтр направления — возможно, заказы есть в другую сторону.",
                  "Йүнәлеш фильтрын алып ташла — башҡа яҡта заказдар булыуы мөмкин."
                )
              : appText(
                  "В твоей зоне сейчас пусто. Оставайся на линии — заказ появится со временем.",
                  "Зонаңда хәҙер буш. Линияла ҡал — заказ ваҡыт менән сыға."
                )}
        </p>
        {!online && (
          <button type="button" className="btn-primary" onClick={onGoOnline}>
            {appText("Включить линию", "Линияны ҡабыҙыу")}
          </button>
        )}
        {online && dir.trim() && (
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
function CarryOrders({ onGoAvailable }: { onGoAvailable: () => void }) {
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
  const [goodsErrors, setGoodsErrors] = useState<Record<number, string | undefined>>({});
  /** Чью карту смотреть, когда везёшь несколько посылок. */
  const [trackedId, setTrackedId] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCarrying(signal, true)
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

  async function onArrived(id: number) {
    setBusyId(id);
    try {
      await parcelArrived(id);
      await load();
    } catch {
      /* тихо: кнопку можно нажать ещё раз, счётчик уже идёт */
    } finally {
      setBusyId(null);
    }
  }

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
    setGoodsErrors((prev) => ({ ...prev, [id]: undefined }));
    try {
      const r = await setGoodsCost(id, kop);
      track("courier_goods_cost");
      setItems((prev) => prev.map((x) => (x.id === id ? { ...x, settlement: r.settlement } : x)));
    } catch (e) {
      setGoodsErrors((prev) => ({
        ...prev,
        [id]: e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить стоимость покупки. Повтори попытку.", "Һатып алыу хаҡын һаҡлап булманы. Ҡабатлап ҡара."),
      }));
    } finally {
      setBusyId(null);
    }
  }

  async function onDeliver(code: string) {
    if (!codeFor) return;
    setCodeBusy(true);
    setCodeErr(null);
    try {
      const completed = await setParcelStatus(codeFor.id, "delivered", code.trim(), photos[codeFor.id]);
      setItems((prev) => prev.map((x) => x.id === completed.id ? completed : x));
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
        <p>{appText("Возьми заказ — он появится здесь.", "Заказ ал — ул бында күренер.")}</p>
        {/* Экран уже ЗНАЕТ следующий шаг — значит должен вести, а не подсказывать
            словами. Иначе человек закрывает его и ищет вкладку глазами. */}
        <button type="button" className="btn-primary" onClick={onGoAvailable}>
          {appText("Смотреть заказы", "Заказдарҙы ҡарау")}
        </button>
      </div>
    );
  }
  // Онлайн-трекинг: карта одна — при нескольких доставках курьер выбирает, чью смотреть,
  // а по умолчанию открыта та, что уже в пути.
  const activeParcels = items.filter((p) => p.status === "accepted" || p.status === "in_transit");
  const tracked = activeParcels.find((p) => p.id === trackedId) ?? activeParcels.find((p) => p.status === "in_transit") ?? activeParcels[0];
  return (
    <div style={{ marginTop: 4 }}>
      {tracked && (
        <div className="parcel-track-block">
          <span className="dl-hint">
            {activeParcels.length > 1
              ? appText("Ты в пути — отправители видят тебя на карте. Выбери доставку:", "Һин юлда — ебәреүселәр һине картала күрә. Илтеүҙе һайла:")
              : appText("Ты в пути — отправитель видит тебя на карте", "Һин юлда — ебәреүсе һине картала күрә")}
          </span>
          {activeParcels.length > 1 && (
            <div className="cpick-row" role="radiogroup">
              {activeParcels.map((p) => (
                <button key={p.id} type="button" role="radio" aria-checked={p.id === tracked.id} className={"cpick" + (p.id === tracked.id ? " is-on" : "")} onClick={() => setTrackedId(p.id)}>
                  {(p.to_city || appText("Доставка", "Илтеү")) + ` · №${p.id}`}
                </button>
              ))}
            </div>
          )}
          <ParcelTrackMap key={tracked.id} parcel={tracked} asCourier />
        </div>
      )}
      {items.some(isCompletedParcel) && <p className="dl-hint">{appText("В работе и последние завершённые доставки", "Эштәге һәм һуңғы тамамланған илтеүҙәр")}</p>}
      {items.map((p) => isCompletedParcel(p) ? (
        <CompletedParcelCard key={p.id} parcel={p} onChanged={() => load()} />
      ) : (
        <div key={p.id}>
          <CarryParcelCard
            p={p}
            busy={busyId === p.id}
            onDepart={() => onDepart(p.id)}
            onArrived={() => onArrived(p.id)}
            onDeliver={() => { setCodeErr(null); setCodeFor(p); }}
            onGoodsCost={(kop) => onGoods(p.id, kop)}
          />
          {goodsErrors[p.id] && (
            <p className="dl-hint dl-hint--warn" role="alert">{goodsErrors[p.id]}</p>
          )}
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

function Cabinet({ me, onReload }: { me: CourierMe; onReload: () => void }) {
  const { appText } = useLang();
  const s = me.statement;
  const [busy, setBusy] = useState(false);
  const [pay, setPay] = useState<CommissionPayment | null>(null);
  const [msg, setMsg] = useState<{ ru: string; ba: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const navigate = useNavigate();

  async function onPay() {
    const owner = getSessionGeneration();
    if (busy) return;
    setBusy(true);
    setMsg(null);
    try {
      const r = await payCourierCommission();
      track("courier_pay_commission");
      if (r.status === "succeeded") {
        setMsg({ ru: "Комиссия оплачена. Спасибо! 💚", ba: "Комиссия түләнде. Рәхмәт! 💚" });
        onReload();
      } else if (r.method === "yookassa" && r.confirmation_url) {
        rememberPayment(r.payment_id, "commission", "/courier", owner);
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

  const owed = s.commission_owed_kop;
  const pausedUntil = me.paused_until ?? me.profile?.paused_until ?? null;
  const tierLine =
    s.fee_tier === "promo"
      ? appText("Промо-ставка", "Акция ставкаһы")
      : s.fee_tier === "tier1"
        ? appText("Стартовая ступень", "Башланғыс баҫҡыс")
        : s.fee_tier === "tier2"
          ? appText("Следующая ступень", "Киләһе баҫҡыс")
          : s.fee_tier === "tier3"
            ? appText("Обычная ставка", "Ғәҙәти ставка")
            : appText("Комиссия по твоей ступени", "Баҫҡысың буйынса комиссия");
  const promo = s.fee_tier === "promo";
  const feePct = String(s.current_fee_percent).replace(".", ",");

  return (
    <div className="cabinet">
      {/* Заработок отдельно от комиссии: иначе работа выглядит одним сплошным долгом. */}
      <button type="button" className="btn-soft" onClick={() => navigate("/courier-earnings")}>
        <IconTrend size={18} /> {appText("Мой заработок", "Минең табыш")}
      </button>
      {/* Фотоконтроль машины (580-ФЗ): две стороны кузова и багажник раз в две недели. */}
      <button type="button" className="btn-soft" onClick={() => navigate("/car-photo?mode=courier")}>
        <IconCamera size={18} /> {appText("Фотоконтроль машины", "Машина фотоконтроле")}
      </button>

      {/* ⭐ Приоритет: кому заказ падает первым и за что. Считается по ДОСТАВКАМ,
          отдельно от такси — работа разная, заслуги одной роли в другую не переносятся. */}
      <PriorityCard courier />

      {pausedUntil && (
        <div className="cab-note cab-note--warn">
          <span className="cab-note__icon" aria-hidden><IconClock size={24} /></span>
          <span className="cab-note__text">
            <strong>{appText("Пауза по качеству", "Сифат буйынса пауза")}</strong>
            <small>
              {appText(
                `Пауза до ${pausedUntil.slice(0, 10)}. Подтяни рейтинг — и снова в строю. Мы рядом, поможем.`,
                `${pausedUntil.slice(0, 10)} тиклем пауза. Рейтингты күтәр — һәм ҡабат сафта. Беҙ янда, ярҙам итербеҙ.`
              )}
            </small>
          </span>
        </div>
      )}

      {/* Рейтинг: плитка со звездой, «Твой рейтинг», крупная оценка и число оценок. */}
      <div className="cab-note">
        <span className="cab-note__tile" aria-hidden><IconStar size={24} /></span>
        <span className="cab-note__text">
          <small className="cab-note__label">{appText("Твой рейтинг", "Һинең рейтинг")}</small>
          {me.rating.avg != null && me.rating.count > 0 ? (
            <>
              <b className="cab-note__big">{me.rating.avg.toFixed(1).replace(".", ",")} ★</b>
              <small>{appText(`оценок: ${me.rating.count}`, `баһа: ${me.rating.count}`)}</small>
            </>
          ) : (
            <>
              <strong>{appText("Пока нет оценок", "Әлегә оценка юҡ")}</strong>
              <small>{appText("Первые доставки — и рейтинг появится.", "Тәүге илтеүҙәр — һәм рейтинг күренер.")}</small>
            </>
          )}
        </span>
      </div>

      <div className="cab-note cab-note--mint">
        <span className="cab-note__tile cab-note__tile--surface" aria-hidden><IconCheck size={24} /></span>
        <span className="cab-note__text">
          <small className="cab-note__label is-green">{appText("Доставлено заказов", "Тапшырылған заказдар")}</small>
          <b className="cab-note__big">{s.delivered_count}</b>
        </span>
      </div>

      {/* Текущая ступень комиссии. Без этой строки «комиссия» читается как штраф,
          а не как плата за то, что заказы вообще нашлись. */}
      {s.fee_tier && (
        <div className={"cab-note cab-note--column" + (promo ? " cab-note--mint" : "")}>
          <div className="cab-note__row">
            <span className={"cab-note__tile" + (promo ? " cab-note__tile--surface" : "")} aria-hidden><IconTrend size={24} /></span>
            <span className="cab-note__text">
              <strong>{appText(`Сейчас ты платишь ${feePct}% комиссии`, `Хәҙер һин ${feePct}% комиссия түләйһең`)}</strong>
              <small className={promo ? "is-green" : ""}>{tierLine}</small>
            </span>
          </div>
          {s.commission_min_kop > 0 && (
            <p className="dl-hint">
              {appText(
                `Комиссия минимум ${rubLabel(s.commission_min_kop)} за доставку. Всё прозрачно — видно, сколько и за что.`,
                `Комиссия иң кәме ${rubLabel(s.commission_min_kop)} бер илтеү өсөн. Барыһы ла асыҡ — күпме һәм ни өсөн икәне күренә.`
              )}
            </p>
          )}
        </div>
      )}

      {/* Комиссия: заработали · оплачено · к оплате (крупно). */}
      <div className={"statement" + (owed > 0 ? " statement--due" : "")}>
        <div className="statement__head">
          <IconWallet size={24} />
          <strong>{appText("Наша комиссия за доставки", "Илтеүҙәр өсөн беҙҙең комиссия")}</strong>
        </div>
        <p className="dl-hint">
          {appText(
            `Это сбор Юлдаша (${feePct}%) за то, что мы свели тебя с заказами. Твой доход остаётся у тебя — сюда попадает только наша часть.`,
            `Был — заказдар менән таныштырғаныбыҙ өсөн Юлдаш сборы (${feePct}%). Килемең үҙеңдә ҡала — бында тик беҙҙең өлөш.`
          )}
        </p>
        <div className="statement__row">
          <span>{appText("Всего заработали мы", "Барлығы беҙ эшләнек")}</span>
          <b>{rubLabel(s.commission_earned_kop)}</b>
        </div>
        <div className="statement__row">
          <span>{appText("Уже оплачено", "Түләнгән")}</span>
          <b className="is-green">{rubLabel(s.commission_paid_kop)}</b>
        </div>
        <div className={"statement__due" + (owed > 0 ? " is-due" : "")}>
          <strong>{appText("К оплате сейчас", "Хәҙер түләргә")}</strong>
          <b>{rubLabel(owed)}</b>
        </div>
        {msg && <div className="consents__status ok" style={{ marginTop: 0 }}>{appText(msg.ru, msg.ba)}</div>}
        {owed > 0 ? (
          <>
            <button type="button" className="btn-primary btn-accent submit-btn" onClick={onPay} disabled={busy}>
              {busy ? appText("Готовим оплату…", "Түләү әҙерләйбеҙ…") : appText("Оплатить комиссию", "Комиссияны түләү")}
            </button>
            <p className="dl-hint">
              {appText(
                "Переведи сумму по СБП на реквизиты Юлдаша — админ подтвердит оплату вручную.",
                "Сумманы СБП аша Юлдаш реквизиттарына күсер — админ түләүҙе ҡулдан раҫлар."
              )}
            </p>
          </>
        ) : (
          <p className="dl-hint is-green">
            {appText("Долгов нет — спасибо, что возишь по-честному.", "Бурыс юҡ — намыҫлы илткәнең өсөн рәхмәт.")}
          </p>
        )}
      </div>

      {(me.application || me.profile) && (
        <div className="cab-facts">
          <span>
            {appText("Транспорт: ", "Транспорт: ")}
            {me.application?.transport === "car"
              ? appText("Легковой", "Еңел машина")
              : me.application?.transport === "cargo"
                ? appText("Грузовой", "Йөк машинаһы")
                : me.application?.transport ?? "—"}
          </span>
          <strong className={me.profile?.online ? "is-green" : ""}>
            {appText("Статус: ", "Статус: ")}
            {me.profile?.online ? appText("на линии", "линияла") : appText("не на линии", "линияла түгел")}
          </strong>
          {me.application?.reviewed_at && (
            <small>{appText("Курьер с", "Курьер")} {formatWhen(me.application.reviewed_at, true)}</small>
          )}
        </div>
      )}

      <button type="button" className="btn-soft" onClick={onReload}>
        {appText("Обновить", "Яңыртыу")}
      </button>
    </div>
  );
}
