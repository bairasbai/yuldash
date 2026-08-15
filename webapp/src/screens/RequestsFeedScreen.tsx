// ================================================================
//  Лента заявок пассажиров для водителя (GET /requests/feed).
//  Отклик на заявку (POST /requests/{id}/respond) — цена + комментарий.
//  Водительская зона, но по контракту requests.py — доступна вошедшим.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchRequestsFeed,
  respondToRequest,
  type RequestFeedItem,
} from "../api/requests";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconRequest, IconCheck } from "../components/Icons";
import { formatWhen } from "../utils/format";
import { useScrollMemory } from "../utils/useScrollMemory";

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

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchRequestsFeed(signal)
      .then((list) => {
        setItems(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <SubHeader
        title={appText("Заявки пассажиров", "Пассажир заявкалары")}
        subtitle={appText("Отзовись — предложи свою цену", "Яуап бир — хаҡыңды тәҡдим ит")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={4} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "ready" &&
        (items.length === 0 ? (
          <div className="state">
            <div className="state__icon"><IconRequest size={34} /></div>
            <h2>{appText("Пока нет заявок", "Әле заявкалар юҡ")}</h2>
            <p>{appText("Как только кто-то будет искать попутку — покажем здесь.", "Кемдер юлдаш эҙләһә — бында күрһәтәбеҙ.")}</p>
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
                    <b>{it.seats}</b> {appText("мест", "урын")}
                  </span>
                  {/* Насколько заявка уводит с твоего маршрута. Без этого водитель
                      читал каждую заявку руками — и уставал от тех, что в другую сторону. */}
                  {/* «По пути» — зелёным, крюк — спокойно серым, без осуждения. */}
                  {it.detour_km != null && (
                    <span
                      className={"badge " + (it.detour_km <= 10 ? "badge--mint" : "badge--muted")}
                    >
                      {it.detour_km <= 10
                        ? appText("По пути", "Юл ыңғайында")
                        : appText(`Крюк ≈ ${it.detour_km} км`, `Урау ≈ ${it.detour_km} км`)}
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
          item={respondTo}
          onClose={() => setRespondTo(null)}
          onDone={(id) => {
            setItems((prev) =>
              prev.map((x) =>
                x.id === respondTo.id ? { ...x, responded: true, my_response_id: id } : x
              )
            );
            setRespondTo(null);
          }}
        />
      )}
    </>
  );
}

function RespondSheet({
  item,
  onClose,
  onDone,
}: {
  item: RequestFeedItem;
  onClose: () => void;
  onDone: (responseId: number) => void;
}) {
  const { appText } = useLang();
  const [price, setPrice] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const res = await respondToRequest(
        item.id,
        price ? Math.max(0, parseInt(price, 10) || 0) : 0,
        comment.trim()
      );
      onDone(res.id);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Попробуй снова.", "Булманы. Ҡабат ҡара.") // DRAFT
      );
      setBusy(false);
    }
  }

  return (
    <div className="sheet-backdrop" onClick={onClose} role="presentation">
      <div className="sheet" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="sheet__grip" aria-hidden />
        <h2 style={{ margin: "0 0 4px", fontSize: "var(--font-heading)", fontWeight: 800 }}>
          {appText("Твой отклик", "Яуабың")}
        </h2>
        <p className="sheet__note" style={{ marginTop: 0 }}>
          {item.from_city} → {item.to_city}
        </p>
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
        <button type="button" className="btn-primary sheet__cta" onClick={submit} disabled={busy}>
          {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Отправить отклик", "Яуап ебәрергә")}
        </button>
      </div>
    </div>
  );
}
