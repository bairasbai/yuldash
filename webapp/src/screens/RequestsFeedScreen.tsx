// ================================================================
//  Лента заявок пассажиров для водителя (GET /requests/feed).
//  Отклик на заявку (POST /requests/{id}/respond) — цена + комментарий.
//  Водительская зона, но по контракту requests.py — доступна вошедшим.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  fetchRequestsFeed,
  respondToRequest,
  type RequestFeedItem,
} from "../api/requests";
import { LoadingList, ErrorState } from "../components/States";
import { useSheetExit } from "../components/useSheetExit";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconRequest, IconCheck } from "../components/Icons";
import { formatWhen, pluralRu } from "../utils/format";
import { useScrollMemory } from "../utils/useScrollMemory";
import { track } from "../analytics";

const PREF_LABEL: Record<string, [string, string]> = {
  women: ["Только женщины", "Тик ҡатын-ҡыҙ"],
  child: ["Детское кресло", "Бала урыны"],
  pets: ["С питомцем", "Хайуан менән"],
  wheelchair: ["Коляска", "Коляска"],
  baggage: ["Багаж", "Багаж"],
  nosmoke: ["Не курить", "Тартмаҫҡа"],
  ac: ["Кондиционер", "Кондиционер"],
};

/** По сколько заявок добавляем за раз («Показать ещё»). */
const PAGE = 30;

