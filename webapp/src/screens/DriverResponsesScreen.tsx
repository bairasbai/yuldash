// ================================================================
//  «Мои отклики» (водитель) — GET /responses/mine + торг о цене.
//  Зеркало Android DriverResponsesScreen.kt.
//
//  Водитель откликался на заявку и дальше не видел ничего: пассажир
//  мог ответить встречной ценой, а узнать об этом можно было только
//  из пуша — пропустил уведомление, потерял поездку.
//
//  Порядок в карточке жёсткий (читается на ходу, одной рукой):
//  состояние → цена на столе → комментарий → как шёл торг → действия.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  acceptResponse,
  counterOffer,
  declineResponse,
  fetchMyResponses,
  parseBargainHistory,
  withdrawResponse,
  type ResponseItem,
} from "../api/requests";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconBolt, IconCheck, IconRequest, IconTrash } from "../components/Icons";
import { priceLabel } from "../utils/format";
import BargainTrail from "../components/BargainTrail";

type Status = "loading" | "error" | "soon" | "ready";

export default function DriverResponsesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<ResponseItem[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [counterFor, setCounterFor] = useState<number | null>(null);
  const [price, setPrice] = useState("");
  const [err, setErr] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMyResponses(signal)
      .then((r) => {
        setRows(r);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && e.status === 404 ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const myTurn = rows.filter((r) => r.can_accept || r.can_counter).length;

  async function accept(r: ResponseItem) {
    if (busyId) return;
    setBusyId(r.id);
    setErr("");
    try {
      const { booking_id } = await acceptResponse(r.id);
      navigate(`/trip/${booking_id}`);
    } catch (e) {
      setErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
      setBusyId(null);
      load();
    }
  }

  async function sendCounter(r: ResponseItem) {
    const value = Number(price);
    if (!Number.isFinite(value) || value <= 0) return;
    setBusyId(r.id);
    setErr("");
    try {
      const updated = await counterOffer(r.id, Math.round(value));
      setRows((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
      setCounterFor(null);
      setPrice("");
    } catch (e) {
      setErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось предложить цену.", "Хаҡ тәҡдим итеп булманы.")
      );
      load();
    } finally {
      setBusyId(null);
    }
  }

  async function refuse(r: ResponseItem) {
    if (busyId) return; // по этому отклику уже идёт другое действие (торг) — не гоняемся
    const prev = rows;
    setRows(rows.filter((x) => x.id !== r.id)); // оптимистично
    try {
      // Пока идёт торг — «отказаться»; после закрытия отклик убирается совсем.
      if (r.status === "offered") await declineResponse(r.id);
      else await withdrawResponse(r.id);
    } catch {
      setRows(prev); // откат
      setErr(appText("Не получилось отменить отклик.", "Яуапты кире алып булманы."));
    }
  }

  /** Подпись состояния отклика — первым делом в карточке. */
  function statusPill(r: ResponseItem): { text: string; cls: string } {
    if (r.status === "accepted")
      return { text: appText("Поездка твоя", "Сәфәр һинеке"), cls: "badge--mint" };
    if (r.status === "declined")
      return { text: appText("Отказ", "Баш тартылған"), cls: "badge--muted" };
    if (r.can_accept || r.can_counter)
      return { text: appText("Твой ход", "Һинең сират"), cls: "badge--gold" };
    return { text: appText("Ждём пассажира", "Юлаусыны көтәбеҙ"), cls: "badge--muted" };
  }

  return (
    <>
      <SubHeader
        title={appText("Мои отклики", "Минең яуаптарым")}
        subtitle={
          myTurn > 0
            ? appText(`Твой ход: ${myTurn}`, `Һинең сират: ${myTurn}`)
            : appText("Торг о цене по заявкам", "Заявкалар буйынса хаҡ һатыулашыуы")
        }
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconRequest size={34} />
          </div>
          <h2>{appText("Скоро здесь", "Тиҙҙән бында")}</h2>
          <p>
            {appText(
              "Торг о цене включится с ближайшим обновлением.",
              "Хаҡ һатыулашыуы яҡын яңыртыуҙа тоташа."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconRequest size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 32 }}>
            <div className="state__icon">
              <IconRequest size={34} />
            </div>
            <h2>{appText("Откликов пока нет", "Әлегә яуап юҡ")}</h2>
            <p>
              {appText(
                "Открой ленту заявок и предложи свою цену — отклик появится здесь.",
                "Заявкалар тасмаһын ас та үҙ хаҡыңды тәҡдим ит — яуап бында күренер."
              )}
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate("/requests-feed")}>
              {appText("Смотреть заявки", "Заявкаларҙы ҡарау")}
            </button>
          </div>
        ) : (
          <div className="list" style={{ marginTop: 12 }}>
            {rows.map((r) => {
              const pill = statusPill(r);
              const live = r.status === "offered";
              return (
                <div key={r.id} className="money-row">
                  <div className="money-row__foot" style={{ marginTop: 0 }}>
                    <span className={"badge " + pill.cls}>{pill.text}</span>
                    {r.bargain_rounds > 0 && (
                      <span className="badge badge--muted">
                        {appText(`Ходов: ${r.bargain_rounds}`, `Сират: ${r.bargain_rounds}`)}
                      </span>
                    )}
                  </div>

                  <div className="bargain-price">
                    <span className="bargain-price__label">
                      {appText("Цена на столе", "Өҫтәлдәге хаҡ")}
                    </span>
                    <span className="bargain-price__value">
                      {priceLabel(r.current_price || r.price, ru)}
                    </span>
                  </div>

                  {r.comment && (
                    <p className="money-row__date" style={{ marginTop: 6 }}>
                      {appText("Твой комментарий: ", "Һинең иҫкәрмәң: ")}
                      {r.comment}
                    </p>
                  )}

                  <BargainTrail
                    history={parseBargainHistory(r.bargain_history)}
                    mine="driver"
                    rounds={r.bargain_rounds}
                  />

                  {live && (r.can_accept || r.can_counter) && (
                    <span className="bargain-turn bargain-turn--mine">
                      <IconBolt size={14} /> {appText("Сейчас твой ход", "Хәҙер һинең сират")}
                    </span>
                  )}
                  {live && !r.can_accept && !r.can_counter && (
                    <span className="bargain-turn">
                      {appText("Ход пассажира — ждём ответа", "Юлаусы сираты — яуап көтәбеҙ")}
                    </span>
                  )}

                  {counterFor === r.id ? (
                    <div style={{ marginTop: 12 }}>
                      <label className="field">
                        <span className="field__label">{appText("Твоя цена, ₽", "Һинең хаҡ, һ")}</span>
                        <input
                          className="field__input"
                          type="number"
                          inputMode="numeric"
                          min={0}
                          value={price}
                          onChange={(e) => setPrice(e.target.value)}
                          placeholder={String(r.current_price || r.price)}
                        />
                      </label>
                      <div className="act-card__actions" style={{ marginTop: 10 }}>
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={() => sendCounter(r)}
                          disabled={busyId === r.id || !price}
                        >
                          {busyId === r.id
                            ? appText("Отправляем…", "Ебәрәбеҙ…")
                            : appText("Предложить", "Тәҡдим итеү")}
                        </button>
                        <button
                          type="button"
                          className="btn-ghost"
                          onClick={() => {
                            setCounterFor(null);
                            setPrice("");
                          }}
                        >
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    </div>
                  ) : (
                    live && (
                      <div className="act-card__actions" style={{ marginTop: 12 }}>
                        {r.can_accept && (
                          <button
                            type="button"
                            className="btn-primary"
                            onClick={() => accept(r)}
                            disabled={busyId === r.id}
                          >
                            <IconCheck size={16} />{" "}
                            {appText("Принять цену", "Хаҡты ҡабул итеү")}
                          </button>
                        )}
                        {r.can_counter && (
                          <button
                            type="button"
                            className="btn-soft"
                            onClick={() => {
                              setCounterFor(r.id);
                              setPrice(String(r.current_price || r.price));
                              setErr("");
                            }}
                          >
                            {appText("Своя цена", "Үҙ хаҡым")}
                          </button>
                        )}
                        <button
                          type="button"
                          className="icon-btn"
                          onClick={() => refuse(r)}
                          aria-label={appText("Отказаться от торга", "Һатыулашыуҙан баш тартыу")}
                        >
                          <IconTrash size={20} />
                        </button>
                      </div>
                    )
                  )}
                </div>
              );
            })}
          </div>
        ))}

      {err && <p className="taxi-note">{err}</p>}

      {status === "ready" && rows.length > 0 && (
        <p className="receipt__foot">
          {appText(
            "Цена на столе — та, по которой создастся поездка. Ходов не больше трёх с каждой стороны.",
            "Өҫтәлдәге хаҡ — сәфәр шуның буйынса төҙөләсәк. Һәр яҡтан өс сираттан артыҡ түгел."
          )}
        </p>
      )}
    </>
  );
}
