// ================================================================
//  Обращения в поддержку → /admin/support (RequireAdmin).
//  GET  /admin/support/tickets?status=open|closed|all
//  GET  /admin/support/tickets/{id}
//  POST /admin/support/tickets/{id}/reply  — ответ уходит пушем человеку
//  POST /admin/support/tickets/{id}/close
//
//  Для такси и доставки поддержка — последняя инстанция при любой проблеме,
//  и тишина в ответ читается как «им всё равно». Поэтому отвечать нужно
//  отовсюду, а не только с телефона.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminTickets,
  fetchAdminTicket,
  replyAdminTicket,
  closeAdminTicket,
  type AdminTicket,
  type TicketThread,
} from "../api/support";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconChat, IconArrow } from "../components/Icons";
import { formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";
type Filter = "open" | "closed" | "all";

export default function AdminSupportScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [filter, setFilter] = useState<Filter>("open");
  const [tickets, setTickets] = useState<AdminTicket[]>([]);
  const [open, setOpen] = useState<TicketThread | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      setState("loading");
      fetchAdminTickets(filter, signal)
        .then((list) => {
          setTickets(list);
          setState("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setState("error");
        });
    },
    [filter]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // ---- Открытый тред ----
  if (open) {
    return (
      <TicketThreadView
        thread={open}
        onBack={() => {
          setOpen(null);
          load();
        }}
        onChange={setOpen}
      />
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Обращения в поддержку", "Ярҙамға мөрәжәғәттәр")}
        subtitle={appText("Ответ уходит человеку сразу", "Яуап кешегә шунда уҡ бара")}
        onBack={() => navigate(-1)}
      />

      <div className="chips">
        {(
          [
            ["open", appText("Открытые", "Асыҡ")],
            ["closed", appText("Закрытые", "Ябыҡ")],
            ["all", appText("Все", "Барыһы")],
          ] as [Filter, string][]
        ).map(([k, label]) => (
          <button
            key={k}
            type="button"
            className={"chip" + (filter === k ? " chip--on" : "")}
            onClick={() => setFilter(k)}
          >
            {label}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && tickets.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconCheck size={34} /></div>
          <h2>{appText("Обращений нет", "Мөрәжәғәт юҡ")}</h2>
          <p>
            {appText(
              "Никто не ждёт ответа. Хороший знак.",
              "Бер кем яуап көтмәй. Яҡшы билдә."
            )}
          </p>
        </div>
      )}

      {state === "ready" && tickets.length > 0 && (
        <div className="list">
          {tickets.map((t) => (
            <button
              key={t.id}
              type="button"
              className="list-row"
              onClick={() =>
                fetchAdminTicket(t.id)
                  .then(setOpen)
                  .catch(() => {
                    /* не открылось — список остаётся на месте */
                  })
              }
            >
              <div className="list-row__main">
                <div className="list-row__title">
                  {t.subject || appText("Без темы", "Темаһыҙ")}
                  {/* Последнее слово за человеком — значит ждут нас */}
                  {t.last_sender === "user" && t.status === "open" && (
                    <span className="badge badge--gold" style={{ marginLeft: 6 }}>
                      {appText("ждёт ответа", "яуап көтә")}
                    </span>
                  )}
                </div>
                <div className="list-row__sub">
                  {t.user_name} · {formatWhen(t.updated_at, ru)} · {t.message_count}{" "}
                  {appText("сообщ.", "хәбәр")}
                </div>
                <div className="list-row__sub">{t.last_message}</div>
              </div>
              <span className="list-row__chev">
                <IconArrow size={18} />
              </span>
            </button>
          ))}
        </div>
      )}
    </>
  );
}

// ----------------------------- Один тред -----------------------------
function TicketThreadView({
  thread,
  onBack,
  onChange,
}: {
  thread: TicketThread;
  onBack: () => void;
  onChange: (t: TicketThread) => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send() {
    const t = text.trim();
    if (!t || busy) return;
    setBusy(true);
    setError(null);
    try {
      onChange(await replyAdminTicket(thread.id, t));
      setText("");
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не отправилось. Проверь сеть.", "Ебәрелмәне. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function close() {
    if (busy) return;
    setBusy(true);
    try {
      onChange(await closeAdminTicket(thread.id));
    } catch {
      /* уже закрыт — не беда */
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <SubHeader
        title={thread.subject || appText("Обращение", "Мөрәжәғәт")}
        subtitle={
          thread.status === "closed"
            ? appText("Закрыто · ответ переоткроет", "Ябыҡ · яуап яңынан аса")
            : appText("Открыто", "Асыҡ")
        }
        onBack={onBack}
      />

      <div className="chat chat--full">
        <div className="chat__body">
          {thread.messages.map((m) => (
            <div
              key={m.id}
              className={"msg" + (m.sender === "admin" ? " msg--mine" : "")}
            >
              <div className={"bubble" + (m.sender === "admin" ? " bubble--mine" : "")}>
                {m.body}
              </div>
              <span className="ticket-time">{formatWhen(m.created_at, ru)}</span>
            </div>
          ))}
        </div>

        {error && <div className="auth__error">{error}</div>}

        <div className="chat__input">
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") void send();
            }}
            placeholder={appText("Ответ человеку…", "Кешегә яуап…")}
            aria-label={appText("Ответ", "Яуап")}
          />
          <button type="button" onClick={() => void send()} disabled={busy} aria-label={appText("Отправить", "Ебәреү")}>
            <IconChat size={20} />
          </button>
        </div>
      </div>

      {thread.status !== "closed" && (
        <button type="button" className="btn-soft" style={{ marginTop: 12 }} onClick={close} disabled={busy}>
          <IconCheck size={18} /> {appText("Вопрос решён — закрыть", "Мәсьәлә хәл ителде — ябырға")}
        </button>
      )}
    </>
  );
}