export default function RequestsFeedScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");

  // Позиция в ленте заявок переживает переход к заявке и обратно.
  useScrollMemory("requests", status === "ready");
  const [items, setItems] = useState<RequestFeedItem[]>([]);
  /** Сколько заявок показано сейчас — растёт по кнопке «Показать ещё». */
  const [shown, setShown] = useState(PAGE);
  const [respondTo, setRespondTo] = useState<RequestFeedItem | null>(null);
  const [onRoute, setOnRoute] = useState(false);
  const generation = getSessionGeneration();
  const loading = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  const load = useCallback(() => {
    loading.current?.abort();
    const ac = new AbortController(); loading.current = ac;
    const signal = ac.signal;
    setStatus("loading");
    setItems([]); setShown(PAGE); setRespondTo(null);
    fetchRequestsFeed(signal, onRoute)
      .then((list) => {
        if (!mounted.current || signal.aborted || generation !== getSessionGeneration()) return;
        setItems(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (!mounted.current || signal.aborted || generation !== getSessionGeneration() || e?.name === "AbortError") return;
        setStatus("error");
      });
  }, [onRoute, generation]);

  useEffect(() => {
    mounted.current = true;
    load();
    return () => { mounted.current = false; loading.current?.abort(); };
  }, [load]);

  return (
    <>
      <SubHeader
        title={appText("Заявки пассажиров", "Пассажир заявкалары")}
        subtitle={appText("Отзовись — предложи свою цену", "Яуап бир — хаҡыңды тәҡдим ит")}
        onBack={() => navigate(-1)}
      />

      <label className="list-row list-row--check" style={{ minHeight: 48 }}>
        <span className="list-row__main" style={{ display: "grid", gap: "var(--space-xs)", minWidth: 0, overflowWrap: "anywhere" }}>
          <span className="list-row__title">{appText("По моим маршрутам", "Минең юлдарым буйынса")}</span>
          <span className="list-row__sub">{appText("Посадка и высадка идут по порядку остановок твоего рейса.", "Ултырыу һәм төшөү һинең сәфәр туҡталыштары тәртибендә.")}</span>
        </span>
        <input type="checkbox" checked={onRoute} onChange={e => setOnRoute(e.target.checked)} />
      </label>

      {status === "loading" && <LoadingList count={4} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "ready" &&
        (items.length === 0 ? (
          <div className="state">
            <div className="state__icon"><IconRequest size={34} /></div>
            <h2>{onRoute ? appText("По твоим маршрутам пока нет заявок", "Һинең юлдарың буйынса әле заявкалар юҡ") : appText("Пока нет заявок", "Әле заявкалар юҡ")}</h2>
            <p>{onRoute ? appText("Проверь опубликованные рейсы или посмотри все заявки.", "Баҫылған сәфәрҙәреңде тикшер йәки бөтә заявкаларҙы ҡара.") : appText("Как только кто-то будет искать попутку — покажем здесь.", "Кемдер юлдаш эҙләһә — бында күрһәтәбеҙ.")}</p>
            {onRoute && <button type="button" className="btn-soft" onClick={() => setOnRoute(false)}>{appText("Все заявки", "Бөтә заявкалар")}</button>}
          </div>
        ) : (
          <div>
            {/* Сервер отдаёт до 200 заявок. Рисуем частями — иначе дешёвый телефон
                строит две сотни карточек сразу и подвисает. */}
            {items.slice(0, shown).map((it, i) => (
              <article
                key={it.id}
                className="ride-card"
                style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}
              >
                <div className="ride-card__route">
                  <span>{it.from_city}</span>
                  <span className="ride-card__arrow">
                    <IconArrow size={20} />
                  </span>
                  <span>{it.to_city}</span>
                </div>
                <div className="ride-card__meta">
                  <span>{formatWhen(it.desired_at, ru)}</span>
                  <span>
                    {/* «1 место», «2 места», «5 мест»; по-башкирски существительное после числа не меняется. */}
                    <b>{it.seats}</b> {appText(pluralRu(it.seats, "место", "места", "мест"), "урын")}
                  </span>
                  {/* Насколько заявка уводит с твоего маршрута. Без этого водитель
                      читал каждую заявку руками — и уставал от тех, что в другую сторону. */}
                  {/* «По пути» — зелёным, крюк — спокойно серым, без осуждения. */}
                  {it.matched_ride_id && <span className="badge badge--mint">{appText("По пути", "Юл ыңғайында")}</span>}
                  {it.detour_km != null && (
                    <span
                      className="badge badge--muted"
                    >
                      {appText(`Крюк ≈ ${it.detour_km} км`, `Урау ≈ ${it.detour_km} км`)}
                    </span>
                  )}
                  {it.prefs.map((p) =>
                    PREF_LABEL[p] ? (
                      <span key={p} className="badge badge--mint">
                        {appText(PREF_LABEL[p][0], PREF_LABEL[p][1])}
                      </span>
                    ) : null
                  )}
                </div>
                {!!it.matched_ride_id && <p className="sheet__note">{appText("Твой рейс", "Һинең сәфәрең")}: {it.matched_ride_from_city} → {it.matched_ride_to_city}
                  {it.matched_ride_depart_at ? ` · ${formatWhen(it.matched_ride_depart_at, ru)}` : ""}
                  <br />{it.matched_pickup_stop} → {it.matched_dropoff_stop}</p>}
                {it.comment && (
                  <p className="sheet__comment" style={{ marginTop: 10 }}>
                    {it.comment}
                  </p>
                )}
                <div className="ride-card__foot">
                  <div className="ride-card__driver">
                    <span className="ride-card__avatar" aria-hidden>
                      {(it.passenger_name || "?").trim().charAt(0).toUpperCase()}
                    </span>
                    <div className="ride-card__driver-name">{it.passenger_name}</div>
                  </div>
                  {it.responded ? (
                    <span className="badge badge--mint">
                      <IconCheck size={14} /> {appText("Ты откликнулся", "Яуап бирҙең")}
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="btn-soft"
                      style={{ flex: "none", padding: "0 18px" }}
                      onClick={() => setRespondTo(it)}
                    >
                      {appText("Откликнуться", "Яуап бирергә")}
                    </button>
                  )}
                </div>
              </article>
            ))}

            {items.length > shown && (
              <button
                type="button"
                className="btn-soft show-more"
                onClick={() => setShown((n) => n + PAGE)}
              >
                {appText(
                  `Показать ещё · осталось ${items.length - shown}`,
                  `Тағы күрһәтергә · ${items.length - shown} ҡалды`
                )}
              </button>
            )}
          </div>
        ))}

      {respondTo && (
        <RespondSheet
          key={`${generation}:${respondTo.id}`}
          item={respondTo}
          onClose={() => setRespondTo(null)}
          onRefresh={load}
          onDone={(id) => {
            // Шторку не убираем: она уходит сама и по концу движения зовёт onClose.
            setItems((prev) =>
              prev.map((x) =>
                x.id === respondTo.id ? { ...x, responded: true, my_response_id: id } : x
              )
            );
          }}
        />
      )}
    </>
  );
}

