// ================================================================
//  «Посылки» (M3). RequireAuth → /parcels. Три вкладки:
//   • Отправить — мастер в три шага (components/ParcelSendWizard.tsx): «по пути» →
//     POST /parcels, курьер / «купи и привези» → /courier/estimate + /courier/orders;
//     в конце — крупный код вручения;
//   • Мои — GET /parcels/mine (статус, код, курьер, «Отменить»);
//   • Возить — «по пути» доставка: Доступные (GET /parcels/available,
//     «Взять») и Везу (GET /parcels/carrying, телефон + статусы).
//
//  Приватность: телефон получателя — только у отправителя (его данные)
//  и у принявшего курьера. Код вручения видит только отправитель.
//  Эндпоинты /parcels/* уже на проде.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchMyParcels,
  cancelParcel,
  parcelRedeliverRequest,
  fetchAvailableParcels,
  acceptParcel,
  setParcelStatus,
  fetchCarrying,
  type Parcel,
} from "../api/parcels";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import {
  StatusPillParcel,
  sizeLabel,
  AvailableParcelCard,
  CarryParcelCard,
  CodeDialog,
} from "../components/parcelUi";
import ParcelRate from "../components/ParcelRate";
import ParcelPhotoStrip from "../components/ParcelPhotoStrip";
import ParcelTrackMap from "../components/ParcelTrackMap";
import ParcelProblemActions from "../components/ParcelProblemActions";
import RoadsideHelp from "../components/RoadsideHelp";
import ParcelReceiptCard from "../components/ParcelReceiptCard";
import RaiseBudget from "../components/RaiseBudget";
import ParcelSendWizard from "../components/ParcelSendWizard";
import {
  ParcelAddressBlock,
  ParcelCodeCard,
  ParcelCourierRow,
  ParcelDeadlineNote,
  ParcelReturnNotice,
  ParcelRouteRow,
} from "../components/parcelForm";
import { CourierDeliveryProgress } from "../components/TaxiTripProgress";
import { kopExactLabel } from "../utils/format";
import { IconBox, IconCheck, IconChat, IconProfile, IconShield } from "../components/Icons";
import { useAuth } from "../auth/AuthProvider";
import { canOpenParcelDispute } from "../utils/parcelDispute.js";

type Tab = "send" | "mine" | "carry";


/** Разбор компенсации за отмену по строкам: «(100 ₽ — отмена + 300 ₽ — дорога курьера)».
 *  Пусто, если сервер старый и разбора не прислал: тогда человек видит только итог. */
function cancelParts(p: Parcel, подписи: [string, string, string]): string {
  const ч = p.cancel_fee_parts;
  if (!ч) return "";
  const строки = [
    [ч.base_kop, подписи[0]] as const,
    [ч.pickup_kop, подписи[1]] as const,
    [ч.waiting_kop, подписи[2]] as const,
  ]
    .filter(([kop]) => kop > 0)
    .map(([kop, имя]) => `${Math.round(kop / 100)} ₽ — ${имя}`);
  return строки.length ? ` (${строки.join(" + ")})` : "";
}

const cancelPartsRu = (p: Parcel) => cancelParts(p, ["отмена", "дорога курьера", "ожидание"]);
const cancelPartsBa = (p: Parcel) => cancelParts(p, ["кире алыу", "курьер юлы", "көтөү"]);

export default function ParcelsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("send");

  return (
    <>
      <SubHeader title={appText("Посылки", "Бандеролдәр")} onBack={() => navigate(-1)} />

      {/* ParcelTab (Android): рамка 14, внутри плитки — активная мятная с зелёной рамкой. */}
      <div className="parcel-tabs" role="tablist">
        {(
          [
            ["send", appText("Отправить", "Ебәреү")],
            ["mine", appText("Мои", "Минеке")],
            ["carry", appText("Возить", "Йөрөтөү")],
          ] as [Tab, string][]
        ).map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className={"parcel-tabs__tab" + (tab === id ? " is-active" : "")}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Смена вкладки — с затуханием (AnimatedContent), а не одним кадром. */}
      <div className="parcel-tab-body" key={tab}>
        {tab === "send" && <ParcelSendWizard onSent={() => setTab("mine")} />}
        {tab === "mine" && <MineTab />}
        {tab === "carry" && <CarryTab />}
      </div>
    </>
  );
}

// ============================ Вкладка «Мои» ============================
type Boot = "loading" | "error" | "ready";

