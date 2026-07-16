// ================================================================
//  Квитанция завершённой поездки (GET /trips/{booking_id}/receipt).
//  Эндпоинт появится на проде после мержа release-2026-07 → мягкая
//  деградация: 404 (нет эндпоинта) / 409 (поездка не завершена) →
//  аккуратное состояние, без краша.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchReceipt, type TripReceipt } from "../api/bookings";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconReceipt, IconHeart, IconCheck } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "soft"; reason: "notyet" | "pending" } // мягкая деградация
  | { kind: "ready"; r: TripReceipt };

export default function TripReceiptScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const bookingId = Number(id);

  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!bookingId) {
        setState({ kind: "error" });
        return;
      }
      setState({ kind: "loading" });
      fetchReceipt(bookingId, signal)
        .then((r) => setState({ kind: "ready", r }))
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          if (e instanceof ApiError) {
            // 404 — эндпоинта ещё нет на проде (до деплоя release-2026-07).
            if (e.status === 404) return setState({ kind: "soft", reason: "notyet" });
            // 409 — поездка ещё не завершена.
            if (e.status === 409) return setState({ kind: "soft", reason: "pending" });
          }
          setState({ kind: "error" });
        });
    },
    [bookingId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <SubHeader
        title={appText("Квитанция", "Квитанция")}
        onBack={() => navigate(-1)}
      />

      {state.kind === "loading" && <LoadingList count={2} />}
      {state.kind === "error" && <ErrorState onRetry={() => load()} />}

      {state.kind === "soft" && (
        <div className="state">
          <div className="state__emoji"><IconReceipt size={34} /></div>
          <h2>
            {state.reason === "pending"
              ? appText("Поездка ещё не завершена", "Сәфәр әле тамамланмаған")
              : appText("Квитанция скоро появится", "Квитанция тиҙҙән күренәсәк")}
          </h2>
          <p>
            {state.reason === "pending"
              ? appText(
                  "Квитанция будет доступна сразу после завершения поездки.",
                  "Квитанция сәфәр тамамланғас уҡ асыла."
                )
              : appText(
                  "Мы включим квитанции с ближайшим обновлением сервиса.",
                  "Квитанцияларҙы яҡын яңыртыуҙа тоташтырабыҙ."
                )}
          </p>
        </div>
      )}

      {state.kind === "ready" && (
        <div className="receipt">
          <div className="receipt__brand"><IconHeart size={16} /> {appText("Юлдаш", "Юлдаш")}</div>
          <div className="receipt__route">
            <span>{state.r.from_city}</span>
            <span className="ride-card__arrow">
              <IconArrow size={18} />
            </span>
            <span>{state.r.to_city}</span>
          </div>
          <div className="receipt__date">{formatWhen(state.r.depart_at, ru)}</div>

          <div className="receipt__rows">
            <div className="info-row">
              <span className="info-row__k">{appText("Водитель", "Водитель")}</span>
              <span className="info-row__v">
                {state.r.driver_name}
                {state.r.driver_verified && (
                  <span className="badge badge--mint" style={{ marginLeft: 6 }}><IconCheck size={12} /></span>
                )}
              </span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Мест", "Урын")}</span>
              <span className="info-row__v">{state.r.seats}</span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Способ оплаты", "Түләү ысулы")}</span>
              <span className="info-row__v">{payMethodLabel(state.r.pay_method, ru)}</span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Статус оплаты", "Түләү хәле")}</span>
              <span className="info-row__v">
                {state.r.paid
                  ? appText("Оплачено", "Түләнгән")
                  : appText("По договорённости", "Килешеү буйынса")}
              </span>
            </div>
          </div>

          <div className="receipt__total">
            <span>{appText("Итого", "Барлығы")}</span>
            <b>{priceLabel(state.r.amount, ru)}</b>
          </div>

          <p className="receipt__foot">
            {appText(
              "Юлдаш — попутки между своими. Оплата проходит напрямую водителю.",
              "Юлдаш — үҙебеҙ араһында юлдаштар. Түләү тура водителгә бара."
            )}
          </p>
        </div>
      )}
    </>
  );
}
