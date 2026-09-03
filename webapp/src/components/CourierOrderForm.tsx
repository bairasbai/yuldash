// ================================================================
//  Заказать платного курьера (courier.py: GET /courier/estimate,
//  POST /courier/orders). Зеркало Android CourierScreen.kt.
//
//  Чем отличается от «по пути». Попутная посылка едет с тем, кто и так
//  туда собрался: дёшево, но когда получится. Курьер едет специально
//  за деньги — и тогда у отправителя есть срок и цена заранее.
//  В вебе был только первый способ: заказать курьера было нельзя
//  вовсе (сверка с Android, 2026-08-30).
//
//  «Купи и привези» — тот же курьер, но он сначала тратит СВОИ деньги
//  на товар. Поэтому сумма к возврату спрашивается отдельно и прямо:
//  человек должен понимать, что вернёт её курьеру из рук в руки.
//
//  Цену считает СЕРВЕР. Без координат честной цены не существует —
//  так и говорим, а не показываем «примерно».
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  createCourierOrder,
  estimateCourier,
  type CourierEstimate,
  type CourierUrgency,
  type DeliveryType,
  type ParcelSize,
} from "../api/courier";
import { geocode } from "../api/discovery";
import { kopExactLabel } from "../utils/format";
import { IconPin } from "./Icons";

interface Pt {
  lat: number;
  lng: number;
  text: string;
}

/** Поле адреса с подсказками. Координаты нужны для цены — без них её не посчитать. */
function AddrField({
  label,
  value,
  onPick,
  onClear,
}: {
  label: string;
  value: Pt | null;
  onPick: (p: Pt) => void;
  onClear: () => void;
}) {
  const { appText } = useLang();
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<Pt[]>([]);
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
    <label className="field">
      <span className="field__label">{label}</span>
      {value ? (
        <button type="button" className="taxi-route__chosen" onClick={onClear}>
          <span className="taxi-route__value">{value.text}</span>
          <span className="taxi-route__change">{appText("Изменить", "Үҙгәртеү")}</span>
        </button>
      ) : (
        <div className="trip-search">
          <input
            className="field__input"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={appText("Город, улица, дом", "Ҡала, урам, йорт")}
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
        </div>
      )}
    </label>
  );
}