/**
 * Нижняя шторка отклика. «Отправить отклик» (при успехе), тап по затемнению уводят её вниз
 * (useSheetExit); `onClose` убирает шторку со страницы, когда она ушла. Во время ухода отправка
 * заперта: отклик из уходящей шторки не уходит.
 */
export function RespondSheet({
  item,
  onClose,
  onDone,
  onRefresh,
}: {
  item: RequestFeedItem;
  /** Шторка уже уехала — убери её со страницы. */
  onClose: () => void;
  /** Сервер принял отклик — обнови ленту (шторка уходит сама). */
  onDone: (responseId: number) => void;
  onRefresh?: () => void;
}) {
  const { appText, lang } = useLang();
  const generation = getSessionGeneration();
  const [price, setPrice] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tripChoice, setTripChoice] = useState<"ride" | "separate" | null>(null);
  const [staleRide, setStaleRide] = useState(false);
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const requestAbort = useRef<AbortController | null>(null);
  const identity = useRef(`${generation}:${item.id}`);
  const sheetRef = useRef<HTMLDivElement>(null);
  const exit = useSheetExit(onClose, sheetRef);
  const matched = !!item.matched_ride_id && !!item.matched_ride_from_city && !!item.matched_ride_to_city &&
    !!item.matched_ride_depart_at && !!item.matched_pickup_stop && !!item.matched_dropoff_stop;
  useEffect(() => {
    mounted.current = true;
    const key = `${generation}:${item.id}`;
    if (identity.current !== key) {
      inFlight.current = false;
      identity.current = key; setTripChoice(null); setStaleRide(false); setPrice(""); setComment(""); setError(null); setBusy(false);
    }
    return () => { mounted.current = false; requestAbort.current?.abort(); };
  }, [generation, item.id]);

  async function submit() {
    if (exit.leaving || inFlight.current || !mounted.current || generation !== getSessionGeneration() || identity.current !== `${generation}:${item.id}`) return;
    if (matched && (tripChoice === null || (tripChoice === "ride" && staleRide))) {
      setError(appText("Выбери свой рейс или отдельную поездку.", "Үҙ сәфәреңде йәки айырым сәфәрҙе һайла.")); return;
    }
    inFlight.current = true;
    const ac = new AbortController(); requestAbort.current = ac;
    setBusy(true);
    setError(null);
    try {
      const res = await respondToRequest(
        item.id,
        price ? Math.max(0, parseInt(price, 10) || 0) : 0,
        comment.trim(),
        matched && tripChoice === "ride" ? item.matched_ride_id! : undefined,
        ac.signal
      );
      if (!mounted.current || ac.signal.aborted || generation !== getSessionGeneration()) return;
      track("respond_request");
      onDone(res.id);
      inFlight.current = false;
      exit.close(); // кнопка остаётся «Отправляем…» (busy не сбрасываем), пока шторка уходит
    } catch (e) {
      if (!mounted.current || ac.signal.aborted || generation !== getSessionGeneration()) return;
      if (e instanceof ApiError && e.status === 409 && tripChoice === "ride") { setStaleRide(true); setTripChoice(null); }
      setError(
        e instanceof ApiError && e.status === 409 && tripChoice === "ride"
          ? appText("Рейс изменился или больше не подходит. Обнови заявки и выбери снова.", "Сәфәр үҙгәргән йәки бүтән тап килмәй. Заявкаларҙы яңыртып ҡабат һайла.")
          : e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Попробуй снова.", "Булманы. Ҡабат ҡара.") // DRAFT
      );
      setBusy(false);
    } finally {
      if (requestAbort.current === ac) inFlight.current = false;
    }
  }

  return exit.portal(
    <div className={exit.backdropClass} onClick={exit.close} role="presentation">
      <div
        ref={sheetRef}
        className="sheet"
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={exit.onAnimationEnd}
        {...exit.dialogProps}
        aria-labelledby={exit.titleId}
      >
        <div className="sheet__grip" aria-hidden />
        <h2 id={exit.titleId} style={{ margin: "0 0 4px", fontSize: "var(--font-heading)", fontWeight: "var(--weight-bold)" }}>
          {appText("Твой отклик", "Яуабың")}
        </h2>
        <p className="sheet__note" style={{ marginTop: 0 }}>
          {item.from_city} → {item.to_city}
        </p>
        {matched && <fieldset disabled={busy} style={{ border: 0, padding: 0, margin: "var(--space-md) 0" }}>
          <legend className="list-row__title">{appText("Как повезёшь пассажира?", "Юлсыны нисек алып бараһың?")}</legend>
          <label className="list-row list-row--check" style={{ minHeight: 48 }}>
            <span className="list-row__main" style={{ display: "grid", gap: "var(--space-xs)", minWidth: 0, overflowWrap: "anywhere" }}>
              <span className="list-row__title">{appText("Мой рейс", "Минең сәфәрем")}: {item.matched_ride_from_city} → {item.matched_ride_to_city}</span>
              <span className="list-row__sub">{appText("Выезд рейса", "Сәфәргә сығыу")}: {formatWhen(item.matched_ride_depart_at, lang !== "ba")}</span>
              <span className="list-row__sub">{appText("Участок пассажира", "Юлсының юл өлөшө")}: {item.matched_pickup_stop} → {item.matched_dropoff_stop}</span>
            </span>
            <input type="radio" name={`respond-trip-${item.id}`} checked={tripChoice === "ride"} disabled={staleRide} onChange={() => { setTripChoice("ride"); setError(null); }} />
          </label>
          <label className="list-row list-row--check" style={{ minHeight: 48 }}>
            <span className="list-row__main">{appText("Отдельная поездка", "Айырым сәфәр")}</span>
            <input type="radio" name={`respond-trip-${item.id}`} checked={tripChoice === "separate"} onChange={() => { setTripChoice("separate"); setError(null); }} />
          </label>
          <p className="sheet__note">{appText("Время посадки уточни в чате.", "Ултырыу ваҡытын чатта асыҡла.")}</p>
        </fieldset>}
        <label className="field" style={{ marginTop: 12 }}>
          <span className="field__label">{appText("Цена, ₽", "Хаҡ, ₽")}</span>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            placeholder={appText("например 500", "мәҫәлән 500")}
          />
        </label>
        <label className="field" style={{ marginTop: 12 }}>
          <span className="field__label">{appText("Комментарий", "Аңлатма")}</span>
          <textarea
            className="field__input field__area"
            value={comment}
            maxLength={500}
            onChange={(e) => setComment(e.target.value)}
            placeholder={appText("Могу забрать у дома в 8:00", "Өйҙән 8:00-дә ала алам")}
          />
        </label>
        {error && <div className="auth__error">{error}</div>}
        {staleRide && <button type="button" className="btn-soft" onClick={() => { if (!inFlight.current) { if (onRefresh) onRefresh(); else onClose(); } }}>{appText("Обновить заявки", "Заявкаларҙы яңыртыу")}</button>}
        <button type="button" className="btn-primary sheet__cta" onClick={submit} disabled={busy || exit.leaving || (matched && tripChoice === null)}>
          {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Отправить отклик", "Яуап ебәрергә")}
        </button>
      </div>
    </div>
  );
}
