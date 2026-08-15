// ================================================================
//  📍 «Где встречаемся» — ориентиры города вместо тыка в карту.
//  Зеркало Android PickupSuggestionChips.
//
//  В селе адрес «Ленина 12» — пять домов без табличек, зато «у мечети»
//  или «автовокзал» знают все. Справочник двуязычный и растёт сам:
//  сервер считает, какие ориентиры выбирают чаще, и поднимает их вверх.
//
//  Города нет в справочнике или связи нет — блока просто не будет,
//  поле «где встречаемся» остаётся обычным текстом.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchPickupPoints, type PickupPoint } from "../api/geo";
import { IconPin } from "./Icons";

/** Пауза: город печатают буквами, дёргать справочник на каждую незачем. */
const TYPING_PAUSE_MS = 350;

export default function PickupChips({
  city,
  value,
  onPick,
  onPickPoint,
}: {
  city: string;
  value: string;
  onPick: (title: string) => void;
  /** Выбрали ориентир из справочника — отдаём его id: с ним придут и координаты. */
  onPickPoint?: (id: number | null) => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [points, setPoints] = useState<PickupPoint[]>([]);

  useEffect(() => {
    const c = city.trim();
    if (c.length < 2) {
      setPoints([]);
      return;
    }
    const ac = new AbortController();
    const timer = window.setTimeout(() => {
      fetchPickupPoints(c, 8, ac.signal)
        .then((list) => setPoints(Array.isArray(list) ? list : []))
        .catch(() => setPoints([])); // нет справочника для города → блок скрыт
    }, TYPING_PAUSE_MS);
    return () => {
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [city]);

  return (
    <label className="field">
      <span className="field__label">{appText("Где встречаемся", "Ҡайҙа осрашабыҙ")}</span>
      <input
        className="field__input"
        value={value}
        onChange={(e) => {
          onPick(e.target.value);
          onPickPoint?.(null); // правят руками — это уже не справочный ориентир
        }}
        placeholder={appText("Например, у автовокзала", "Мәҫәлән, автовокзал янында")}
        autoComplete="off"
      />
      {points.length > 0 && (
        <div className="chips" style={{ marginTop: 8 }}>
          {points.map((p) => {
            const title = ru ? p.title_ru : p.title_ba || p.title_ru;
            return (
              <button
                key={p.id}
                type="button"
                className={"chip" + (value === title ? " chip--on" : "")}
                onClick={() => {
                  onPick(title);
                  onPickPoint?.(p.id);
                }}
              >
                <IconPin size={14} /> {title}
              </button>
            );
          })}
        </div>
      )}
    </label>
  );
}