export default function CourierOrderForm({ onCreated }: { onCreated: () => void }) {
  const { appText } = useLang();
  const [from, setFrom] = useState<Pt | null>(null);
  const [to, setTo] = useState<Pt | null>(null);
  const [size, setSize] = useState<ParcelSize>("small");
  const [urgency, setUrgency] = useState<CourierUrgency>("bypath");
  const [dtype, setDtype] = useState<DeliveryType>("courier");
  const [desc, setDesc] = useState("");
  const [shopping, setShopping] = useState("");
  const [cod, setCod] = useState("");
  const [receiver, setReceiver] = useState("");
  const [phone, setPhone] = useState("");
  const [rules, setRules] = useState(false);
  const [est, setEst] = useState<CourierEstimate | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState<string | null>(null);

  // Цена: пересчитываем при любом изменении, которое на неё влияет.
  useEffect(() => {
    if (!from || !to) {
      setEst(null);
      return;
    }
    const ac = new AbortController();
    let alive = true;
    estimateCourier(
      {
        from_lat: from.lat,
        from_lng: from.lng,
        to_lat: to.lat,
        to_lng: to.lng,
        size,
        urgency,
        delivery_type: dtype,
      },
      ac.signal
    )
      .then((e) => alive && setEst(e))
      .catch(() => alive && setEst(null));
    return () => {
      alive = false;
      ac.abort();
    };
  }, [from, to, size, urgency, dtype]);

  const codKop = Math.round(Number(cod.replace(",", ".")) * 100) || 0;
  const canSubmit =
    !!from &&
    !!to &&
    receiver.trim().length > 0 &&
    rules &&
    !busy &&
    (dtype !== "buy_bring" || (codKop > 0 && shopping.trim().length > 0));

  async function submit() {
    if (!canSubmit || !from || !to) return;
    setBusy(true);
    setError(null);
    try {
      const r = await createCourierOrder({
        from_address: from.text,
        to_address: to.text,
        from_lat: from.lat,
        from_lng: from.lng,
        to_lat: to.lat,
        to_lng: to.lng,
        size,
        urgency,
        delivery_type: dtype,
        description: desc.trim(),
        shopping_list: dtype === "buy_bring" ? shopping.trim() : "",
        cod_amount_kop: dtype === "buy_bring" ? codKop : 0,
        receiver_name: receiver.trim(),
        receiver_phone: phone.trim(),
        rules_accepted: true,
      });
      setCode(String(r.confirm_code ?? ""));
      onCreated();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать заказ. Проверь сеть.", "Заказ булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  // Заказ создан — главное теперь код: без него курьер вещь не отдаст.
  if (code !== null) {
    return (
      <div className="act-card act-card--mint">
        <div className="act-card__title">{appText("Курьер вызван", "Курьер саҡырылды")}</div>
        <p className="act-card__text">
          {appText(
            "Передай этот код получателю. Курьер отдаст посылку только тому, кто его назовёт.",
            "Был кодты алыусыға тапшыр. Курьер аҫылманы уны әйткән кешегә генә бирә."
          )}
        </p>
        {code && <div className="code-big">{code}</div>}
      </div>
    );
  }

  return (
    <>
      {/* Что за доставка */}
      <div className="chips">
        <button
          type="button"
          className={"chip" + (dtype === "courier" ? " chip--on" : "")}
          onClick={() => setDtype("courier")}
          aria-pressed={dtype === "courier"}
        >
          {appText("Отвезти", "Илтеү")}
        </button>
        <button
          type="button"
          className={"chip" + (dtype === "buy_bring" ? " chip--on" : "")}
          onClick={() => setDtype("buy_bring")}
          aria-pressed={dtype === "buy_bring"}
        >
          {appText("Купить и привезти", "Һатып алып килтереү")}
        </button>
      </div>

      <AddrField
        label={appText("Откуда забрать", "Ҡайҙан алырға")}
        value={from}
        onPick={setFrom}
        onClear={() => setFrom(null)}
      />
      <AddrField
        label={appText("Куда привезти", "Ҡайҙа килтерергә")}
        value={to}
        onPick={setTo}
        onClear={() => setTo(null)}
      />

      {/* Размер и срочность — обе вещи меняют цену, поэтому стоят до неё. */}
      <div className="chips">
        {(["small", "medium", "large"] as ParcelSize[]).map((s) => (
          <button
            key={s}
            type="button"
            className={"chip" + (size === s ? " chip--on" : "")}
            onClick={() => setSize(s)}
            aria-pressed={size === s}
          >
            {s === "small"
              ? appText("Небольшая", "Бәләкәй")
              : s === "medium"
                ? appText("Средняя", "Уртаса")
                : appText("Крупная", "Ҙур")}
          </button>
        ))}
      </div>
      <div className="chips">
        <button
          type="button"
          className={"chip" + (urgency === "bypath" ? " chip--on" : "")}
          onClick={() => setUrgency("bypath")}
          aria-pressed={urgency === "bypath"}
        >
          {appText("Когда получится", "Ҡасан булыр")}
        </button>
        <button
          type="button"
          className={"chip" + (urgency === "now" ? " chip--on" : "")}
          onClick={() => setUrgency("now")}
          aria-pressed={urgency === "now"}
        >
          {appText("Нужно сейчас", "Хәҙер кәрәк")}
        </button>
      </div>

      {/* Купи и привези: курьер платит своими, и человек должен это понимать заранее. */}
      {dtype === "buy_bring" && (
        <>
          <label className="field">
            <span className="field__label">{appText("Что купить", "Нимә һатып алырға")}</span>
            <input
              className="field__input"
              value={shopping}
              onChange={(e) => setShopping(e.target.value.slice(0, 2000))}
              placeholder={appText("«Хлеб, молоко, лекарство из аптеки»", "«Икмәк, һөт, дарыу»")}
            />
          </label>
          <label className="field">
            <span className="field__label">{appText("Сколько отдать курьеру, ₽", "Курьерға күпме бирергә, һум")}</span>
            <input
              className="field__input"
              inputMode="numeric"
              value={cod}
              onChange={(e) => setCod(e.target.value.replace(/[^\d]/g, ""))}
              maxLength={7}
            />
            <span className="field__hint">
              {appText(
                "Курьер купит на свои и отдаст чек. Эту сумму вернёшь ему при получении.",
                "Курьер үҙ аҡсаһына ала һәм чек бирә. Был сумманы алғанда ҡайтараһың."
              )}
            </span>
          </label>
        </>
      )}

      <label className="field">
        <span className="field__label">{appText("Что везём", "Нимә илтәбеҙ")}</span>
        <input
          className="field__input"
          value={desc}
          onChange={(e) => setDesc(e.target.value.slice(0, 2000))}
          placeholder={appText("«Пакет документов»", "«Документтар пакеты»")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Кто получит", "Кем ала")}</span>
        <input
          className="field__input"
          value={receiver}
          onChange={(e) => setReceiver(e.target.value.slice(0, 120))}
          placeholder={appText("Имя получателя", "Алыусының исеме")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Телефон получателя", "Алыусының телефоны")}</span>
        <input
          className="field__input"
          inputMode="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value.slice(0, 40))}
          placeholder="+7 …"
        />
      </label>

      {/* Цена. Строками, а не одним числом: человек должен видеть, за что платит. */}
      {est && (
        <div className="info-list">
          <div className="info-row">
            <span className="info-row__k">{appText("Доставка", "Илтеү")}</span>
            <span className="info-row__v">{kopExactLabel(est.delivery_kop)}</span>
          </div>
          {est.weather_kop > 0 && (
            <div className="info-row">
              <span className="info-row__k">{appText("Зимняя дорога", "Ҡышҡы юл")}</span>
              <span className="info-row__v">{kopExactLabel(est.weather_kop)}</span>
            </div>
          )}
          {/* Курьера ещё нет — честной суммы за его дорогу не существует. Говорим потолок. */}
          {est.pickup_pending && est.pickup_max_kop > 0 && (
            <div className="info-row">
              <span className="info-row__k">{appText("Дорога курьера", "Курьер юлы")}</span>
              <span className="info-row__v">
                {appText(
                  `не больше ${kopExactLabel(est.pickup_max_kop)}`,
                  `${kopExactLabel(est.pickup_max_kop)} артыҡ түгел`
                )}
              </span>
            </div>
          )}
          <div className="info-row">
            <span className="info-row__k">{appText("Итого", "Барлығы")}</span>
            <span className="info-row__v">
              <b>{kopExactLabel(est.price_kop)}</b>
            </span>
          </div>
          {est.distance_km > 0 && (
            <div className="info-row">
              <span className="info-row__k">{appText("Расстояние", "Ара")}</span>
              <span className="info-row__v">{est.distance_km.toFixed(1)} км</span>
            </div>
          )}
        </div>
      )}
      {/* Комиссия платформы зависит от стажа курьера, а курьера ещё нет. Говорим об этом
          прямо: иначе итоговая сумма при вручении разойдётся с обещанной, и виноватыми
          будем мы, а не арифметика. */}
      {/* Сколько будет стоить возврат, если получателя не найдут. Говорим ДО заказа:
          плату за возврат нельзя брать с того, кого о ней не предупредили. */}
      {(est?.return_fee_estimate_kop ?? 0) > 0 && (
        <p className="trip-hint">
          {appText(
            `Если получателя не найдут и посылку повезут обратно — вернёшь курьеру за дорогу около ${kopExactLabel(est?.return_fee_estimate_kop ?? 0)}. Сама доставка при возврате не оплачивается.`,
            `Алыусы табылмаһа һәм аҫылманы кире алып ҡайтһалар — юл өсөн курьерға яҡынса ${kopExactLabel(est?.return_fee_estimate_kop ?? 0)} ҡайтараһың. Илтеүҙең үҙе кире ҡайтарыуҙа түләнмәй.`
          )}
        </p>
      )}

      {est?.commission_estimated && (
        <p className="trip-hint">
          {appText(
            "Комиссия платформы посчитана примерно — точную посчитаем, когда заказ возьмёт курьер.",
            "Платформа комиссияһы яҡынса иҫәпләнде — курьер заказды алғас, теүәлен иҫәпләрбеҙ."
          )}
        </p>
      )}
      {!est && from && to && (
        <p className="trip-hint">{appText("Считаем цену…", "Хаҡты иҫәпләйбеҙ…")}</p>
      )}
      {(!from || !to) && (
        <p className="trip-hint">
          {appText(
            "Укажи оба адреса — тогда посчитаем цену точно, а не «примерно».",
            "Ике адресты ла күрһәт — шунда хаҡты теүәл иҫәпләйбеҙ, «яҡынса» түгел."
          )}
        </p>
      )}

      <label className="list-row list-row--check">
        <div className="list-row__main">
          <div className="list-row__sub">
            {appText(
              "Не отправляю запрещённое: деньги, документы на предъявителя, оружие, алкоголь, скоропортящееся.",
              "Тыйылғанды ебәрмәйем: аҡса, кем килтерһә шуға документ, ҡорал, алкоголь, тиҙ боҙолған нәмә."
            )}
          </div>
        </div>
        <input
          type="checkbox"
          className="checkbox"
          checked={rules}
          onChange={(e) => setRules(e.target.checked)}
          aria-label={appText("Согласен с правилами", "Ҡағиҙәләр менән килешәм")}
        />
      </label>

      {error && (
        <div className="notice" role="status">
          {error}
        </div>
      )}

      <button
        type="button"
        className="btn-primary submit-btn"
        onClick={() => void submit()}
        disabled={!canSubmit}
      >
        {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Вызвать курьера", "Курьер саҡырыу")}
      </button>
    </>
  );
}
