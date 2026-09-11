import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useNavigate } from "react-router-dom";
import { cancelRequest, fetchMatchRides, fetchMyRequests, type RideRequestRow } from "../api/requests";
import type { Ride } from "../api/rides";
import { useLang } from "../i18n/lang";
import { IconCalendar, IconChat, IconChevron, IconPencil, IconProfile, IconRequest, IconTrash, IconWallet } from "../components/Icons";
import RideCard from "../components/RideCard";
import RideSheet from "../components/RideSheet";
import ScreenHeader from "../components/ScreenHeader";
import { ErrorState, LoadingList } from "../components/States";
import { formatWhen, pluralRu } from "../utils/format";

type ViewState = "loading" | "error" | "ready";

const STATUS: Record<string, [string, string, string]> = {
  // В Android пилюля статуса всегда мятная (RequestSummaryCard: Surface(CanonMint)).
  active: ["Активна", "Әүҙем", "badge--mint"],
  matched: ["Водитель найден", "Йөрөтөүсе табылды", "badge--mint"],
  cancelled: ["Отменена", "Кире алынды", "badge--mint"],
};

/** Автоподбор поездок под заявку: те же состояния и открытие карточки, что в Android. */
function MatchingRidesSection({ requestId }: { requestId: number }) {
  const { appText } = useLang();
  const [state, setState] = useState<ViewState>("loading");
  const [rides, setRides] = useState<Ride[]>([]);
  const [openedRide, setOpenedRide] = useState<Ride | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchMatchRides(requestId, signal)
      .then((rows) => {
        setRides(rows);
        setState("ready");
      })
      .catch((error) => {
        if (signal?.aborted || error?.name === "AbortError") return;
        setState("error");
      });
  }, [requestId]);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return (
    <section className="my-request-matches" aria-label={appText("Подходящие поездки", "Тап килгән сәфәрҙәр")}>
      <h3>{appText("Подходящие поездки", "Тап килгән сәфәрҙәр")}</h3>
      {state === "loading" && (
        <div className="my-request-matches__status" role="status">
          <span className="mini-spinner" aria-hidden />
          {appText("Ищем совпадения…", "Тап килгәндәрҙе эҙләйбеҙ…")}
        </div>
      )}
      {state === "error" && (
        <div className="my-request-matches__status" role="alert">
          <span>{appText("Не удалось загрузить.", "Йөкләп булманы.")}</span>
          <button type="button" className="my-request-matches__retry" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}
      {state === "ready" && rides.length === 0 && (
        <p className="my-request-matches__empty">
          {appText(
            "Пока нет совпадений — как появятся, покажем.",
            "Әлегә тап килгәне юҡ — булыу менән күрһәтербеҙ."
          )}
        </p>
      )}
      {state === "ready" && rides.length > 0 && (
        <div className="my-request-matches__list">
          {rides.map((ride, index) => (
            <button
              key={ride.id}
              type="button"
              className="ride-card-btn"
              onClick={() => setOpenedRide(ride)}
            >
              <RideCard ride={ride} index={index} />
            </button>
          ))}
        </div>
      )}
      {openedRide && <RideSheet ride={openedRide} onClose={() => setOpenedRide(null)} />}
    </section>
  );
}

