// ================================================================
//  Опции салона и остановки по пути — при заказе такси.
//  Зеркало Android (InstantOrderScreen.kt: InstantOptions, стопы).
//
//  Опции. Детское кресло, коляска, собака-проводник, животное,
//  большой багаж, зарядка. Это НЕ класс машины: кресло возит любая.
//  Фильтр жёсткий — машине без кресла такой заказ не предложат
//  вообще, в этом и смысл галочки. Раньше в вебе выбора не было:
//  мама с ребёнком заказывала обычную машину и узнавала об отсутствии
//  кресла у подъезда (сверка с Android, 2026-08-30).
//
//  Остановки. A → точки → B, не больше трёх: каждая удлиняет дорогу
//  и цену, а на четвёртой водители начинают отказываться.
//
//  Кресла и багаж стоят денег, доступность (коляска, собака-проводник)
//  и зарядка — нет. Цену считает сервер: он присылает каталог с
//  ценами (`option_catalog`), мы только показываем.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { geocode } from "../api/discovery";
import { IconPin } from "./Icons";

/** Опции салона. Коды совпадают с backend/app/car_class.py. */
export const TAXI_OPTIONS: { code: string; ru: string; ba: string; emoji: string }[] = [
  { code: "seat_0_1", ru: "Люлька 0–1", ba: "Бәпес арбаһы 0–1", emoji: "👶" },
  { code: "seat_1_4", ru: "Кресло 1–4", ba: "Ултырғыс 1–4", emoji: "🧒" },
  { code: "seat_4_7", ru: "Кресло 4–7", ba: "Ултырғыс 4–7", emoji: "🧒" },
  { code: "booster", ru: "Бустер 7–12", ba: "Бустер 7–12", emoji: "💺" },
  { code: "stroller", ru: "Коляска", ba: "Балалар арбаһы", emoji: "🍼" },
  { code: "wheelchair", ru: "Инвалидная коляска", ba: "Инвалид коляскаһы", emoji: "♿" },
  { code: "guide_dog", ru: "Собака-проводник", ba: "Юл күрһәтеүсе эт", emoji: "🦮" },
  { code: "pets", ru: "С животным", ba: "Хайуан менән", emoji: "🐾" },
  { code: "big_luggage", ru: "Большой багаж", ba: "Ҙур багаж", emoji: "🧳" },
  { code: "charger", ru: "Зарядка в машине", ba: "Машинала зарядка", emoji: "🔌" },
];

export interface OrderStop {
  lat: number;
  lng: number;
  text: string;
}

/**
 * Опции салона чипами. Цена рядом — там, где она есть: «кресло 150 ₽» человек примет,
 * а вот тихую прибавку в итоговой сумме воспримет как обман.
 */
