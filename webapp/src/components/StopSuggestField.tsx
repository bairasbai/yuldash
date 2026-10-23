import { useEffect, useState } from "react";
import { searchSettlements, type Settlement } from "../api/geo";
import { ApiError } from "../api/client";
import { useLang } from "../i18n/lang";

export const MAX_RIDE_STOPS = 4;
const MAX_STOP_LENGTH = 60;
type StopProblem = "invalid" | "tooMany" | "endpoint" | "duplicate";
export class RideStopError extends Error {
  constructor(readonly problem: StopProblem) { super(problem); }
}

/** Как на сервере: только буквы, пробел, дефис, точка и скобки; никаких телефонов/номеров домов. */
export function rideStopTextAllowed(value: string): boolean {
  const text = value.trim();
  return [...text].length > 0 && [...text].length <= MAX_STOP_LENGTH &&
    /\p{L}/u.test(text) && /^[\p{L} .()\-]+$/u.test(text);
}
const fold = (value: string) => value.trim().toLowerCase().replace(/[ёәөүһҙҫңғҡ]/g,
  (letter) => ({ ё: "е", ә: "а", ө: "о", ү: "у", һ: "х", ҙ: "з", ҫ: "с", ң: "н", ғ: "г", ҡ: "к" } as Record<string, string>)[letter]!);
export const rideStopName = (value: string) => value.split("(")[0].trim()
  .replace(/^(?:(?:д|дер|с|п|пос|пгт|г)\.\s*|(?:деревня|село|посёлок|поселок|пгт|аул)\s+)/i, "").trim();
const key = (value: string) => fold(rideStopName(value));
const districtOf = (value: string) => {
  const text = value.trim();
  const start = text.lastIndexOf("(");
  return start >= 0 && text.endsWith(")") ? text.slice(start + 1, -1).trim() : "";
};

export function rideStopPickText(town: Settlement, ba: boolean): string {
  const name = ba ? town.name_ba || town.name_ru : town.name_ru;
  const district = town.district?.trim();
  return district ? `${name} (${district})` : name;
}
function isEndpoint(town: Settlement, endpoints: string[]): boolean {
  const names = [town.name_ru, town.name_ba].filter((v): v is string => !!v).map(key);
  return endpoints.some((value) => {
    if (!names.includes(key(value))) return false;
    const district = districtOf(value);
    // Без района не считаем одноимённый населённый пункт заведомо другим местом.
    return !district || fold(district) === fold(town.district || "");
  });
}

/** Проверяется и сохранённый черновик; исходные строки при отказе остаются у формы. */
export async function resolveRideStops(values: string[], from: string, to: string, ba: boolean): Promise<string[]> {
  const nonempty = values.map((value) => value.trim()).filter(Boolean);
  if (nonempty.length > MAX_RIDE_STOPS) throw new RideStopError("tooMany");
  if (nonempty.some((value) => !rideStopTextAllowed(value))) throw new RideStopError("invalid");
  const seen = new Set<number>();
  const result: string[] = [];
  for (const raw of nonempty) {
    const district = districtOf(raw);
    const matches = (await searchSettlements(rideStopName(raw), 30)).filter((town) =>
      [town.name_ru, town.name_ba].some((name) => !!name && key(name) === key(raw)) &&
      (!district || fold(town.district || "") === fold(district)));
    const unique = [...new Map(matches.map((town) => [town.id, town])).values()];
    const selected = unique.length === 1 ? unique[0] : null;
    if (!selected) throw new RideStopError("invalid");
    if (isEndpoint(selected, [from, to])) throw new RideStopError("endpoint");
    if (seen.has(selected.id)) throw new RideStopError("duplicate");
    seen.add(selected.id);
    const name = rideStopPickText(selected, ba);
    if (!rideStopTextAllowed(name)) throw new RideStopError("invalid");
    result.push(name);
  }
  return result;
}

export function rideStopErrorMessage(problem: StopProblem, appText: (ru: string, ba: string) => string): string {
  switch (problem) {
    case "invalid": return appText("Выбери село или город из подсказок. Точное место договоритесь в чате.", "Ауыл йәки ҡаланы тәҡдимдәрҙән һайла. Теүәл урынды чатта килешегеҙ.");
    case "tooMany": return appText("Можно добавить до четырёх остановок.", "Дүрткә тиклем туҡталыш өҫтәргә була.");
    case "endpoint": return appText("Начало и конец маршрута уже указаны — выбери остановку по пути.", "Юлдың башы һәм аҙағы күрһәтелгән инде — юл ыңғайындағы туҡталышты һайла.");
    case "duplicate": return appText("Эта остановка уже добавлена.", "Был туҡталыш өҫтәлгән инде.");
  }
}

