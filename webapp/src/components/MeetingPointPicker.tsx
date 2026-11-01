import { useEffect, useState } from "react";
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
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [points, setPoints] = useState<PickupPoint[]>([]);
  const [showMap, setShowMap] = useState(false);
  const [note, setNote] = useState(value?.note ?? "");

  useEffect(() => {
    if (!open) return;
    let live = true;
    fetchPickupPoints(city).then((rows) => { if (live) setPoints(points => points.length ? points : rows); }).catch(() => {});
    return () => { live = false; };
  }, [open, city]);

  const label = !value || value.clear
    ? appText("По желанию — ориентир или точка на карте", "Телһәләгең буйынса — билгеле урын йәки карта нөктәһе")
    : value.landmark_id
      ? (points.find((p) => p.id === value.landmark_id)?.title_ru ?? appText("Ориентир выбран", "Билгеле урын һайланды"))
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
          {points.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {points.map((p) => (
                <button key={p.id} type="button" className={"chip" + (value?.landmark_id === p.id ? " chip--on" : "")}
                  style={{ minHeight: 48 }} disabled={disabled}
                  onClick={() => onChange({ landmark_id: p.id, note: note.trim() || undefined })}>
                  {p.title_ru}
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
                onCenterChange={(p) => onChange({ lat: p.lat, lng: p.lng, note: note.trim() || undefined })}
              />
              <div className="muted" style={{ fontSize: 13 }}>
                {appText("Двигай карту — точка под булавкой в центре", "Картаны ҡуҙғат — нөктә уртадағы бөгөл аҫтында")}
              </div>
            </>
          )}
          <label className="field">
            <span className="field__label">{appText("Пояснение: ворота, подъезд, что рядом", "Аңлатма: ҡапҡа, инеү урыны, янында нимә")}</span>
            <input className="field__input" value={note} maxLength={120} disabled={disabled}
              onChange={(e) => setNote(e.target.value)}
              onBlur={() => { if (value && !value.clear) onChange({ ...value, note: note.trim() || undefined }); }} />
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
                onClick={() => { onChange({ clear: true }); setNote(""); setOpen(false); }}>
                {appText("Убрать место", "Урынды алып ташлау")}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
