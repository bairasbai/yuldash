// ================================================================
//  «Мои адреса» — сохранённые места (Дом / Работа / свои).
//  GET/POST /places/saved, DELETE /places/saved/{id}. RequireAuth.
//  Адрес вводится вручную; если геокодер доступен — подсказываем
//  координаты (мягкая деградация: 404/ошибка → просто ручной ввод).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  deleteSavedPlace,
  fetchSavedPlaces,
  upsertSavedPlace,
  type SavedPlace,
  type SavedPlaceKind,
} from "../api/places";
import { geocode, type GeoHit } from "../api/discovery";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconHome, IconWork, IconPin, IconTrash, IconCheck } from "../components/Icons";

type Status = "loading" | "error" | "ready";

function kindIcon(kind: string) {
  if (kind === "home") return <IconHome size={22} />;
  if (kind === "work") return <IconWork size={22} />;
  return <IconPin size={22} />;
}

export default function SavedPlacesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [places, setPlaces] = useState<SavedPlace[]>([]);

  // Форма добавления
  const [kind, setKind] = useState<SavedPlaceKind>("custom");
  const [label, setLabel] = useState("");
  const [address, setAddress] = useState("");
  const [coords, setCoords] = useState<{ lat: number; lng: number } | null>(null);
  const [hits, setHits] = useState<GeoHit[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchSavedPlaces(signal)
      .then((rows) => {
        setPlaces(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Эндпоинта может ещё не быть на проде — покажем пустой список, а не краш.
        if (e instanceof ApiError && e.status === 404) {
          setPlaces([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Подсказки адреса (мягко: нет геокодера — просто нет подсказок).
  useEffect(() => {
    const q = address.trim();
    if (q.length < 2) {
      setHits([]);
      return;
    }
    const ac = new AbortController();
    const t = setTimeout(() => {
      geocode(q, ac.signal)
        .then((r) => setHits(r.items.slice(0, 4)))
        .catch(() => setHits([]));
    }, 350);
    return () => {
      clearTimeout(t);
      ac.abort();
    };
  }, [address]);

  const kinds: { key: SavedPlaceKind; label: string }[] = [
    { key: "home", label: appText("Дом", "Өй") },
    { key: "work", label: appText("Работа", "Эш") },
    { key: "custom", label: appText("Другое", "Башҡа") },
  ];

  async function add() {
    if (!address.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await upsertSavedPlace({
        kind,
        label: label.trim(),
        address: address.trim(),
        lat: coords?.lat ?? null,
        lng: coords?.lng ?? null,
      });
      setLabel("");
      setAddress("");
      setCoords(null);
      setHits([]);
      setKind("custom");
      load();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Попробуй снова.", "Һаҡлап булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    // Оптимистично убираем из списка; при ошибке перезагрузим.
    setPlaces((p) => p.filter((x) => x.id !== id));
    try {
      await deleteSavedPlace(id);
    } catch {
      load();
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Мои адреса", "Адрестарым")}
        subtitle={appText("Дом, работа и любимые места", "Өй, эш һәм яҡын нөктәләр")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && (
        <>
          {places.length === 0 ? (
            <div className="state" style={{ paddingBottom: 24 }}>
              <div className="state__icon"><IconPin size={34} /></div>
              <h2>{appText("Пока нет адресов", "Әле адрестар юҡ")}</h2>
              <p>
                {appText(
                  "Сохрани дом и работу — заказывать поездку станет на пару касаний быстрее.",
                  "Өй менән эште һаҡла — сәфәр ҡалдырыу бер-ике баҫыуға тиҙерәк булыр."
                )}
              </p>
            </div>
          ) : (
            <div className="list">
              {places.map((p) => (
                <div key={p.id} className="list-row">
                  <span className="list-row__icon">{kindIcon(p.kind)}</span>
                  <div className="list-row__main">
                    <div className="list-row__title">
                      {p.label ||
                        (p.kind === "home"
                          ? appText("Дом", "Өй")
                          : p.kind === "work"
                          ? appText("Работа", "Эш")
                          : appText("Место", "Урын"))}
                    </div>
                    <div className="list-row__sub">{p.address}</div>
                  </div>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={() => remove(p.id)}
                    aria-label={appText("Удалить", "Юйырға")}
                  >
                    <IconTrash size={20} />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Форма добавления */}
          <h2 className="section-title">{appText("Добавить адрес", "Адрес өҫтәргә")}</h2>
          <div className="form">
            <div className="seg">
              {kinds.map((k) => (
                <button
                  key={k.key}
                  type="button"
                  className={"seg__item" + (kind === k.key ? " is-active" : "")}
                  onClick={() => setKind(k.key)}
                >
                  <span>{kindIcon(k.key)}</span>
                  {k.label}
                </button>
              ))}
            </div>

            <label className="field">
              <span className="field__label">{appText("Название (необязательно)", "Исем (мотлаҡ түгел)")}</span>
              <input
                className="field__input"
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                placeholder={appText("Например: У мамы", "Мәҫәлән: Әсәйҙә")}
                autoComplete="off"
              />
            </label>

            <label className="field">
              <span className="field__label">{appText("Адрес", "Адрес")}</span>
              <input
                className="field__input"
                value={address}
                onChange={(e) => {
                  setAddress(e.target.value);
                  setCoords(null);
                }}
                placeholder={appText("Город, улица, дом", "Ҡала, урам, йорт")}
                autoComplete="off"
              />
              {coords && (
                <span className="field__hint">
                  <IconCheck size={13} />{" "}
                  {appText("Точка на карте выбрана", "Картала нөктә һайланды")}
                </span>
              )}
            </label>

            {hits.length > 0 && !coords && (
              <div className="geo-hits">
                {hits.map((h, i) => (
                  <button
                    key={i}
                    type="button"
                    className="geo-hit"
                    onClick={() => {
                      setAddress(h.title);
                      setCoords({ lat: h.lat, lng: h.lon });
                      setHits([]);
                    }}
                  >
                    <IconPin size={18} />
                    <span>{h.title}</span>
                  </button>
                ))}
              </div>
            )}

            {error && <div className="auth__error">{error}</div>}

            <button
              type="button"
              className="btn-primary submit-btn"
              onClick={add}
              disabled={!address.trim() || busy}
            >
              {busy ? (
                appText("Сохраняем…", "Һаҡлайбыҙ…")
              ) : (
                <>
                  <IconCheck size={18} /> {appText("Сохранить адрес", "Адресты һаҡларға")}
                </>
              )}
            </button>
          </div>
        </>
      )}
    </>
  );
}
