// ================================================================
//  Редактирование своей заявки (POST /requests/{id}/edit, requests.py
//  RequestEditIn). Править можно только АКТИВНУЮ заявку — matched уже
//  стала поездкой. Префилл — из GET /requests/mine (отдельной ручки
//  «одна заявка» на бэке нет). До деплоя release ручка edit отдаёт
//  404/405 → мягкое «скоро», без краха.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchMyRequests,
  editRequest,
  type RideRequestRow,
  type RequestEditInput,
} from "../api/requests";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconClock } from "../components/Icons";
import { track } from "../analytics";
import { serverDate } from "../utils/serverTime";
import { minDateTimeNow } from "../utils/dateInput";

/** ISO → значение для <input type="datetime-local"> (локальное время). */
function isoToLocal(iso?: string | null): string {
  if (!iso) return "";
  const d = serverDate(iso);
  if (!d) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

type Status = "loading" | "error" | "not-editable" | "ready";

export default function EditRequestScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { id } = useParams();
  const requestId = Number(id);

  const [status, setStatus] = useState<Status>("loading");
  const [row, setRow] = useState<RideRequestRow | null>(null);

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [when, setWhen] = useState(""); // datetime-local
  const [seats, setSeats] = useState(1);
  const [maxPrice, setMaxPrice] = useState("");
  const [comment, setComment] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!requestId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchMyRequests(signal)
        .then((rows) => {
          const req = rows.find((r) => r.id === requestId);
          if (!req) {
            setStatus("error");
            return;
          }
          if (req.status !== "active") {
            setStatus("not-editable");
            return;
          }
          setRow(req);
          setFrom(req.from_city);
          setTo(req.to_city);
          setWhen(isoToLocal(req.desired_at));
          setSeats(req.seats || 1);
          setMaxPrice(req.max_price != null && req.max_price > 0 ? String(req.max_price) : "");
          setComment(req.comment || "");
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [requestId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const canSubmit = from.trim().length > 0 && to.trim().length > 0 && !busy;

  async function submit() {
    if (!canSubmit || !row) return;
    setBusy(true);
    setError(null);
    // Шлём только изменившееся — RequestEditIn меняет ровно присланное.
    const body: RequestEditInput = {};
    if (from.trim() !== row.from_city) body.from_city = from.trim();
    if (to.trim() !== row.to_city) body.to_city = to.trim();
    const newWhen = when ? new Date(when).toISOString() : null;
    if (newWhen && newWhen !== row.desired_at) body.desired_at = newWhen;
    if (seats !== row.seats) body.seats = seats;
    const newMax = maxPrice ? Math.max(0, parseInt(maxPrice, 10) || 0) : null;
    if (newMax != null && newMax !== (row.max_price ?? null)) body.max_price = newMax;
    if (comment.trim() !== (row.comment || "")) body.comment = comment.trim();

    if (Object.keys(body).length === 0) {
      // Нечего менять — просто возвращаемся к откликам.
      navigate(`/requests/${requestId}/responses`, { replace: true });
      return;
    }
    try {
      await editRequest(requestId, body);
      track("request_edited");
      navigate(`/requests/${requestId}/responses`, { replace: true });
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
        // Ручки ещё нет на проде — честно и мягко.
        setError(
          appText(
            "Редактирование появится после ближайшего обновления. Пока можно отменить заявку и создать новую.",
            "Үҙгәртеү яҡын яңыртыуҙан һуң буласаҡ. Әлегә заявканы кире алып, яңыһын яһап була."
          )
        );
      } else {
        setError(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось сохранить. Попробуй снова.", "Һаҡлап булманы. Ҡабат ҡара.")
        );
      }
      setBusy(false);
    }
  }

  if (status === "loading") {
    return (
      <>
        <SubHeader title={appText("Редактировать заявку", "Заявканы үҙгәртеү")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }
  if (status === "error") {
    return (
      <>
        <SubHeader title={appText("Редактировать заявку", "Заявканы үҙгәртеү")} onBack={() => navigate(-1)} />
        <ErrorState onRetry={() => load()} />
      </>
    );
  }
  if (status === "not-editable") {
    return (
      <>
        <SubHeader title={appText("Редактировать заявку", "Заявканы үҙгәртеү")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconClock size={32} />
          </div>
          <h2>{appText("Эту заявку уже не изменить", "Был заявканы үҙгәртеп булмай инде")}</h2>
          <p>
            {appText(
              "Править можно только активную заявку. Если планы поменялись — создай новую.",
              "Тик әүҙем заявканы ғына үҙгәртеп була. Пландар үҙгәрһә — яңыһын яһа."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/request")}>
            {appText("Создать новую", "Яңыһын яһарға")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Редактировать заявку", "Заявканы үҙгәртеү")}
        subtitle={appText("Поменяй, что нужно — водители увидят сразу", "Кәрәген үҙгәрт — йөрөтөүселәр шунда уҡ күрер")}
        onBack={() => navigate(-1)}
      />

      <div className="form">
        <label className="field">
          <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
          <input
            className="field__input"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
          <input
            className="field__input"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Когда (необязательно)", "Ҡасан (мотлаҡ түгел)")}</span>
          <input
            className="field__input"
            type="datetime-local"
            min={minDateTimeNow()}
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </label>

        <div className="field-row">
          <div className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Мест", "Урын")}</span>
            <div className="stepper">
              <button
                type="button"
                onClick={() => setSeats((s) => Math.max(1, s - 1))}
                aria-label={appText("Меньше", "Кәм")}
              >
                −
              </button>
              <b>{seats}</b>
              <button
                type="button"
                onClick={() => setSeats((s) => Math.min(8, s + 1))}
                aria-label={appText("Больше", "Күберәк")}
              >
                +
              </button>
            </div>
          </div>
          <label className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Цена до, ₽", "Хаҡ, ₽ ҡәҙәр")}</span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              min={0}
              max={1000000}
              value={maxPrice}
              onChange={(e) => setMaxPrice(e.target.value)}
              placeholder={appText("не важно", "мөһим түгел")}
            />
          </label>
        </div>

        <label className="field">
          <span className="field__label">{appText("Комментарий", "Аңлатма")}</span>
          <textarea
            className="field__input"
            rows={3}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder={appText("Например: буду с сумками", "Мәҫәлән: сумкалар менән булам")}
          />
        </label>

        {error && <div className="auth__error">{error}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          disabled={!canSubmit}
          onClick={submit}
        >
          {busy ? (
            appText("Сохраняем…", "Һаҡлайбыҙ…")
          ) : (
            <>
              <IconCheck size={18} /> {appText("Сохранить изменения", "Үҙгәрештәрҙе һаҡларға")}
            </>
          )}
        </button>
      </div>
    </>
  );
}