/** Только /settlements: остановка не превращается в адрес из внешнего геокодера. */
export default function StopSuggestField({ value, onChange, endpoints, describedBy }: {
  value: string; onChange: (value: string) => void; endpoints: string[];
  /** id строки с правилом остановок: диктор зачитает её при переходе в поле. */
  describedBy?: string;
}) {
  const { appText, lang } = useLang();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Settlement[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const failureText = appText("Не получилось загрузить подсказки. Повтори.", "Тәҡдимдәрҙе йөкләп булманы. Ҡабатла.");
  useEffect(() => {
    setItems([]); setError(null); setLoading(false);
    if (!open || !value.trim() || !rideStopTextAllowed(value)) return;
    const ac = new AbortController();
    let active = true;
    setLoading(true);
    const timer = window.setTimeout(() => {
      searchSettlements(rideStopName(value), 10, ac.signal).then((rows) => {
        if (active) setItems(rows.filter((town) => rideStopTextAllowed(rideStopPickText(town, lang === "ba"))));
      }).catch((failure: unknown) => {
        if (active) setError(failure instanceof ApiError && failure.message ? failure.message :
          failureText);
      }).finally(() => { if (active) setLoading(false); });
    }, 250);
    return () => { active = false; window.clearTimeout(timer); ac.abort(); };
  }, [value, open, retry, lang, failureText]);
  const shown = items.filter((town) => !isEndpoint(town, endpoints));
  const invalid = !!value.trim() && !rideStopTextAllowed(value);
  return (
    <div className="city-field" style={{ flex: 1, minWidth: 0 }} onBlur={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false);
    }}>
      <label className="field">
        <span className="field__label">{appText("Остановка по пути", "Юл ыңғайындағы туҡталыш")}</span>
        <input className="field__input" value={value} autoComplete="off" aria-invalid={invalid} aria-describedby={describedBy}
          onChange={(event) => { setOpen(true); onChange(event.target.value); }} onFocus={() => setOpen(true)}
          placeholder={appText("Например, Темясово", "Мәҫәлән, Темәс")} />
      </label>
      {open && value.trim() && (
        <div className="city-field__list" style={{ position: "static", animation: "fadeUp var(--motion-quick) ease both" }} aria-live="polite">
          {invalid ? <p className="sheet__note">{rideStopErrorMessage("invalid", appText)}</p> : loading ?
            <p className="sheet__note" role="status">{appText("Ищем остановки…", "Туҡталыштарҙы эҙләйбеҙ…")}</p> : error ?
              <><p className="sheet__note" role="alert">{error}</p><button type="button" className="btn-soft" style={{ minHeight: 48 }} onClick={() => setRetry((n) => n + 1)}>{appText("Повторить", "Ҡабатлау")}</button></> :
              shown.length === 0 ? <p className="sheet__note">{appText("Не нашли. Попробуй другое название.", "Табылманы. Башҡа атама яҙып ҡара.")}</p> :
                <><ul role="listbox" aria-label={appText("Остановки по пути", "Юл буйындағы туҡталыштар")} style={{ listStyle: "none", margin: 0, padding: 0 }}>
                  {/* listbox владеет только option: <li> между ними — role="presentation", иначе диктор читает «пункт списка» вместо варианта. */}
                  {shown.map((town) => <li key={town.id} role="presentation"><button type="button" className="city-field__item" role="option" aria-selected={false}
                    onClick={() => { onChange(rideStopPickText(town, lang === "ba")); setOpen(false); }}>
                    <span className="city-field__name">{rideStopPickText(town, lang === "ba")}</span>
                    <span className="city-field__sub">{town.region}</span>
                  </button></li>)}
                </ul>{shown.some((town) => town.kind === "village") && <small className="sheet__note">{appText("© OpenStreetMap", "© OpenStreetMap")}</small>}</>}
        </div>
      )}
    </div>
  );
}
