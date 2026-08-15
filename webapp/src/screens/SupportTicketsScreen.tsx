// ================================================================
//  Поддержка Юлдаш → /support (RequireAuth).
//  Список обращений + «Новое обращение» (тема + текст → POST).
//  Бейдж непрочитанного (ждут ответа пользователя). Все состояния.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  createTicket,
  fetchTickets,
  type TicketListItem,
} from "../api/support";
import { formatRelative } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import { IconWarn, IconChat } from "../components/Icons";

type Load = "loading" | "ok" | "error";
type Send = "idle" | "sending";

export default function SupportTicketsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [items, setItems] = useState<TicketListItem[]>([]);
  const [state, setState] = useState<Load>("loading");
  const [composing, setComposing] = useState(false);
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [send, setSend] = useState<Send>("idle");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchTickets(signal)
      .then((r) => {
        setItems(r.items);
        setState("ok");
      })
      .catch(() => {
        if (signal?.aborted) return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function submit() {
    const b = body.trim();
    if (b.length < 1 || send === "sending") return;
    setSend("sending");
    setError(null);
    try {
      const t = await createTicket(subject.trim(), b);
      setComposing(false);
      setSubject("");
      setBody("");
      navigate(`/support/${t.id}`);
    } catch (e) {
      setSend("idle");
      setError(
        e instanceof ApiError && e.status === 0
          ? appText("Нет соединения. Проверь интернет.", "Бәйләнеш юҡ. Интернетты тикшер.")
          : appText("Не получилось отправить. Попробуй ещё раз.", "Ебәреп булманы. Тағы ла ҡабатла.")
      );
      return;
    }
    setSend("idle");
  }

  return (
    <>
      <SubHeader
        title={appText("Поддержка Юлдаш", "Юлдаш ярҙамы")}
        subtitle={appText("Мы рядом и поможем", "Беҙ янда, ярҙам итәбеҙ")}
        onBack={() => navigate(-1)}
      />

      {!composing && (
        <button
          type="button"
          className="btn-primary support-new"
          onClick={() => {
            setComposing(true);
            setError(null);
          }}
        >
          {appText("Новое обращение", "Яңы мөрәжәғәт")}
        </button>
      )}

      {composing && (
        <div className="support-compose">
          <label className="field">
            <span className="field__label">{appText("Тема (по желанию)", "Тема (теләһәң)")}</span>
            <input
              className="field__input"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              maxLength={200}
              placeholder={appText("Например: оплата поездки", "Мәҫәлән: сәфәр түләүе")}
            />
          </label>
          <label className="field">
            <span className="field__label">{appText("Опиши вопрос", "Һорауыңды яҙ")}</span>
            <textarea
              className="field__input field__area"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={4}
              maxLength={4000}
              placeholder={appText(
                "Расскажи, что случилось — мы во всём разберёмся.",
                "Ни булғанын яҙ — беҙ барыһын асыҡлайбыҙ."
              )}
            />
          </label>

          {error && <div className="auth__error">{error}</div>}

          <div className="support-compose__actions">
            <button
              type="button"
              className="btn-soft"
              onClick={() => {
                setComposing(false);
                setError(null);
              }}
              disabled={send === "sending"}
            >
              {appText("Отмена", "Баш тартыу")}
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={submit}
              disabled={body.trim().length < 1 || send === "sending"}
            >
              {send === "sending"
                ? appText("Отправляем…", "Ебәрәбеҙ…")
                : appText("Отправить", "Ебәреү")}
            </button>
          </div>
        </div>
      )}

      {state === "loading" && <LoadingList count={3} />}

      {state === "error" && (
        <div className="state">
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не удалось загрузить", "Йөкләп булманы")}</h2>
          <p>{appText("Проверь соединение и попробуй снова.", "Бәйләнеште тикшереп, ҡабат ҡара.")}</p>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {state === "ok" && items.length === 0 && !composing && (
        <div className="state">
          <div className="state__icon"><IconChat size={34} /></div>
          <h2>{appText("Пока нет обращений", "Әлегә мөрәжәғәт юҡ")}</h2>
          <p>
            {appText(
              "Есть вопрос или что-то не работает? Напиши — ответим по-человечески.",
              "Һорауың бармы йәки эшләмәйме? Яҙ — кешеләрсә яуап бирәбеҙ."
            )}
          </p>
        </div>
      )}

      {state === "ok" && items.length > 0 && (
        <div className="support-list">
          {items.map((t) => (
            <button
              key={t.id}
              type="button"
              className="support-row"
              onClick={() => navigate(`/support/${t.id}`)}
            >
              <div className="support-row__main">
                <div className="support-row__top">
                  <span className="support-row__subject">
                    {t.subject || appText("Обращение", "Мөрәжәғәт")}
                  </span>
                  {t.status === "closed" ? (
                    <span className="badge badge--muted">{appText("Закрыто", "Ябылған")}</span>
                  ) : (
                    <span className="badge badge--mint">{appText("Открыто", "Асыҡ")}</span>
                  )}
                </div>
                <div className="support-row__last">
                  {t.last_sender === "admin" && (
                    <b>{appText("Поддержка: ", "Ярҙам: ")}</b>
                  )}
                  {t.last_message || appText("Нет сообщений", "Хәбәр юҡ")}
                </div>
                <div className="support-row__meta">{formatRelative(t.updated_at, ru)}</div>
              </div>
              {t.unread && <span className="notif-dot" aria-hidden />}
            </button>
          ))}
        </div>
      )}
    </>
  );
}
