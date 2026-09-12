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
import { RideCardSkeleton } from "../components/States";
import { AdminIntro, ListedEmpty, ListedError, NearbyChip } from "../components/adminUi";
import { IconCheck, IconChat, IconSend } from "../components/Icons";
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
      <SubHeader title={appText("Обращения", "Мөрәжәғәттәр")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Человек написал и ждёт ответа. Ответ переоткрывает закрытое обращение — разговор продолжается, а не начинается заново.",
            "Кеше яҙған һәм яуап көтә. Яуап ябылған мөрәжәғәтте ҡабат аса — һөйләшеү дауам итә, яңынан башланмай."
          )}
        </AdminIntro>

        {/* В приложении два чипа — «Открытые» и «Все»; «Закрытые» есть только в вебе, тем же чипом. */}
        <div className="afilter-row" role="group" aria-label={appText("Фильтр обращений", "Мөрәжәғәт фильтры")}>
          {(
            [
              ["open", appText("Открытые", "Асыҡтар")],
              ["all", appText("Все", "Барыһы")],
              ["closed", appText("Закрытые", "Ябыҡтар")],
            ] as [Filter, string][]
          ).map(([k, label]) => (
            <NearbyChip key={k} icon={<IconChat size={15} />} label={label} active={filter === k} onClick={() => setFilter(k)} />
          ))}
        </div>

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && (
          <ListedError message={appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер.")} onRetry={() => load()} />
        )}

        {state === "ready" && tickets.length === 0 && (
          <ListedEmpty
            title={appText("Обращений нет", "Мөрәжәғәт юҡ")}
            subtitle={appText(
              "Никто не ждёт ответа. Загляни сюда позже — люди пишут не каждый день.",
              "Бер кем яуап көтмәй. Һуңыраҡ кил — кешеләр һәр көн яҙмай."
            )}
          />
        )}

        {state === "ready" &&
          tickets.map((t) => {
            // «Последним написал человек» и есть признак «ждёт нас». Статус open тут мало
            // говорит: открытым остаётся и тикет, где мы уже ответили.
            const waitsUs = t.status === "open" && t.last_sender === "user";
            return (
              <button
                key={t.id}
                type="button"
                className={"acard acard--btn" + (waitsUs ? " acard--waits" : "")}
                onClick={() =>
                  fetchAdminTicket(t.id)
                    .then(setOpen)
                    .catch(() => {
                      /* не открылось — список остаётся на месте */
                    })
                }
              >
                <span className="acard__row">
                  <strong className="acard__title">{t.user_name || appText("Без имени", "Исемһеҙ")}</strong>
                  {waitsUs ? (
                    <span className="atext atext--green">{appText("ждёт ответа", "яуап көтә")}</span>
                  ) : t.status === "closed" ? (
                    <small className="acard__date">{appText("закрыто", "ябылған")}</small>
                  ) : null}
                </span>
                {t.subject && <span className="acard__text">{t.subject}</span>}
                <span className="acard__sub">{t.last_message}</span>
                {t.updated_at && <small className="acard__date">{formatWhen(t.updated_at, ru)}</small>}
              </button>
            );
          })}
      </div>
    </>
  );
}

// ----------------------------- Один тред -----------------------------
/** Тред: тема 19 Bold, сообщения карточками (наши — на подложке попутки), поле ответа, «Ответить» и «Закрыть». */
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
      <SubHeader title={appText("Обращение", "Мөрәжәғәт")} onBack={onBack} />
      <div className="alist">
        <h2 className="athread__subject">{thread.subject || appText("Без темы", "Темаһыҙ")}</h2>
        {thread.messages.map((m) => {
          const mine = m.sender === "admin";
          return (
            <div key={m.id} className={"amsg" + (mine ? " amsg--mine" : "")}>
              <small className="amsg__who">{mine ? appText("Поддержка", "Ярҙам хеҙмәте") : appText("Человек", "Кеше")}</small>
              <p>{m.body}</p>
              {m.created_at && <small className="acard__date">{formatWhen(m.created_at, ru)}</small>}
            </div>
          );
        })}

        {error && <div className="auth__error">{error}</div>}

        <textarea
          className="field__input field__area"
          value={text}
          onChange={(e) => setText(e.target.value.slice(0, 4000))}
          placeholder={appText("Ответ человеку", "Кешегә яуап")}
          aria-label={appText("Ответ", "Яуап")}
          rows={3}
        />
        <div className="acard__actions">
          <button type="button" className="abtn abtn--48" onClick={() => void send()} disabled={busy || !text.trim()}>
            <IconSend size={18} /> {appText("Ответить", "Яуап биреү")}
          </button>
          <button type="button" className="abtn abtn--48 abtn--outline" onClick={close} disabled={busy || thread.status === "closed"}>
            <IconCheck size={18} /> {appText("Закрыть", "Ябыу")}
          </button>
        </div>
        <small className="acard__date">
          {appText(
            "Закрытие не запрещает человеку написать снова: его ответ откроет обращение обратно.",
            "Ябыу кешегә ҡабат яҙырға ҡамасауламай: уның яуабы мөрәжәғәтте кире аса."
          )}
        </small>
      </div>
    </>
  );
}
