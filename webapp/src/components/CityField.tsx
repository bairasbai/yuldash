// ================================================================
//  🏘 Поле города с подсказками из справочника (~6600 сёл РБ).
//  Зеркало Android-подсказок «откуда/куда».
//
//  Зачем не просто текстовое поле: «Темясово» человек напишет пятью
//  способами, и заявка не совпадёт ни с одной поездкой. Подсказка
//  даёт одно и то же написание всем — и добавляет координаты, без
//  которых не считается ни крюк, ни погода на маршруте.
//
//  Поиск ищет и по русскому, и по башкирскому написанию: человек
//  печатает так, как говорит.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { searchSettlements, type Settlement } from "../api/geo";

/** Пауза перед запросом: город печатают буквами, а не целиком. */
const TYPING_PAUSE_MS = 250;

export default function CityField({
  label,
  value,
  onChange,
  onPick,
  placeholder,
  required,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  /** Выбрали из справочника — отдаём и координаты: они нужны погоде и крюку. */
  onPick?: (s: Settlement) => void;
  placeholder?: string;
  required?: boolean;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  const [items, setItems] = useState<Settlement[]>([]);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement | null>(null);
  // Гасим подсказки сразу после выбора: иначе они мигают поверх уже выбранного.
  const justPicked = useRef(false);

  useEffect(() => {
    if (justPicked.current) {
      justPicked.current = false;
      return;
    }
    const q = value.trim();
    if (q.length < 2) {
      setItems([]);
      return;
    }
    const ac = new AbortController();
    const timer = window.setTimeout(() => {
      searchSettlements(q, 8, ac.signal)
        .then((list) => {
          setItems(list);
          setOpen(list.length > 0);
        })
        .catch(() => setItems([])); // нет сети / нет ручки → обычное поле ввода
    }, TYPING_PAUSE_MS);
    return () => {
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [value]);

  // Клик мимо — закрыть список.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  function pick(s: Settlement) {
    justPicked.current = true;
    onChange(ru ? s.name_ru : s.name_ba || s.name_ru);
    onPick?.(s);
    setOpen(false);
  }

  return (
    <div className="city-field" ref={box}>
      <label className="field">
        <span className="field__label">{label}</span>
        <input
          className="field__input"
          value={value}
          required={required}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => items.length > 0 && setOpen(true)}
          placeholder={placeholder ?? appText("Начни вводить название", "Атамаһын яҙа башла")}
          autoComplete="off"
        />
      </label>

      {open && items.length > 0 && (
        <ul className="city-field__list" role="listbox">
          {items.map((s) => {
            const name = ru ? s.name_ru : s.name_ba || s.name_ru;
            const alt = ru ? s.name_ba : s.name_ru;
            return (
              <li key={s.id}>
                <button type="button" className="city-field__item" onClick={() => pick(s)}>
                  <span className="city-field__name">{name}</span>
                  <span className="city-field__sub">
                    {alt && alt !== name ? `${alt} · ` : ""}
                    {s.district || s.region}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
