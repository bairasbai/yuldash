import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { chosenNames } from "../nearbyFeed";
import type { NearbyCityFilter } from "../nearbyCityFilter";
import type { SearchEntry, SearchRoute } from "../utils/searchHistory";
import { clearSearchHistory, readSearchHistory, removeSearch, repeatSearch, searchRouteKey } from "../utils/searchHistory";
import { getSessionGeneration } from "../api/client";
import { SEARCH_HISTORY_KEY } from "../utils/privacy";
import { isSessionStorageSignal, subscribeSessionChanges } from "../utils/sessionCredentials";
import { storageFenced, subscribeStorageFailure } from "../utils/storageHealth";
import { IconTrash } from "./Icons";

export default function SearchHistory({
  generation, filter, onRepeat,
}: {
  generation: string;
  filter: NearbyCityFilter;
  onRepeat: (route: SearchRoute) => void;
}) {
  const { appText, lang } = useLang();
  const lifetime = useRef(false);
  const region = useRef<HTMLElement>(null);
  const [entries, setEntries] = useState<SearchEntry[]>([]);
  const [removing, setRemoving] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const current = generation === getSessionGeneration() && !storageFenced();
  useEffect(() => {
    lifetime.current = true;
    let active = true;
    const invalidate = () => {
      if (!active) return;
      setEntries([]);
      setConfirm(false);
      setVersion(value => value + 1);
    };
    const onStorage = (event: StorageEvent) => {
      if (event.key === SEARCH_HISTORY_KEY || isSessionStorageSignal(event.key)) invalidate();
    };
    const stopSession = subscribeSessionChanges(invalidate);
    const stopStorageFailure = subscribeStorageFailure(invalidate);
    window.addEventListener("storage", onStorage);
    window.addEventListener("pageshow", invalidate);
    return () => {
      active = false;
      lifetime.current = false;
      stopSession();
      stopStorageFailure();
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("pageshow", invalidate);
    };
  }, []);
  useEffect(() => {
    let active = true;
    void readSearchHistory(generation).then(rows => {
      if (active && generation === getSessionGeneration() && !storageFenced()) setEntries(rows ?? []);
    });
    return () => { active = false; };
  }, [generation, version]);

  async function act(operation: () => Promise<unknown>) {
    if (busy || !current || !lifetime.current) return;
    setBusy(true);
    try {
      await operation();
      if (lifetime.current) setVersion(value => value + 1);
    } finally { if (lifetime.current) setBusy(false); }
  }

  function canRepeat() {
    return lifetime.current && !!region.current && !region.current.closest("[inert]") &&
      generation === getSessionGeneration() && !storageFenced();
  }

  async function remove(key: string) {
    setRemoving(key);
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    const row = region.current?.querySelector(".search-history__row.is-removing");
    if (row) await Promise.allSettled(row.getAnimations().map(animation => animation.finished));
    const rows = await removeSearch(generation, key);
    if (lifetime.current) {
      if (rows) setEntries(rows);
      setRemoving(null);
      region.current?.closest<HTMLElement>(".nbs")?.focus();
    }
  }

  function label(route: SearchRoute) {
    const translations = Object.entries(route.labels ?? {}).map(([ru, ba]) => ({ ru, ba }));
    const names = (cities: readonly string[], options: NearbyCityFilter["fromOptions"]) =>
      chosenNames(cities, options, [...filter.found, ...translations], lang !== "ba").join(", ");
    const from = names(route.from, filter.fromOptions);
    const to = names(route.to, filter.toOptions);
    return `${from || appText("Все города", "Бөтә ҡалалар")} → ${to || appText("Любое направление", "Теләһә ҡайҙа")}`;
  }

  if (!current || !entries.length) return null;
  const title = appText("Недавно искали", "Күптән түгел эҙләнеләр");
  return (
    <section ref={region} className="search-history nbs__body" aria-label={title}>
      <h3 className="search-history__title">{title}</h3>
      {entries.map(entry => {
        const key = searchRouteKey(entry);
        const route = label(entry);
        return (
          <div className={"search-history__row" + (removing === key ? " is-removing" : "")} key={key}>
            <button type="button" className="nbs-row" disabled={busy || confirm}
              aria-label={appText(`Повторить поиск: ${route}`, `Эҙләүҙе ҡабатларға: ${route}`)}
              onClick={() => void act(async () => {
                const match = await repeatSearch(generation, key, canRepeat);
                if (match && canRepeat()) onRepeat(match);
              })}>
              <span className="nbs-row__label" title={route}>{route}</span>
            </button>
            <button type="button" className="search-history__remove" disabled={busy || confirm}
              aria-label={appText(`Удалить из истории: ${route}`, `Тарихтан юйырға: ${route}`)}
              onClick={() => void act(() => remove(key))}>
              <IconTrash aria-hidden />
            </button>
          </div>
        );
      })}
      {confirm ? (
        <div className="nbs__body" role="group" aria-label={appText("Очистить историю поиска?", "Эҙләү тарихын таҙартырғамы?")}>
          <p className="nbs-hint" role="status">
            {appText("Очистить историю поиска? Сохранённые адреса и подписки останутся.",
              "Эҙләү тарихын таҙартырғамы? Һаҡланған адрестар һәм яҙылыуҙар ҡаласаҡ.")}
          </p>
          <div className="search-history__actions">
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => {
              setConfirm(false);
              region.current?.closest<HTMLElement>(".nbs")?.focus();
            }} autoFocus>
              {appText("Оставить", "Ҡалдырыу")}
            </button>
            <button type="button" className="btn-ghost" disabled={busy}
              onClick={() => void act(async () => {
                const rows = await clearSearchHistory(generation);
                if (lifetime.current) {
                  if (rows) setEntries(rows);
                  setConfirm(false);
                  region.current?.closest<HTMLElement>(".nbs")?.focus();
                }
                return rows;
              })}>
              {appText("Очистить", "Таҙартыу")}
            </button>
          </div>
        </div>
      ) : (
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => setConfirm(true)}>
          {appText("Очистить историю", "Тарихты таҙартыу")}
        </button>
      )}
    </section>
  );
}
