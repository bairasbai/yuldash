// ================================================================
//  Что пассажир может сделать ВО ВРЕМЯ поездки на такси.
//  Зеркало Android (InstantOrderScreen.kt + TaxiTripScreen.kt).
//
//  До этой волны веб умел ровно две вещи: заказать машину и отменить
//  заказ. Всё, что случается между «водитель принял» и «приехали»,
//  было доступно только в приложении: сменить адрес, добавить
//  остановку, сказать «уже выхожу», поменять способ расчёта,
//  позвать помощь на трассе, ответить на зимнюю проверку
//  (сверка с Android, 2026-08-30).
//
//  Правила, которые тут соблюдаются:
//  · цену считает СЕРВЕР — новую сумму человек видит ДО согласия;
//  · крупная смена (межгород / цена втрое) идёт через водителя:
//    пять часов за руль это не «поменял адрес», а другая работа;
//  · ни одна кнопка не врёт: не получилось — так и говорим,
//    и заказ остаётся ровно там, где был.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  changeDestination,
  imComing,
  previewDestination,
  setPaymentMethod,
  setWaypoints,
  waitForDriver,
  cancelInstantOrder,
  type DestinationQuote,
  type InstantOrder,
  type PaymentMethod,
} from "../api/instant";
import { roadsideHelpOrder, winterCheckOrder, winterCheckOrderOk } from "../api/safety";
import { geocode } from "../api/discovery";
import { serverMs } from "../utils/serverTime";
import {
  forgetWinterCheck,
  rememberWinterCheck,
  wasWinterCheckAsked,
  winterCheckDelay,
  winterCheckNeedsAnswer,
} from "../utils/winterCheck.js";
import {
  LatestDestinationPreview,
  type DestinationPreview,
} from "../utils/destinationPreview.js";
import {
  IconCar,
  IconCheck,
  IconPin,
  IconWarn,
  IconWallet,
} from "./Icons";
import { track } from "../analytics";

interface Point {
  lat: number;
  lng: number;
  text: string;
}

function samePoint(left: Point, right: Point): boolean {
  return left.lat === right.lat && left.lng === right.lng && left.text === right.text;
}

/** Пока идёт запрос — вторую кнопку не жмём: два «сменить адрес» подряд сервер отобьёт 429. */
function useBusy() {
  const [busy, setBusy] = useState(false);
  return [busy, setBusy] as const;
}

// ----------------------------- Поиск адреса -----------------------------
/**
 * Поле «куда» для смены адреса и остановок. Тот же геокодер, что в форме заказа,
 * с той же паузой: человек печатает быстрее, чем отвечает сервер.
 */
function AddressSearch({
  placeholder,
  onPick,
}: {
  placeholder: string;
  onPick: (p: Point) => void;
}) {
  const { appText } = useLang();
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<Point[]>([]);
  const tRef = useRef<number | null>(null);

  useEffect(() => {
    if (tRef.current) window.clearTimeout(tRef.current);
    const text = q.trim();
    if (text.length < 3) {
      setHits([]);
      return;
    }
    const ac = new AbortController();
    tRef.current = window.setTimeout(() => {
      geocode(text, ac.signal)
        .then((r) => setHits(r.items.map((h) => ({ lat: h.lat, lng: h.lon, text: h.title }))))
        .catch(() => setHits([]));
    }, 350);
    return () => {
      if (tRef.current) window.clearTimeout(tRef.current);
      ac.abort();
    };
  }, [q]);

  return (
    <div className="trip-search">
      <input
        className="taxi-route__input"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        autoComplete="off"
      />
      {hits.length > 0 && (
        <div className="taxi-suggest taxi-suggest--inline">
          {hits.map((h, i) => (
            <button
              key={i}
              type="button"
              className="taxi-suggest__row"
              onClick={() => {
                onPick(h);
                setQ("");
                setHits([]);
              }}
            >
              <IconPin size={18} />
              <span>{h.text}</span>
            </button>
          ))}
        </div>
      )}
      {q.trim().length >= 3 && hits.length === 0 && (
        <p className="trip-hint">{appText("Ничего не нашли", "Бер нәмә лә табылманы")}</p>
      )}
    </div>
  );
}

