// ================================================================
//  Тред обращения → /support/:id (RequireAuth).
//  Сообщения пузырями (user справа, поддержка слева). Ввод + отправка,
//  «Закрыть обращение». Закрытый — можно снова написать (бэк
//  переоткрывает). Все состояния: загрузка / ошибка / 404.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  closeTicket,
  fetchTicket,
  sendTicketMessage,
  type TicketThread,
} from "../api/support";
import { formatRelative } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow } from "../components/Icons";
import { YuChat, YuSupport } from "../components/BrandIcons";

type Load = "loading" | "ok" | "error" | "missing";

export default function SupportTicketScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id: idParam } = useParams();
  const id = Number(idParam);

  const [thread, setThread] = useState<TicketThread | null>(null);
  const [state, setState] = useState<Load>("loading");
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [closing, setClosing] = useState(false);
  const endRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!id) {
        setState("missing");
        return;
      }
      setState("loading");
      fetchTicket(id, signal)
        .then((t) => {
          setThread(t);
          setState("ok");
        })
        .catch((e) => {
          if (signal?.aborted) return;
          setState(e instanceof ApiError && e.status === 404 ? "missing" : "error");
        });
    },
    [id]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [thread?.messages.length]);

  async function send() {
    const b = text.trim();
    if (!b || sending) return;
    setSending(true);
    setText("");
    try {
      const t = await sendTicketMessage(id, b);
      setThread(t);
    } catch (e) {
      setText(b); // вернём текст, чтобы не потерять
      if (e instanceof ApiError && e.status === 404) setState("missing");
    } finally {
      setSending(false);
    }
  }

  async function onClose() {
    if (closing || !thread) return;
    setClosing(true);
    try {
      const t = await closeTicket(id);
      setThread(t);
    } catch {
      /* мягко: статус подтянется при обновлении */
    } finally {
      setClosing(false);
    }
  }

  if (state === "missing") {
    return (
      <>
        <SubHeader
          title={appText("Обращение", "Мөрәжәғәт")}
          onBack={() => navigate("/support")}
        />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <YuChat size={36} />
          </div>
          <h2>{appText("Обращение не найдено", "Мөрәжәғәт табылманы")}</h2>
          <p>{appText("Возможно, оно было удалено.", "Бәлки, ул юйылған.")}</p>
          <button type="button" className="btn-primary" onClick={() => navigate("/support")}>
            {appText("К списку обращений", "Мөрәжәғәттәр исемлегенә")}
          </button>
        </div>
      </>
    );
  }

  if (state === "error") {
    return (
      <>
        <SubHeader title={appText("Обращение", "Мөрәжәғәт")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon state__icon--warn">
            <YuSupport size={34} />
          </div>
          <h2>{appText("Не удалось загрузить", "Йөкләп булманы")}</h2>
          <p>{appText("Проверь соединение и попробуй снова.", "Бәйләнеште тикшереп, ҡабат ҡара.")}</p>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      </>
    );
  }

  const closed = thread?.status === "closed";

  return (
    <>
      <SubHeader
        title={thread?.subject || appText("Обращение", "Мөрәжәғәт")}
        subtitle={
          closed
            ? appText("Закрыто · можно написать снова", "Ябылған · ҡабат яҙып була")
            : appText("Поддержка Юлдаш", "Юлдаш ярҙамы")
        }
        onBack={() => navigate("/support")}
      />

      {state === "loading" ? (
        <div className="center-fill" role="status" aria-live="polite">
          <div className="spinner" aria-hidden />
          <p>{appText("Загружаем…", "Йөкләйбеҙ…")}</p>
        </div>
      ) : (
        <div className="chat chat--full">
          <div className="chat__body">
            {thread && thread.messages.length === 0 && (
              <p className="chat__empty">
                {appText("Здесь появится переписка с поддержкой.", "Бында ярҙам менән яҙышыу күренәсәк.")}
              </p>
            )}
            {thread?.messages.map((m) => {
              const mine = m.sender === "user";
              return (
                <div key={m.id} className={"bubble" + (mine ? " bubble--mine" : "")}>
                  {m.sender === "admin" && (
                    <span className="bubble__admin">{appText("Поддержка", "Ярҙам")}</span>
                  )}
                  {m.body}
                  <span className="bubble__time">{formatRelative(m.created_at, ru)}</span>
                </div>
              );
            })}
            <div ref={endRef} />
          </div>

          {!closed && thread && thread.messages.length > 0 && (
            <button type="button" className="link-btn support-close" onClick={onClose} disabled={closing}>
              {closing
                ? appText("Закрываем…", "Ябабыҙ…")
                : appText("Закрыть обращение", "Мөрәжәғәтте ябырға")}
            </button>
          )}

          <div className="chat__input">
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") send();
              }}
              placeholder={
                closed
                  ? appText("Написать снова…", "Ҡабат яҙырға…")
                  : appText("Сообщение…", "Хәбәр…")
              }
              aria-label={appText("Сообщение", "Хәбәр")}
              disabled={sending}
            />
            <button
              type="button"
              onClick={send}
              disabled={sending || !text.trim()}
              aria-label={appText("Отправить", "Ебәрергә")}
            >
              <IconArrow size={20} />
            </button>
          </div>
        </div>
      )}
    </>
  );
}