function MineTab() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const { user } = useAuth();
  const navigate = useNavigate();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchMyParcels(signal)
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

  async function onCancel(id: number) {
    setBusyId(id);
    try {
      const p = await cancelParcel(id);
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо — статус не изменился */
    } finally {
      setBusyId(null);
    }
  }

  // «Курьер не застал получателя» → попросить заехать ещё раз. Ступенька между неудачей
  // и возвратом: раньше её не было и отправитель платил за возврат почти полную доставку.
  async function onRedeliver(id: number) {
    setBusyId(id);
    try {
      const p = await parcelRedeliverRequest(id);
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо — сервер сам решает, открыта ли просьба; карточка не изменилась */
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "loading") return <LoadingList count={3} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon"><IconBox size={34} /></div>
        <h2>{appText("Пока нет посылок", "Әле бандеролдәр юҡ")}</h2>
        <p>{appText("Создай заявку — попутный курьер довезёт её «между своими».", "Заявка яһа — юл ыңғайы курьер уны «үҙебеҙ» еткерә.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 14 }}>
      {items.map((p) => {
        const active = p.status !== "delivered" && p.status !== "canceled";
        return (
          <div key={p.id} className="parcel-card">
            {/* Шапка как в Android MyParcelCard: маршрут жирно + «размер · описание», справа статус. */}
            <div className="parcel-card__head parcel-card__head--route">
              <div className="parcel-card__route">
                <ParcelRouteRow from={p.from_city} to={p.to_city} />
                <div className="dl-hint">
                  {sizeLabel(p.size, ru)}
                  {p.description ? `  ·  ${p.description}` : ""}
                </div>
              </div>
              <StatusPillParcel status={p.status} />
            </div>
            {/* Порядок блоков — как в Android MyParcelCard: рельса, возврат, срок, адреса, компенсация,
                получатель, курьер, покупки, код, «заехать ещё раз», «Отменить». */}
            {p.status !== "canceled" && p.status !== "cancelled" && (
              <CourierDeliveryProgress status={p.status} />
            )}
            <ParcelReturnNotice status={p.status} reason={p.return_reason} forCourier={false} />
            <ParcelDeadlineNote deliverBy={p.deliver_by} overdue={p.overdue} status={p.status} forCourier={false} />
            <ParcelAddressBlock from={p.from_address} to={p.to_address} />
            {/* Снимки на границах ответственности — отправителю они нужны так же, как курьеру. */}
            <ParcelPhotoStrip pickupUrl={p.pickup_photo_url} deliveryUrl={p.delivery_photo_url} />
            {(p.cancel_fee_kop ?? 0) > 0 && (
              <div className="parcel-card__warn">
                {appText("Компенсация курьеру после отмены: ", "Кире алғандан һуң курьерға компенсация: ")}
                {kopExactLabel(p.cancel_fee_kop ?? 0)}
                {appText(". Рассчитайтесь напрямую.", ". Туранан-тура иҫәпләшегеҙ.")}
              </div>
            )}
            <div className="parcel-card__receiver">
              <span className="parcel-card__receiver-icon" aria-hidden><IconProfile size={16} /></span>
              <span className="parcel-card__receiver-name">{appText("Получатель: ", "Алыусы: ")}{p.receiver_name || "—"}</span>
              {p.price_kop > 0 && <b className="parcel-card__price">{kopExactLabel(p.price_kop)}</b>}
            </div>
            {/* Курьер, если принята: плашка + «Написать курьеру». Чат — где оставить, кому отдать,
                когда будут дома: письменно, а не в звонке. */}
            {p.courier && (
              <>
                <ParcelCourierRow
                  name={p.courier.name}
                  rating={p.courier.rating}
                  ratingCount={p.courier.rating_count}
                  phone={p.courier.phone}
                />
                <button type="button" className="btn-soft btn-soft--compact" onClick={() => navigate(`/parcel-chat/${p.id}`)}>
                  <IconChat size={18} /> {appText("Написать курьеру", "Курьерға яҙырға")}
                </button>
                {/* Живая карта — только пока посылка едет вперёд (accepted / in_transit). */}
                {(p.status === "accepted" || p.status === "in_transit") && (
                  <div className="parcel-track-block">
                    <span className="dl-hint">{appText("Курьер в пути — следи на карте", "Курьер юлда — картала күҙәт")}</span>
                    <ParcelTrackMap parcel={p} asCourier={false} />
                  </div>
                )}
              </>
            )}
            {/* «Купи и привези»: в магазине оказалось дороже согласованного. Без этой
                кнопки курьер не мог провести расчёт — товар куплен на его деньги,
                а сумма выше той, на которую согласился заказчик. Оба висели. */}
            {active && p.delivery_type === "buy_bring" && (p.cod_amount_kop ?? 0) > 0 && (
              <RaiseBudget
                parcelId={p.id}
                currentKop={p.cod_amount_kop ?? 0}
                onDone={() => load()}
              />
            )}
            {/* Курьер уже потратил свои деньги на товар — просто «отменить» тут
                нечестно по отношению к нему. Разбираться нужно через спор, где
                слышны обе стороны. */}
            {active && (p.settlement?.goods_actual_kop ?? 0) > 0 && (
              <div className="parcel-card__warn">
                {appText(
                  "Курьер уже купил товар. Обычная отмена недоступна — если что-то пошло не так, открой спор.",
                  "Курьер тауарҙы һатып алған инде. Ғәҙәти кире алыу мөмкин түгел — проблема булһа, бәхәс ас."
                )}
              </div>
            )}
            {/* Код вручения — свой, показываем отправителю */}
            {p.confirm_code && active && (
              <ParcelCodeCard code={p.confirm_code} label={appText("Код вручения (передай получателю)", "Тапшырыу коды (алыусыға бир)")} />
            )}
            {active && (p.settlement?.goods_actual_kop ?? 0) <= 0 && (
              <>
                {/* Курьер уже в пути — отмена стоит ему времени и бензина.
                    Сумму называем ДО нажатия, а не после. */}
                {p.courier && (
                  <div className="parcel-card__warn">
                    {(p.cancel_fee_preview_kop ?? 0) > 0
                      ? appText(
                          `Курьер уже принял заказ. Отмена сейчас — компенсация курьеру ${Math.round((p.cancel_fee_preview_kop ?? 0) / 100)} ₽${cancelPartsRu(p)} за потраченное время и дорогу. Расчёт напрямую с курьером.`,
                          `Курьер заказды алған инде. Хәҙер кире алһаң — курьерға ваҡыт һәм юл өсөн ${Math.round((p.cancel_fee_preview_kop ?? 0) / 100)} һум${cancelPartsBa(p)} компенсация. Иҫәпләшеү курьер менән туранан-тура.`
                        )
                      : appText(
                          "Курьер уже принял заказ. После отмены сервис зафиксирует компенсацию за потраченное время и дорогу; сумма появится в карточке, расчёт — напрямую.",
                          "Курьер заказды алған инде. Кире алғандан һуң сервис ваҡыт һәм юл өсөн компенсацияны теркәр; сумма карточкала күренер, иҫәпләшеү — туранан-тура."
                        )}
                  </div>
                )}
                {/* Курьер приехал и не застал получателя. Раньше у отправителя тут не
                    было ничего, кроме чата: дозвонись как-нибудь сам, а нет — плати за
                    возврат почти полную доставку. Теперь есть дешёвый выход. Цену заезда
                    называем ДО нажатия и считаем на сервере. */}
                {p.can_request_redelivery && (
                  <div className="redeliver-card">
                    <strong>{appText("Курьер не застал получателя", "Курьер алыусыны тапманы")}</strong>
                    <span>
                      {(p.return_fee_parts?.next_redeliver_kop ?? 0) > 0
                        ? appText(
                            `Свяжись с ним и попроси курьера заехать ещё раз — заезд стоит ${Math.round((p.return_fee_parts?.next_redeliver_kop ?? 0) / 100)} ₽, это дешевле возврата (${Math.round((p.return_fee_parts?.total_kop ?? 0) / 100)} ₽).`,
                            `Уның менән бәйләнеш тот һәм курьерҙан ҡабат инеүҙе һора — инеү ${Math.round((p.return_fee_parts?.next_redeliver_kop ?? 0) / 100)} һум тора, был кире ҡайтарыуҙан (${Math.round((p.return_fee_parts?.total_kop ?? 0) / 100)} һум) арзаныраҡ.`
                          )
                        : appText(
                            "Свяжись с ним и попроси курьера заехать ещё раз — доплаты за этот заезд не будет.",
                            "Уның менән бәйләнеш тот һәм курьерҙан ҡабат инеүҙе һора — был инеү өсөн өҫтәмә түләү булмаясаҡ."
                          )}
                    </span>
                    <button
                      type="button"
                      className="btn-soft"
                      onClick={() => onRedeliver(p.id)}
                      disabled={busyId === p.id}
                    >
                      {busyId === p.id
                        ? appText("Просим…", "Һорайбыҙ…")
                        : appText("Попросить заехать ещё раз", "Ҡабат инеүҙе һорау")}
                    </button>
                  </div>
                )}
                <button type="button" className="btn-soft" onClick={() => onCancel(p.id)} disabled={busyId === p.id}>
                  {busyId === p.id ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить", "Кире алыу")}
                </button>
              </>
            )}

            {canOpenParcelDispute(p, user?.id) && (
              <ParcelProblemActions
                parcel={p}
                role="sender"
                onChanged={(next) => {
                  if (next) setItems((prev) => prev.map((item) => (item.id === next.id ? next : item)));
                }}
              />
            )}

            {/* Доставлено — оцениваем курьера. Без истории оценок он для
                следующего отправителя просто незнакомый человек с коробкой. */}
            {p.status === "delivered" && p.courier && (
              <ParcelRate parcelId={p.id} role="courier" />
            )}

            {/* Чек. Нужен и после возврата: там тоже есть деньги — дорога и ожидание
                курьера, которые отправитель возвращает. */}
            {(p.status === "delivered" || p.status === "returned") && (
              <ParcelReceiptCard parcelId={p.id} />
            )}
          </div>
        );
      })}
    </div>
  );
}

// ============================ Вкладка «Возить» («по пути») ============================
function CarryTab() {
  const { appText } = useLang();
  const [sub, setSub] = useState<"available" | "carrying">("available");
  return (
    <>
      <div className="chips" style={{ marginTop: 14 }}>
        <button type="button" className={"chip" + (sub === "available" ? " chip--on" : "")} onClick={() => setSub("available")}>
          {appText("Доступные", "Асыҡ")}
        </button>
        <button type="button" className={"chip" + (sub === "carrying" ? " chip--on" : "")} onClick={() => setSub("carrying")}>
          {appText("Везу", "Йөрөтәм")}
        </button>
      </div>
      {sub === "available" ? <AvailableList /> : <CarryingList onGoAvailable={() => setSub("available")} />}
    </>
  );
}

function AvailableList() {
  const { appText } = useLang();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchAvailableParcels({}, signal)
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

  async function onTake(id: number) {
    setBusyId(id);
    try {
      await acceptParcel(id);
      setItems((prev) => prev.filter((x) => x.id !== id));
    } catch {
      // 409 — уже взяли; просто убираем из списка.
      setItems((prev) => prev.filter((x) => x.id !== id));
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "loading") return <LoadingList count={3} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon"><IconShield size={34} /></div>
        <h2>{appText("Пока нет посылок «по пути»", "Әле «юл ыңғайы» бандеролдәр юҡ")}</h2>
        <p>{appText("Как появятся заявки на твоём маршруте — покажем здесь. Помоги соседу по пути.", "Маршрутыңда заявка сыҡһа — бында күрһәтәбеҙ. Юл ыңғайы күршегә ярҙам ит.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 4 }}>
      {items.map((p) => (
        <AvailableParcelCard key={p.id} p={p} busy={busyId === p.id} onTake={() => onTake(p.id)} />
      ))}
    </div>
  );
}

function CarryingList({ onGoAvailable }: { onGoAvailable: () => void }) {
  const { appText } = useLang();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [codeFor, setCodeFor] = useState<Parcel | null>(null);
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

  async function onDeliver(code: string) {
    if (!codeFor) return;
    setCodeBusy(true);
    setCodeErr(null);
    try {
      await setParcelStatus(codeFor.id, "delivered", code.trim());
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
        <div className="state__icon"><IconCheck size={34} /></div>
        <h2>{appText("Ты пока ничего не везёшь", "Һин бер нәмә лә йөрөтмәйһең")}</h2>
        <p>{appText("Возьми заявку — она появится здесь.", "Заявка ал — ул бында күренер.")}</p>
        {/* Дорога названа — значит по ней и ведём. Подсказка без кнопки перекладывает
            на человека работу, которую экран уже сделал. */}
        <button type="button" className="btn-primary" onClick={onGoAvailable}>
          {appText("Смотреть заявки", "Заявкаларҙы ҡарау")}
        </button>
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
          />
          <ParcelProblemActions parcel={p} role="courier" onChanged={() => load()} />
          {/* Курьер едет по той же зимней трассе, что и все, — но едет ОДИН: рядом нет
              пассажира, который заметит беду. Кнопка была у попутки и такси, а у него нет. */}
          {String(p.status) === "in_transit" && <RoadsideHelp target={{ kind: "parcel", id: p.id }} />}
        </div>
      ))}
      {codeFor && (
        <CodeDialog busy={codeBusy} error={codeErr} onSubmit={onDeliver} onClose={() => setCodeFor(null)} />
      )}
    </div>
  );
}
