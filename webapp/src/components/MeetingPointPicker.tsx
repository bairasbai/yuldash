import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchPickupPoints, type PickupPoint } from "../api/geo";
import type { MeetingPointInput } from "../api/bookings";
import YandexMap from "./YandexMap";

/** RS-03 А: «Уточнить место (по желанию)» — ориентир села или точка на карте + пояснение.
 *  Ничего не обязательно: пусто — бронь как раньше. Сервер проверяет радиус от места посадки. */
export function MeetingPointPicker({
  city, title, value, disabled, onChange,
}: {
  city: string;
  title: string;
  value: MeetingPointInput | null;
  disabled?: boolean;
  onChange: (v: MeetingPointInput | null) => void;
}) {
  const { appText, lang } = useLang();
  const [open, setOpen] = useState(false);
  const [points, setPoints] = useState<PickupPoint[]>([]);
  const [showMap, setShowMap] = useState(false);
  const note = value?.note ?? "";
  const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
  const [retry, setRetry] = useState(0);
  const previousCity = useRef(city);
  const latest = useRef({ value, disabled, onChange });
  latest.current = { value, disabled, onChange };

  useEffect(() => {
    if (previousCity.current !== city) {
      previousCity.current = city;
      setPoints([]);
      setShowMap(false);
      onChange(null);
    }
  }, [city, onChange]);

  useEffect(() => {
    if (!open) return;
    let live = true;
    setLoadState("loading");
    setPoints([]);
    fetchPickupPoints(city)
      .then((rows) => { if (live) { setPoints(rows); setLoadState("ready"); } })
      .catch(() => { if (live) setLoadState("error"); });
    return () => { live = false; };
  }, [open, city, retry]);

  const label = !value || value.clear
    ? appText("По желанию — ориентир или точка на карте", "Телһәләгең буйынса — билгеле урын йәки карта нөктәһе")
    : value.landmark_id
      ? (() => { const p = points.find((x) => x.id === value.landmark_id);
                 return p ? (lang === "ba" && p.title_ba ? p.title_ba : p.title_ru) : appText("Ориентир выбран", "Билгеле урын һайланды"); })()
      : value.lat != null
        ? appText("Точка на карте указана", "Карта нөктәһе күрһәтелгән")
        : value.note || appText("Пояснение указано", "Аңлатма яҙылған");

  return (
    <div className="card" style={{ display: "grid", gap: 10 }}>
      <button type="button" className="btn-ghost" style={{ minHeight: 48, textAlign: "left" }}
        disabled={disabled} onClick={() => setOpen((v) => !v)}>
        <b>{title}</b>
        <div className="muted" style={{ fontSize: 13 }}>{label}</div>
      </button>
      {open && (
        <div style={{ display: "grid", gap: 10 }}>
          <div className="muted" style={{ fontSize: 13 }}>
            {appText("Выбери ориентир или поставь точку на карте", "Билгеле урын һайла йәки картаға нөктә ҡуй")}
          </div>
          {loadState === "loading" && <p role="status" className="muted">{appText("Загружаем ориентиры…", "Билгеле урындарҙы тейәйбеҙ…")}</p>}
          {loadState === "error" && (
            <div role="alert">
              <p>{appText("Не получилось загрузить ориентиры.", "Билгеле урындарҙы тейәп булманы.")}</p>
              <button type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={disabled} onClick={() => setRetry((n) => n + 1)}>
                {appText("Повторить", "Ҡабатлау")}
              </button>
            </div>
          )}
          {loadState === "ready" && points.length === 0 && <p className="muted">{appText("Ориентиров пока нет — поставь точку на карте", "Билгеле урындар әлегә юҡ — картаға нөктә ҡуй")}</p>}
          {loadState === "ready" && points.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {points.map((p) => (
                <button key={p.id} type="button" className={"chip" + (value?.landmark_id === p.id ? " chip--on" : "")}
                  style={{ minHeight: 48 }} disabled={disabled}
                  onClick={() => onChange({ landmark_id: p.id, note: note.trim() || undefined })}>
                  {lang === "ba" && p.title_ba ? p.title_ba : p.title_ru}
                </button>
              ))}
            </div>
          )}
          <button type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={disabled}
            onClick={() => setShowMap((v) => !v)}>
            {appText("На карте", "Картала")}
          </button>
          {showMap && (
            <>
              <YandexMap
                height={260}
                center={value?.lat != null && value?.lng != null ? { lat: value.lat, lng: value.lng } : undefined}
                onCenterChange={(p) => {
                  const current = latest.current;
                  if (!current.disabled) current.onChange({ lat: p.lat, lng: p.lng, note: current.value?.note?.trim() || undefined });
                }}
              />
              <div className="muted" style={{ fontSize: 13 }}>
                {appText("Двигай карту — точка под булавкой в центре", "Картаны ҡуҙғат — нөктә уртадағы бөгөл аҫтында")}
              </div>
            </>
          )}
          <label className="field">
            <span className="field__label">{appText("Пояснение: ворота, подъезд, что рядом", "Аңлатма: ҡапҡа, инеү урыны, янында нимә")}</span>
            <input className="field__input" value={note} maxLength={120} disabled={disabled}
              onChange={(e) => onChange({ ...(value?.clear ? {} : value), note: e.target.value })} />
          </label>
          <div style={{ display: "flex", gap: 8 }}>
            <button type="button" className="btn-primary" style={{ minHeight: 48, flex: 1 }} disabled={disabled}
              onClick={() => {
                if (value && !value.clear) onChange({ ...value, note: note.trim() || undefined });
                setOpen(false);
              }}>
              {appText("Готово", "Әҙер")}
            </button>
            {value && !value.clear && (
              <button type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={disabled}
                onClick={() => { onChange({ clear: true }); setOpen(false); }}>
                {appText("Убрать место", "Урынды алып ташлау")}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
