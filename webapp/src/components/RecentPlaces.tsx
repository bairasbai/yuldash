// ================================================================
//  «Недавние адреса» — список, который копится сам.
//  Зеркало Android SavedPlacesScreen.kt + places.py:
//  GET/POST /places/recent, DELETE /places/recent/{id}, DELETE /places/recent.
//
//  Почему это не украшение. Список складывается из каждого заказа,
//  и человек его не выбирал: там оседают адрес больницы, дом бывшего,
//  работа, с которой ушёл. Право убрать оттуда строку — обязательная
//  часть, а не удобство. Кнопка «очистить всё» нужна отдельно: когда
//  телефон отдают в чужие руки, чистить по одной — десять жестов
//  вместо одного.
//
//  В вебе не было ни списка, ни удаления: адреса копились на сервере
//  и удалить их из браузера было нельзя (сверка с Android, 2026-08-30).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import {
  clearRecentPlaces,
  deleteRecentPlace,
  fetchRecentPlaces,
  type RecentPlace,
} from "../api/places";
import { IconPin, IconTrash } from "./Icons";

export default function RecentPlaces() {
  const { appText } = useLang();
  const [rows, setRows] = useState<RecentPlace[]>([]);
  const [busy, setBusy] = useState(false);
  const [ask, setAsk] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    fetchRecentPlaces(signal)
      .then(setRows)
      .catch(() => {
        /* нет ручки / нет сети — блока просто не будет */
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  if (rows.length === 0) return null;

  return (
    <>
      <h2 className="section-title">{appText("Недавние адреса", "Һуңғы адрестар")}</h2>
      <p className="taxi-note" style={{ marginTop: 0 }}>
        {appText(
          "Складываются сами из твоих заказов. Любой можно убрать — это твоя история, а не наша.",
          "Заказдарыңдан үҙҙәре йыйыла. Теләһә ҡайһыһын алып ташларға була — был һинең тарихың, беҙҙеке түгел."
        )}
      </p>

      <div className="list">
        {rows.map((r) => (
          <div key={r.id} className="list-row">
            <span className="list-row__icon">
              <IconPin size={18} />
            </span>
            <div className="list-row__main">
              <div className="list-row__title">{r.address}</div>
            </div>
            <button
              type="button"
              className="icon-btn"
              aria-label={appText("Убрать адрес", "Адресты алып ташлау")}
              disabled={busy}
              onClick={() => {
                setBusy(true);
                deleteRecentPlace(r.id)
                  .then(() => setRows((prev) => prev.filter((x) => x.id !== r.id)))
                  .catch(() => {})
                  .finally(() => setBusy(false));
              }}
            >
              <IconTrash size={18} />
            </button>
          </div>
        ))}
      </div>

      {!ask ? (
        <button type="button" className="link-btn link-btn--quiet" onClick={() => setAsk(true)}>
          {appText("Очистить все недавние", "Барлыҡ һуңғыларҙы таҙартыу")}
        </button>
      ) : (
        <div className="act-card act-card--warn">
          <div className="act-card__title">
            {appText("Очистить историю адресов?", "Адрестар тарихын таҙартырғамы?")}
          </div>
          <p className="act-card__text">
            {appText(
              "Уберём все недавние точки. Сохранённые адреса (Дом, Работа) останутся.",
              "Барлыҡ һуңғы нөктәләрҙе алып ташлайбыҙ. Һаҡланған адрестар (Өй, Эш) ҡала."
            )}
          </p>
          <div className="act-card__actions">
            <button
              type="button"
              className="btn-danger"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                clearRecentPlaces()
                  .then(() => setRows([]))
                  .catch(() => {})
                  .finally(() => {
                    setBusy(false);
                    setAsk(false);
                  });
              }}
            >
              {appText("Очистить", "Таҙартыу")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setAsk(false)} disabled={busy}>
              {appText("Оставить", "Ҡалдырыу")}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