/** Главная вкладка «Заявка»: список, отклики, правка и отмена — как MyRequestsScreen Android. */
export default function MyRequestsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const [state, setState] = useState<ViewState>("loading");
  const [requests, setRequests] = useState<RideRequestRow[]>([]);
  const [cancelTarget, setCancelTarget] = useState<RideRequestRow | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState("");
  const keepRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const returnFocusRef = useRef<HTMLButtonElement | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchMyRequests(signal)
      .then((rows) => {
        setRequests(rows);
        setState("ready");
      })
      .catch((error) => {
        if (signal?.aborted || error?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (cancelTarget) keepRef.current?.focus();
  }, [cancelTarget]);

  function closeDialog() {
    if (cancelling) return;
    setCancelTarget(null);
    setCancelError("");
    window.setTimeout(() => returnFocusRef.current?.focus(), 0);
  }

  function dialogKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      closeDialog();
      return;
    }
    if (event.key !== "Tab") return;
    const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)");
    if (!buttons?.length) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  async function confirmCancel() {
    if (!cancelTarget || cancelling) return;
    setCancelling(true);
    setCancelError("");
    try {
      const updated = await cancelRequest(cancelTarget.id);
      setRequests((rows) => rows.map((row) => (row.id === updated.id ? updated : row)));
      setCancelTarget(null);
      window.setTimeout(() => returnFocusRef.current?.focus(), 0);
    } catch {
      setCancelError(appText("Не получилось отменить. Проверь связь и повтори.", "Кире алып булманы. Бәйләнеште тикшереп ҡабатла."));
    } finally {
      setCancelling(false);
    }
  }

  return (
    <>
      <ScreenHeader title={appText("Мои заявки", "Минең заявкалар")} />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && (
        <ErrorState
          title={appText("Не удалось загрузить заявки", "Заявкаларҙы йөкләп булманы")}
          onRetry={() => load()}
        />
      )}

      {state === "ready" && requests.length === 0 && (
        <div className="state request-empty">
          <div className="state__icon"><IconRequest size={34} /></div>
          <h2>{appText("Заявок пока нет", "Әлегә заявкалар юҡ")}</h2>
          <p>{appText("Создай заявку — водители увидят её и откликнутся.", "Заявка булдыр — йөрөтөүселәр уны күреп яуап бирер.")}</p>
          <button type="button" className="btn-primary" onClick={() => navigate("/request")}>
            {appText("Создать заявку", "Заявка булдырыу")}
          </button>
        </div>
      )}

      {state === "ready" && requests.length > 0 && (
        <div className="my-requests-list">
          {requests.map((request, index) => {
            const status = STATUS[request.status] ?? [request.status, request.status, "badge--muted"];
            const active = request.status === "active";
            return (
              <article
                key={request.id}
                className="my-request-card"
                style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}
              >
                <div className="my-request-card__head">
                  <span className="my-request-card__icon" aria-hidden><IconRequest size={24} /></span>
                  <div className="my-request-card__main">
                    <div className="my-request-card__title-row">
                      <h3 className="my-request-card__route">{request.from_city} → {request.to_city}</h3>
                      <span className={`badge ${status[2]}`}>{appText(status[0], status[1])}</span>
                    </div>
                    <span className="my-request-card__meta"><IconCalendar size={16} /> {formatWhen(request.desired_at, ru)}</span>
                    {request.comment && <span className="my-request-card__meta"><IconRequest size={16} /> {request.comment}</span>}
                    <span className="my-request-card__meta">
                      <IconWallet size={16} />{" "}
                      {request.max_price
                        ? appText(`${request.max_price} ₽ предлагаю`, `${request.max_price} ₽ тәҡдим итәм`)
                        : appText("цена договорная", "хаҡ килешеү буйынса")}
                    </span>
                    <span className="my-request-card__meta">
                      <IconProfile size={16} /> {appText(`${request.seats} ${pluralRu(request.seats, "место", "места", "мест")}`, `${request.seats} урын`)}
                    </span>
                  </div>
                </div>
                <div className="my-request-card__actions">
                  <button type="button" className="btn-soft my-request-card__responses" onClick={() => navigate(`/requests/${request.id}/responses`)}>
                    <IconChat size={20} />
                    <span>{appText("Посмотреть отклики", "Яуаптарҙы ҡарау")}</span>
                    <IconChevron size={20} />
                  </button>
                  {active && (
                    <div className="icon-row">
                      <button
                        type="button"
                        className="btn-ghost icon-label-btn"
                        onClick={() => navigate(`/requests/${request.id}/edit`)}
                      >
                        <IconPencil size={18} /> {appText("Редактировать", "Үҙгәртеү")}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost icon-label-btn danger-text"
                        onClick={(event) => {
                          returnFocusRef.current = event.currentTarget;
                          setCancelTarget(request);
                        }}
                      >
                        <IconTrash size={18} /> {appText("Отменить заявку", "Заявканы кире алыу")}
                      </button>
                    </div>
                  )}
                </div>
                <MatchingRidesSection requestId={request.id} />
              </article>
            );
          })}
          <button type="button" className="btn-primary my-requests-create" onClick={() => navigate("/request")}>
            <IconRequest size={20} /> {appText("Создать новую", "Яңыһын булдырыу")}
          </button>
        </div>
      )}

      {cancelTarget && (
        <div className="sheet-backdrop" onClick={closeDialog}>
          <section
            ref={dialogRef}
            className="sheet"
            role="dialog"
            aria-modal="true"
            aria-labelledby="cancel-request-title"
            onClick={(event) => event.stopPropagation()}
            onKeyDown={dialogKeyDown}
          >
            <h2 className="sheet__title" id="cancel-request-title">
              {appText("Отменить заявку?", "Заявканы кире алабыҙмы?")}
            </h2>
            <p className="sheet__comment">
              {appText("Водители больше не увидят её. Это действие нельзя отменить.", "Йөрөтөүселәр уны күрмәҫ. Быны кире ҡайтарып булмай.")}
            </p>
            {cancelError && <div className="notice" role="alert">{cancelError}</div>}
            <div className="sheet__actions">
              <button ref={keepRef} type="button" className="btn-ghost" disabled={cancelling} onClick={closeDialog}>
                {appText("Оставить", "Ҡалдырыу")}
              </button>
              <button type="button" className="btn-danger" disabled={cancelling} onClick={() => void confirmCancel()}>
                {cancelling ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить заявку", "Кире алыу")}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