// ----------------------------- Смена адреса -----------------------------
/**
 * «Едем в другое место».
 *
 * Два шага: сначала СЧИТАЕМ и показываем новую цену, потом человек соглашается.
 * Одним нажатием менять нельзя — цена растёт молча, а узнаётся в конце поездки,
 * и это ровно тот случай, за который такси и не любят.
 */
function ChangeDestination({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [open, setOpen] = useState(false);
  const [point, setPoint] = useState<Point | null>(null);
  const [preview, setPreview] = useState<DestinationPreview<Point, DestinationQuote> | null>(null);
  const previewGate = useRef(new LatestDestinationPreview<Point, DestinationQuote>(samePoint));
  const [busy, setBusy] = useBusy();
  const [note, setNote] = useState("");

  async function ask(p: Point) {
    const revision = previewGate.current.begin(p);
    setPoint(p);
    setPreview(null);
    setNote("");
    setBusy(true);
    try {
      const next = previewGate.current.resolve(revision, await previewDestination(order.id, p));
      if (next) setPreview(next);
    } catch (e) {
      if (previewGate.current.isCurrent(revision)) {
        setNote(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось посчитать. Попробуй ещё раз.", "Иҫәпләп булманы. Тағы ҡабатла.")
        );
      }
    } finally {
      if (previewGate.current.isCurrent(revision)) setBusy(false);
    }
  }

  async function apply() {
    if (!point || !preview || busy || !previewGate.current.canApply(point, preview)) return;
    setBusy(true);
    setNote("");
    try {
      const r = await changeDestination(order.id, preview.point);
      if (r.waiting_driver) {
        setNote(
          appText(
            "Спросили водителя — это дальняя поездка. Ответит через минуту.",
            "Йөрөтөүсенән һораныҡ — был алыҫ сәфәр. Бер минуттан яуап бирер."
          )
        );
        previewGate.current.clear();
        setPreview(null);
        setPoint(null);
        onDone();
        return;
      }
      setOpen(false);
      previewGate.current.clear();
      setPreview(null);
      setPoint(null);
      onDone();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Адрес не сменился — нет связи.", "Адрес алышынманы — бәйләнеш юҡ.")
      );
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft trip-btn" onClick={() => setOpen(true)}>
        <IconPin size={18} /> {appText("Едем в другое место", "Башҡа урынға китәбеҙ")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconPin size={18} /> {appText("Новый адрес", "Яңы адрес")}
      </div>
      <p className="act-card__text">
        {appText(
          "Заплатишь за уже проеденное плюс дорогу до нового адреса. Сумму покажем до согласия.",
          "Үтелгән юл өсөн һәм яңы адресҡа тиклемге юл өсөн түләйһең. Сумманы алдан күрһәтәбеҙ."
        )}
      </p>

      {!preview && <AddressSearch placeholder={appText("Куда теперь", "Хәҙер ҡайҙа")} onPick={ask} />}

      {preview && point && (
        <>
          <div className="info-list" style={{ marginTop: 0 }}>
            <div className="info-row">
              <span className="info-row__k">{appText("Новый адрес", "Яңы адрес")}</span>
              <span className="info-row__v">{preview.point.text}</span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Станет", "Буласаҡ")}</span>
              <span className="info-row__v">
                <b>{ru ? `${preview.quote.price} ₽` : `${preview.quote.price} һум`}</b>
                {preview.quote.old_price > 0 && preview.quote.old_price !== preview.quote.price && (
                  <span className="money-row__op">
                    {" "}
                    {appText(
                      `вместо ${preview.quote.old_price} ₽`,
                      `${preview.quote.old_price} һум урынына`
                    )}
                  </span>
                )}
              </span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Уже проехали", "Үтелгән")}</span>
              <span className="info-row__v">
                {appText(
                  `${preview.quote.driven_km.toFixed(1)} км`,
                  `${preview.quote.driven_km.toFixed(1)} км`
                )}
              </span>
            </div>
          </div>

          {preview.quote.needs_driver_ok && (
            <p className="act-card__text" style={{ marginTop: 10 }}>
              {preview.quote.ask_reason === "intercity"
                ? appText(
                    "Это уже другой город — сначала спросим водителя. Пять часов за руль он должен выбрать сам.",
                    "Был башҡа ҡала — тәүҙә йөрөтөүсенән һорайбыҙ. Биш сәғәт юл — уның ҡарары."
                  )
                : appText(
                    "Цена сильно выросла — сначала спросим водителя.",
                    "Хаҡ ныҡ артты — тәүҙә йөрөтөүсенән һорайбыҙ."
                  )}
            </p>
          )}

          <div className="act-card__actions">
            <button type="button" className="btn-primary" onClick={() => void apply()} disabled={busy}>
              {preview.quote.needs_driver_ok
                ? appText("Спросить водителя", "Йөрөтөүсенән һорау")
                : appText("Едем сюда", "Бында китәбеҙ")}
            </button>
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                previewGate.current.clear();
                setPreview(null);
                setPoint(null);
              }}
              disabled={busy}
            >
              {appText("Другой адрес", "Башҡа адрес")}
            </button>
          </div>
        </>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
        {appText("Закрыть", "Ябыу")}
      </button>
    </div>
  );
}

// ----------------------------- Остановки -----------------------------
/**
 * «Заехать по пути».
 *
 * Добавлять и убирать можно в любую сторону: «мама сама доехала» — живой случай,
 * и цена тогда падает. А вот порядок менять нельзя — водитель уже едет к первой точке,
 * и перестановка на ходу это путаница ради редкого случая. Проеденные точки не трогаем.
 */
function Stops({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useBusy();
  const [note, setNote] = useState("");

  const all = order.stops ?? [];
  const done = all.filter((s) => s.done);
  const live = all.filter((s) => !s.done);

  async function save(next: { lat: number; lng: number; text?: string }[]) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const q = await setWaypoints(order.id, next);
      setNote(
        appText(
          `Готово. Цена: ${q.price} ₽`,
          `Әҙер. Хаҡ: ${q.price} һум`
        )
      );
      onDone();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Попробуй ещё раз.", "Килеп сыҡманы. Тағы ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft trip-btn" onClick={() => setOpen(true)}>
        <IconCar size={18} />{" "}
        {live.length > 0
          ? appText(`Остановки (${live.length})`, `Туҡтауҙар (${live.length})`)
          : appText("Заехать по пути", "Юл ыңғайы инеү")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconCar size={18} /> {appText("Остановки по пути", "Юлдағы туҡтауҙар")}
      </div>
      <p className="act-card__text">
        {appText(
          "Не больше трёх. Каждая удлиняет дорогу и цену — сумму пересчитаем сразу.",
          "Өстән артыҡ түгел. Һәр береһе юлды ла, хаҡты ла оҙонайта — сумманы шунда уҡ иҫәпләйбеҙ."
        )}
      </p>

      {done.length > 0 && (
        <ul className="trip-stops">
          {done.map((s, i) => (
            <li key={`d${i}`} className="trip-stops__row trip-stops__row--done">
              <IconCheck size={16} />
              <span>{s.text || appText("Точка на карте", "Картала нөктә")}</span>
              <span className="trip-stops__tag">{appText("проехали", "үттек")}</span>
            </li>
          ))}
        </ul>
      )}

      {live.length > 0 && (
        <ul className="trip-stops">
          {live.map((s, i) => (
            <li key={`l${i}`} className="trip-stops__row">
              <IconPin size={16} />
              <span>{s.text || appText("Точка на карте", "Картала нөктә")}</span>
              <button
                type="button"
                className="trip-stops__del"
                disabled={busy}
                onClick={() =>
                  void save(
                    live
                      .filter((_, j) => j !== i)
                      .map((x) => ({ lat: x.lat, lng: x.lng, text: x.text }))
                  )
                }
              >
                {appText("Убрать", "Алып ташлау")}
              </button>
            </li>
          ))}
        </ul>
      )}

      {live.length < 3 && (
        <AddressSearch
          placeholder={appText("Куда заехать", "Ҡайҙа инергә")}
          onPick={(p) =>
            void save([
              ...live.map((x) => ({ lat: x.lat, lng: x.lng, text: x.text })),
              { lat: p.lat, lng: p.lng, text: p.text },
            ])
          }
        />
      )}
      {live.length >= 3 && (
        <p className="trip-hint">
          {ru ? "Больше трёх остановок не получится" : "Өс туҡтауҙан артыҡ булмай"}
        </p>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
        {appText("Закрыть", "Ябыу")}
      </button>
    </div>
  );
}

// ----------------------------- Способ расчёта -----------------------------
const PAY_LABELS: Record<PaymentMethod, { ru: string; ba: string }> = {
  cash: { ru: "Наличными", ba: "Аҡса менән" },
  sbp: { ru: "Переводом (СБП)", ba: "Күсереү (СБП)" },
  negotiate: { ru: "Договоримся на месте", ba: "Урынында килешәбеҙ" },
};

/**
 * Чем рассчитаемся. Меняется до самого конца поездки: про наличные человек вспоминает
 * ровно тогда, когда лезет в карман, то есть уже сидя в машине. Водителю уходит
 * уведомление — тихая подмена договорённости хуже, чем её честная смена.
 */
function PayMethod({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useBusy();
  const [note, setNote] = useState("");
  const current = (order.payment_method || "negotiate") as PaymentMethod;

  async function pick(m: PaymentMethod) {
    if (busy || m === current) {
      setOpen(false);
      return;
    }
    setBusy(true);
    setNote("");
    try {
      await setPaymentMethod(order.id, m);
      setOpen(false);
      onDone();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не сохранилось. Попробуй ещё раз.", "Һаҡланманы. Тағы ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft trip-btn" onClick={() => setOpen(true)}>
        <IconWallet size={18} />{" "}
        {appText(
          `Расчёт: ${PAY_LABELS[current]?.ru ?? PAY_LABELS.negotiate.ru}`,
          `Түләү: ${PAY_LABELS[current]?.ba ?? PAY_LABELS.negotiate.ba}`
        )}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconWallet size={18} /> {appText("Чем рассчитаемся", "Нимә менән түләйбеҙ")}
      </div>
      <p className="act-card__text">
        {appText(
          "Водитель увидит выбор сразу — на высадке не будет неожиданностей.",
          "Йөрөтөүсе һайлауыңды шунда уҡ күрә — төшкәндә көтөлмәгәнлек булмай."
        )}
      </p>
      {(Object.keys(PAY_LABELS) as PaymentMethod[]).map((m) => (
        <button
          key={m}
          type="button"
          className={m === current ? "btn-primary" : "btn-soft"}
          style={{ width: "100%", marginBottom: 8 }}
          disabled={busy}
          onClick={() => void pick(m)}
        >
          {appText(PAY_LABELS[m].ru, PAY_LABELS[m].ba)}
        </button>
      ))}
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
        {appText("Закрыть", "Ябыу")}
      </button>
    </div>
  );
}

// ----------------------------- Помощь на трассе -----------------------------
/**
 * «Застряли на трассе».
 *
 * Мягче красной кнопки SOS: близкие получают координаты, поддержка видит сигнал.
 * Числа в ответе честные — сколько людей реально предупреждено и сколько их вообще
 * заведено. Раньше экран писал «близкие получили твои координаты» даже тому,
 * кто доверенных контактов не добавлял, и человек на морозе переставал звонить сам.
 */
export function RoadsideButton({ orderId }: { orderId: number }) {
  const { appText } = useLang();
  const [busy, setBusy] = useBusy();
  const [note, setNote] = useState("");

  async function call() {
    if (busy) return;
    setBusy(true);
    setNote("");
    const send = (lat?: number, lng?: number) =>
      roadsideHelpOrder(orderId, { lat, lng })
        .then((r) => {
          // По факту доставки сигнала, а не по нажатию: не ушло — не считаем помощью.
          track("roadside_help");
          if (r.contacts_total === 0) {
            setNote(
              appText(
                "Поддержка получила сигнал. Близких у тебя в приложении нет — добавь их в «Доверенных», чтобы в следующий раз им ушло сообщение.",
                "Ярҙам хеҙмәте сигналды алды. Ҡәҙерлеләрең өҫтәлмәгән — киләһе юлы хәбәр китһен өсөн уларҙы «Ышаныслы кешеләр»гә өҫтә."
              )
            );
          } else if (r.contacts_notified === 0) {
            setNote(
              appText(
                "Поддержка получила сигнал. Сообщение близким сейчас не уходит — позвони им сам.",
                "Ярҙам хеҙмәте сигналды алды. Хәбәр ҡәҙерлеләргә китмәй — уларға үҙең шылтырат."
              )
            );
          } else {
            setNote(
              appText(
                `Помощь вызвана. Близким отправлено: ${r.contacts_notified}.`,
                `Ярҙам саҡырылды. Ҡәҙерлеләргә ебәрелде: ${r.contacts_notified}.`
              )
            );
          }
        })
        .catch(() =>
          setNote(
            appText(
              "Сигнал не ушёл — нет связи. Позвони близким или 112.",
              "Сигнал китмәне — бәйләнеш юҡ. Ҡәҙерлеләреңә йәки 112-гә шылтырат."
            )
          )
        )
        .finally(() => setBusy(false));

    if (!navigator.geolocation) {
      void send();
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => void send(p.coords.latitude, p.coords.longitude),
      () => void send(),
      { timeout: 8000 }
    );
  }

  return (
    <>
      <button type="button" className="btn-soft trip-btn trip-btn--warn" onClick={() => void call()} disabled={busy}>
        <IconWarn size={18} /> {appText("Застряли на трассе", "Юлда ҡалдыҡ")}
      </button>
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}

// ----------------------------- «Уже выхожу» -----------------------------
/**
 * Пассажир спускается — водитель это видит.
 *
 * Денег не меняет: таймер ожидания идёт как шёл. Это сообщение, а не сделка:
 * иначе кнопкой начали бы отматывать платное ожидание.
 */
function ImComing({ orderId }: { orderId: number }) {
  const { appText } = useLang();
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useBusy();

  if (sent) {
    return (
      <p className="trip-hint trip-hint--ok">
        <IconCheck size={16} /> {appText("Водитель знает, что ты выходишь", "Йөрөтөүсе сығыуыңды белә")}
      </p>
    );
  }
  return (
    <button
      type="button"
      className="btn-soft trip-btn"
      disabled={busy}
      onClick={() => {
        setBusy(true);
        imComing(orderId)
          .then(() => setSent(true))
          .catch(() => {
            /* сеть/статус сменился — кнопка просто останется, экран обновится поллингом */
          })
          .finally(() => setBusy(false));
      }}
    >
      <IconCheck size={18} /> {appText("Уже выхожу", "Сығып киләм")}
    </button>
  );
}

// ----------------------------- Зимняя проверка -----------------------------
/**
 * «Доехал?» — ответ на зимнюю проверку. Гасит эскалацию близким.
 *
 * Показываем только когда сервер уже спросил (пуш ушёл). Кнопка одна и большая:
 * человек отвечает на неё в дороге, часто в перчатках и в темноте.
 */
export function WinterCheckAnswer({ orderId, onDone }: { orderId: number; onDone?: () => void }) {
  const { appText } = useLang();
  const [busy, setBusy] = useBusy();
  const [done, setDone] = useState(false);

  if (done) return null;
  return (
    <div className="act-card act-card--mint">
      <div className="act-card__title">
        <IconCheck size={18} /> {appText("Всё в порядке?", "Бөтәһе лә яҡшымы?")}
      </div>
      <p className="act-card__text">
        {appText(
          "Отметь, что доехал(а) — иначе через полчаса мы позвоним твоим близким.",
          "Барып еткәнеңде билдәлә — юҡһа ярты сәғәттән ҡәҙерлеләреңә шылтыратабыҙ."
        )}
      </p>
      <button
        type="button"
        className="btn-primary"
        disabled={busy}
        onClick={() => {
          setBusy(true);
          winterCheckOrderOk(orderId)
            .then(() => {
              track("winter_check_order_ok");
              forgetWinterCheck(WINTER_ASKED_KEY(orderId));
              setDone(true);
              onDone?.();
            })
            .catch(() => {
              /* не ушло — кнопка остаётся, человек нажмёт ещё раз */
            })
            .finally(() => setBusy(false));
        }}
      >
        {appText("Я доехал(а)", "Мин барып еттем")}
      </button>
    </div>
  );
}

// ----------------------------- Очередь «рядом никого» -----------------------------
/**
 * «Подожду машину».
 *
 * В райцентре ночью на линии две-три машины, и обе заняты — это норма, а не сбой.
 * Раньше отказ был мгновенным и окончательным: человек получал «никого нет» за две
 * секунды и уходил к конкуренту. Теперь заказ встаёт в очередь, а поиск продолжается сам.
 */
export function WaitForCarCard({ order, onWaiting, onCancelled, onNewOrder }: {
  order: InstantOrder; onWaiting: () => void; onCancelled: () => void; onNewOrder: () => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useBusy();
  const [note, setNote] = useState("");
  const [waitUntil, setWaitUntil] = useState(order.wait_until);
  const inFlight = useRef(false);
  useEffect(() => { setWaitUntil(order.wait_until); }, [order.wait_until]);
  const waiting = serverMs(waitUntil) > Date.now();

  async function stopWaiting(after: () => void) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setNote("");
    try {
      await cancelInstantOrder(order.id, "changed_mind");
      setWaitUntil(null);
      after();
    } catch {
      setNote(appText("Не получилось остановить поиск. Заказ ещё в очереди — попробуй снова.",
        "Эҙләүҙе туҡтатып булманы. Заказ әле сиратта — тағы ҡабатла."));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function wait() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setNote("");
    try {
      const result = await waitForDriver(order.id);
      setWaitUntil(result.wait_until);
      setNote(appText(`Ждём машину ещё ${result.wait_minutes} минут.`, `Машинаны тағы ${result.wait_minutes} минут көтәбеҙ.`));
      onWaiting();
    } catch {
      setNote(appText("Не получилось встать в очередь. Попробуй ещё раз.", "Сиратҡа баҫып булманы. Тағы ҡабатла."));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconCar size={18} /> {appText("Могу подождать", "Көтә алам")}
      </div>
      <p className="act-card__text">
        {appText(
          "Оставим заказ в очереди и продолжим искать. Найдётся машина — пришлём уведомление, ждать с открытым экраном не нужно.",
          "Заказды сиратта ҡалдырабыҙ һәм эҙләүҙе дауам итәбеҙ. Машина табылһа — хәбәр итәбеҙ, экранды асыҡ тотоу кәрәкмәй."
        )}
      </p>
      <button
        type="button"
        className="btn-primary"
        disabled={busy}
        onClick={waiting ? () => stopWaiting(onCancelled) : wait}
      >
        {waiting ? appText("Не ждать машину", "Машинаны көтмәҫкә") : appText("Подождать машину", "Машинаны көтөү")}
      </button>
      <button type="button" className="btn-ghost" disabled={busy} onClick={() => {
        if (inFlight.current) return;
        if (waiting) return stopWaiting(onNewOrder);
        onNewOrder();
      }}>{appText("Попробовать снова", "Ҡабат ҡарау")}</button>
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </div>
  );
}

// ----------------------------- Зимняя проверка: когда спрашивать -----------------------------
/** Скорость по зимней трассе, км/ч — по ней считаем, когда поездка должна была кончиться. */
const WINTER_SPEED_KMH = 55;
/** Запас сверх расчётного времени: пробка, заправка, магазин по пути. */
const WINTER_BUFFER_MS = 25 * 60 * 1000;
/** Нет ни времени, ни расстояния — спрашиваем через два часа. */
const WINTER_FALLBACK_MS = 2 * 60 * 60 * 1000;
/** Один вопрос на заказ: сессия помнит, что уже спрашивали. */
const WINTER_ASKED_KEY = (id: number) => `yuldash.winterAskOrder.${id}`;

/**
 * Когда пора спросить «доехал?».
 *
 * Считаем от посадки: сервер отдаёт eta_min и расстояние, а тикать секунды на нём незачем.
 * Если ни того, ни другого нет — спрашиваем через два часа: это заведомо позже любой
 * городской поездки и заведомо раньше, чем человека перестанут искать.
 */
function winterDueAt(order: InstantOrder): number {
  const started = serverMs(order.created_at);
  if (Number.isNaN(started)) return Number.POSITIVE_INFINITY;
  if (order.eta_min > 0) return started + order.eta_min * 60_000 + WINTER_BUFFER_MS;
  if (order.distance_km > 0) {
    return started + (order.distance_km / WINTER_SPEED_KMH) * 3_600_000 + WINTER_BUFFER_MS;
  }
  return started + WINTER_FALLBACK_MS;
}

/**
 * Зимний протокол в такси: молчание пассажира само зовёт помощь.
 *
 * Дорога Сибай–Уфа зимой — четыре часа, и пассажир такси едет её один с незнакомым
 * водителем. По истечении расчётного времени спрашиваем «всё в порядке?»; не ответил
 * полчаса — сервер зовёт тех, кому он расшарил поездку.
 *
 * Спрашиваем ТОЛЬКО пассажира: водитель за рулём, и его «да» ничего не говорит о том,
 * кого он везёт.
 */
function useWinterCheck(order: InstantOrder): boolean {
  const [askForOrderId, setAskForOrderId] = useState<number | null>(null);

  useEffect(() => {
    if (order.role !== "passenger" || order.status !== "onboard") {
      setAskForOrderId(null);
      return;
    }
    const key = WINTER_ASKED_KEY(order.id);
    const asked = wasWinterCheckAsked(key);

    const delay = winterCheckDelay(asked, winterDueAt(order));
    if (!Number.isFinite(delay)) return;
    let alive = true;
    const timer = window.setTimeout(() => {
      // Сервер идемпотентен: сам решает, не рано ли, и сам шлёт пуш обеим сторонам.
      winterCheckOrder(order.id)
        .then((r) => {
          if (!alive) return;
          const needsAnswer = winterCheckNeedsAnswer(r.state);
          setAskForOrderId(needsAnswer ? order.id : null);
          if (needsAnswer) rememberWinterCheck(key);
          else if (r.state === "ok" || r.state === "closed") forgetWinterCheck(key);
        })
        .catch(() => {
          // Уже полученный вопрос важнее временной ошибки сети: возможность ответить сохраняем.
          if (alive && asked) setAskForOrderId(order.id);
        });
    }, delay);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [order.id, order.role, order.status, order.eta_min, order.distance_km, order.created_at]);

  return askForOrderId === order.id;
}

// ----------------------------- Сборка -----------------------------
/**
 * Все действия пассажира в поездке одним блоком.
 *
 * Что показываем — зависит от того, где машина: пока едет к нам, нужна кнопка
 * «уже выхожу»; когда мы уже в салоне — смена адреса, остановки и помощь на трассе.
 * Показывать всё сразу значило бы утопить нужную кнопку среди ненужных.
 */
export default function TaxiTripActions({
  order,
  onChanged,
}: {
  order: InstantOrder;
  onChanged: () => void;
}) {
  const { appText } = useLang();
  const s = order.status;
  const arriving = s === "arriving";
  const onboard = s === "onboard";
  const enRoute = s === "accepted" || arriving;
  const winterAsk = useWinterCheck(order);

  if (!enRoute && !onboard) return null;

  return (
    <div className="trip-actions">
      {/* Расчётное время вышло, а поездка идёт. Один вопрос — и близкие спокойны. */}
      {winterAsk && <WinterCheckAnswer key={order.id} orderId={order.id} />}
      {/* Водитель уже у подъезда — самая нужная кнопка сейчас одна. */}
      {arriving && <ImComing orderId={order.id} />}

      {/* Водитель ещё не подтвердил, что видел новый адрес. Через минуту молчания
          говорим об этом прямо: за рулём в телефон смотреть некогда, а человеку
          важно понимать, куда его везут. */}
      {order.pending_destination && (
        <div className="act-card act-card--warn">
          <div className="act-card__title">
            <IconPin size={18} /> {appText("Ждём ответа водителя", "Йөрөтөүсе яуабын көтәбеҙ")}
          </div>
          <p className="act-card__text">
            {appText(
              `Предложили ехать на «${order.pending_destination.to_text}» за ${order.pending_destination.price} ₽. Пока он не согласится, едем по прежнему адресу.`,
              `«${order.pending_destination.to_text}» тигән урынға ${order.pending_destination.price} һумға барырға тәҡдим иттек. Ул риза булғансы, элекке адрес буйынса барабыҙ.`
            )}
          </p>
        </div>
      )}
      {!order.pending_destination &&
        order.destination_ack_overdue &&
        (order.destination_changes ?? 0) > 0 && (
          <div className="act-card act-card--warn">
            <div className="act-card__title">
              <IconWarn size={18} /> {appText("Водитель ещё не отметил новый адрес", "Йөрөтөүсе яңы адресты билдәләмәгән")}
            </div>
            <p className="act-card__text">
              {appText(
                "Он за рулём и мог не увидеть. Позвони — так надёжнее.",
                "Ул руль артында, күрмәгән булыуы ихтимал. Шылтырат — шулай ышаныслыраҡ."
              )}
            </p>
          </div>
        )}

      {(onboard || enRoute) && <ChangeDestination order={order} onDone={onChanged} />}
      {(onboard || enRoute) && <Stops order={order} onDone={onChanged} />}
      <PayMethod order={order} onDone={onChanged} />
      {onboard && <RoadsideButton orderId={order.id} />}
    </div>
  );
}