export function TaxiOptions({
  selected,
  prices,
  onToggle,
}: {
  selected: string[];
  /** code → ₽, из `option_catalog` сервера. Пусто = цена ещё не пришла. */
  prices?: Record<string, number>;
  onToggle: (code: string) => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [open, setOpen] = useState(false);

  if (!open) {
    return (
      <button type="button" className="link-btn link-btn--quiet" onClick={() => setOpen(true)}>
        {selected.length > 0
          ? appText(`Что нужно в салоне (${selected.length})`, `Салонда нимә кәрәк (${selected.length})`)
          : appText("Нужно кресло, коляска, животное?", "Ултырғыс, арба, хайуан кәрәкме?")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">{appText("Что нужно в салоне", "Салонда нимә кәрәк")}</div>
      <p className="act-card__text">
        {appText(
          "Отметь честно: машину без нужного оборудования к тебе просто не отправят. Кресло и багаж платные, доступность и зарядка — бесплатно.",
          "Дөрөҫ билдәлә: кәрәкле йыһазһыҙ машинаны һиңә ебәрмәйҙәр. Ултырғыс менән багаж түләүле, доступность һәм зарядка — бушлай."
        )}
      </p>
      <div className="chips">
        {TAXI_OPTIONS.map((o) => {
          const on = selected.includes(o.code);
          const price = prices?.[o.code] ?? 0;
          return (
            <button
              key={o.code}
              type="button"
              className={"chip" + (on ? " chip--on" : "")}
              aria-pressed={on}
              onClick={() => onToggle(o.code)}
            >
              {o.emoji} {appText(o.ru, o.ba)}
              {price > 0 ? (ru ? ` · ${price} ₽` : ` · ${price} һум`) : ""}
            </button>
          );
        })}
      </div>
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
        {appText("Готово", "Әҙер")}
      </button>
    </div>
  );
}

/**
 * Остановки по пути при заказе. Поиск адреса тот же, что в основной форме,
 * с той же паузой перед запросом: человек печатает быстрее, чем отвечает сервер.
 */
export function TaxiStops({
  stops,
  onChange,
}: {
  stops: OrderStop[];
  onChange: (next: OrderStop[]) => void;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<OrderStop[]>([]);
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

  if (!open) {
    return (
      <button type="button" className="link-btn link-btn--quiet" onClick={() => setOpen(true)}>
        {stops.length > 0
          ? appText(`Заезды по пути (${stops.length})`, `Юл ыңғайы туҡтауҙар (${stops.length})`)
          : appText("Заехать по пути", "Юл ыңғайы инеү")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">{appText("Заехать по пути", "Юл ыңғайы инеү")}</div>
      <p className="act-card__text">
        {appText(
          "Не больше трёх точек. Каждая удлиняет дорогу и цену — пересчитаем сразу.",
          "Өстән артыҡ түгел. Һәр береһе юлды ла, хаҡты ла оҙонайта — шунда уҡ иҫәпләйбеҙ."
        )}
      </p>

      {stops.length > 0 && (
        <ul className="trip-stops">
          {stops.map((s, i) => (
            <li key={i} className="trip-stops__row">
              <IconPin size={16} />
              <span>{s.text}</span>
              <button
                type="button"
                className="trip-stops__del"
                onClick={() => onChange(stops.filter((_, j) => j !== i))}
              >
                {appText("Убрать", "Алып ташлау")}
              </button>
            </li>
          ))}
        </ul>
      )}

      {stops.length < 3 && (
        <div className="trip-search">
          <input
            className="taxi-route__input"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={appText("Куда заехать", "Ҡайҙа инергә")}
            aria-label={appText("Куда заехать", "Ҡайҙа инергә")}
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
                    onChange([...stops, h]);
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

      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
        {appText("Готово", "Әҙер")}
      </button>
    </div>
  );
}

/** Сколько водитель ждёт на месте. Больше четырёх часов не предлагаем — у него смена. */
const WAIT_HOURS = [1, 2, 3, 4];

/**
 * «Обратно тоже» — круговой рейс.
 *
 * Водитель везёт, ждёт на месте и возвращает. Обратная дорога дешевле: второй конец
 * достался ему без нового поиска пассажира, и порожняка нет. Поэтому предложение живёт
 * только на межгороде — в городе экономить не на чем, и сервер там его не отдаёт.
 *
 * Ожидание отдельно не оплачивается. Но рамку человек должен понимать заранее: это не
 * «жди сколько хочешь», у водителя смена, и опоздание означает новый заказ.
 */
export function TaxiRoundTrip({
  available,
  price,
  discountPercent,
  maxWaitHours,
  checked,
  waitMin,
  onChecked,
  onWaitMin,
}: {
  available: boolean;
  price?: number | null;
  discountPercent?: number;
  maxWaitHours?: number;
  checked: boolean;
  waitMin: number;
  onChecked: (v: boolean) => void;
  onWaitMin: (min: number) => void;
}) {
  const { appText } = useLang();
  const maxHours = Math.max(1, maxWaitHours ?? 4);
  const discount = discountPercent ?? 0;

  // Человек передвинул точку Б в черту города — предложение исчезло. Гасим и флаг:
  // иначе он молча уедет в заказ, и цена не сойдётся с той, что была на экране.
  useEffect(() => {
    if (!available && checked) onChecked(false);
  }, [available, checked, onChecked]);

  if (!available) return null;

  return (
    <div className="act-card">
      <label className="list-row list-row--check" style={{ padding: 0 }}>
        <div className="list-row__main">
          <div className="list-row__title">{appText("Обратно тоже", "Кире лә")}</div>
          <div className="list-row__sub">
            {discount > 0
              ? appText(
                  `Водитель довезёт, подождёт и вернёт обратно. Обратная дорога — на ${discount}% дешевле.`,
                  `Йөрөтөүсе илтә, көтә һәм кире ҡайтара. Кире юл ${discount}% арзаныраҡ.`
                )
              : appText(
                  "Водитель довезёт, подождёт и вернёт обратно.",
                  "Йөрөтөүсе илтә, көтә һәм кире ҡайтара."
                )}
          </div>
        </div>
        <input
          type="checkbox"
          className="checkbox"
          checked={checked}
          onChange={(e) => onChecked(e.target.checked)}
          aria-label={appText("Обратно тоже", "Кире лә")}
        />
      </label>

      {checked && (
        <>
          <span className="field__label" style={{ display: "block", marginTop: 12 }}>
            {appText("Сколько ждать на месте", "Урында күпме көтөргә")}
          </span>
          <div className="chips">
            {WAIT_HOURS.filter((h) => h <= maxHours).map((h) => (
              <button
                key={h}
                type="button"
                className={"chip" + (waitMin === h * 60 ? " chip--on" : "")}
                onClick={() => onWaitMin(h * 60)}
                aria-pressed={waitMin === h * 60}
              >
                {appText(`${h} ч`, `${h} сәғәт`)}
              </button>
            ))}
          </div>
          {price != null && price > 0 && (
            <p className="act-card__text" style={{ marginTop: 8, marginBottom: 0 }}>
              {appText(`Туда и обратно: ${price} ₽`, `Барыу-ҡайтыу: ${price} һум`)}
            </p>
          )}
          <p className="trip-hint">
            {appText(
              "Ждать дольше водитель не сможет — у него смена. Задержишься — поездку придётся заказать заново.",
              "Йөрөтөүсе оҙағыраҡ көтә алмай — уның сменаһы бар. Һуңлаһаң, сәфәрҙе яңынан заказ итергә тура килә."
            )}
          </p>
        </>
      )}
    </div>
  );
}
