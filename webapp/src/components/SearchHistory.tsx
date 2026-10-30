import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import type { RideCity } from "../api/discovery";
import type { SearchHistoryEntry, SearchRoute } from "../utils/searchHistory";
import { searchHistoryKey } from "../utils/searchHistory";
import AlertDialog from "./AlertDialog";
import { IconTrash } from "./Icons";

interface Props {
  entries: readonly SearchHistoryEntry[];
  cities: readonly RideCity[];
  /** Неперсональный маркер входа владельца записей и текущего входа (не токены). */
  ownerSession: string;
  activeSession: string;
  onRepeat: (route: SearchRoute) => void;
  onRemove: (key: string) => void;
  onClear: () => void;
}

/**
 * Независимый блок для верха шторки городов. Данные и действия принадлежат хозяину
 * экрана: здесь нет localStorage, HTTP, даты поиска или фильтров мест/условий.
 * Подключать только вместе с очисткой личного ключа (очередь RS-07А/RS-10Б).
 * BA — черновик; перенести пары в «Переводы на проверку», когда docs/tasks свободен.
 */
export default function SearchHistory({
  entries, cities, ownerSession, activeSession, onRepeat, onRemove, onClear,
}: Props) {
  const { appText } = useLang();
  const [ask, setAsk] = useState(false);
  const alive = useRef(true);
  const latest = useRef({ ownerSession, activeSession });
  latest.current = { ownerSession, activeSession };
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);
  useEffect(() => setAsk(false), [ownerSession, activeSession, entries.length]);

  // Старое подтверждение/нажатие после выхода не действует от имени нового входа.
  const canAct = () => alive.current && !!ownerSession && ownerSession === activeSession
    && latest.current.ownerSession === ownerSession && latest.current.activeSession === activeSession;
  if (!canAct() || !entries.length) return null;

  const title = appText("Недавно искали", "Яҡында эҙләнеләр");
  const fold = (name: string) => name.toLowerCase().replace(/ё/g, "е");
  const cityLabel = (name: string) => {
    const city = cities.find((item) => fold(item.ru) === fold(name));
    return city ? appText(city.ru, city.ba || city.ru) : name;
  };

  return (
    <section aria-label={title}>
      <h3 className="section-title">{title}</h3>
      <div className="list" role="list">
        {entries.map((entry) => {
          const key = searchHistoryKey(entry)!;
          const from = entry.from.map(cityLabel).join(", ") || appText("Все города", "Бөтә ҡалалар");
          const to = entry.to.map(cityLabel).join(", ") || appText("Любое направление", "Теләһә ҡайҙа");
          const label = `${from} → ${to}`;
          return (
            <div key={key} className="list-row" role="listitem">
              <button
                type="button"
                className="btn-ghost list-row__main"
                style={{ textAlign: "left", padding: 0 }}
                aria-label={appText(`Повторить поиск: ${label}`, `Эҙләүҙе ҡабатлау: ${label}`)}
                onClick={() => {
                  if (canAct()) onRepeat({ from: [...entry.from], to: [...entry.to] });
                }}
              >
                <span className="list-row__title" style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {label}
                </span>
              </button>
              <button
                type="button"
                className="btn-ghost"
                style={{ flex: "none", minWidth: 48, color: "var(--danger)" }}
                aria-label={appText(`Удалить из истории: ${label}`, `Тарихтан юйыу: ${label}`)}
                onClick={() => { if (canAct()) onRemove(key); }}
              >
                <IconTrash size={20} />
              </button>
            </div>
          );
        })}
      </div>
      <button type="button" className="btn-ghost" onClick={() => { if (canAct()) setAsk(true); }}>
        {appText("Очистить историю", "Тарихты таҙартыу")}
      </button>
      {ask && (
        <AlertDialog
          title={appText("Очистить историю поиска?", "Эҙләү тарихын таҙартырғамы?")}
          text={appText("Сохранённые адреса и подписки останутся", "Һаҡланған адрестар һәм яҙылыуҙар ҡала")}
          confirm={{
            label: appText("Очистить", "Таҙартыу"), tone: "danger",
            onClick: () => { if (canAct()) { onClear(); setAsk(false); } },
          }}
          dismiss={{ label: appText("Оставить", "Ҡалдырыу"), onClick: () => setAsk(false) }}
          onClose={() => setAsk(false)}
        />
      )}
    </section>
  );
}
