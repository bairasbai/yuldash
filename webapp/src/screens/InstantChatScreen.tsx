// ================================================================
//  Чат такси-заказа (пассажир ↔ водитель). RequireAuth →
//  /taxi-chat/:orderId (зеркало chat.py: /instant/orders/{id}/messages
//  + WS /ws/instant/{id}/chat).
//
//  Писать можно в активном заказе (accepted..onboard); после
//  завершения/отмены — только чтение истории.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchOrderMessages,
  sendOrderMessageRest,
  openOrderChat,
  type ChatMessage,
} from "../api/chat";
import { fetchInstantOrder, isUnlocked, type InstantOrder } from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { ChatPhotoButton, ChatMessageBody } from "../components/ChatPhoto";
import { ChatVoiceButton, VoiceBubble } from "../components/ChatVoice";
import { IconArrow } from "../components/Icons";
import { YuChat } from "../components/BrandIcons";
import QuickReplies from "../components/QuickReplies";
import { ChatFlagPlate, ChatSafetyDisclaimer } from "../components/ChatSafety";

export default function InstantChatScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { orderId } = useParams();
  const id = Number(orderId);
  const myId = user?.id ?? -1;

  const [order, setOrder] = useState<InstantOrder | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState("");
  const [live, setLive] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [noChat, setNoChat] = useState(false); // чат ещё не открыт (до accept)
  const endRef = useRef<HTMLDivElement | null>(null);
  const chatRef = useRef<ReturnType<typeof openOrderChat> | null>(null);

  const upsert = useCallback((m: ChatMessage) => {
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
  }, []);

  // Заголовок: имя собеседника из заказа. Отменяем при уходе с экрана —
  // иначе ответ прилетает в размонтированный компонент.
  useEffect(() => {
    if (!id) return;
    const ac = new AbortController();
    fetchInstantOrder(id, ac.signal)
      .then(setOrder)
      .catch(() => {});
    return () => ac.abort();
  }, [id]);

  // История + WS.
  useEffect(() => {
    if (!id) return;
    let alive = true;
    fetchOrderMessages(id)
      .then((list) => {
        if (alive) {
          setMessages(list);
          setLoaded(true);
        }
      })
      .catch((e) => {
        if (!alive) return;
        // 409 = чат ещё не открыт (до accept) / история закрыта.
        if (e instanceof ApiError && e.status === 409) setNoChat(true);
        setLoaded(true);
      });

    const chat = openOrderChat(id, {
      onMessage: upsert,
      onOpen: () => setLive(true),
      onClose: () => setLive(false),
      onError: () => setLive(false),
    });
    chatRef.current = chat;
    return () => {
      alive = false;
      chat.close();
      chatRef.current = null;
    };
  }, [id, upsert]);

  // Поллинг-фолбэк без живого WS.
  useEffect(() => {
    if (live || !loaded || noChat) return;
    const iv = window.setInterval(() => {
      fetchOrderMessages(id)
        .then(setMessages)
        .catch(() => {});
    }, 7000);
    return () => window.clearInterval(iv);
  }, [live, loaded, noChat, id]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  const readOnly = order ? !isUnlocked(order.status) : false;
  const peer =
    order?.role === "driver"
      ? order?.passenger_name || appText("Пассажир", "Юлаусы")
      : order?.driver_name || appText("Водитель", "Водитель");

  // Общая отправка (поле ввода и быстрые ответы): живой сокет, фолбэк — REST.
  async function sendText(t: string) {
    if (!t) return;
    const sentLive = chatRef.current?.send(t);
    if (!sentLive) {
      try {
        const m = await sendOrderMessageRest(id, t);
        upsert(m);
      } catch (e) {
        if (e instanceof ApiError) setText(t);
      }
    }
  }

  /** Голос уходит по REST: сокет передаёт только текст, а ссылку надо положить в поле. */
  async function sendVoice(voiceUrl: string) {
    try {
      const m = await sendOrderMessageRest(id, "", voiceUrl);
      upsert(m);
    } catch {
      /* не отправилось — человек запишет заново, чат не ломаем */
    }
  }

  async function send() {
    const t = text.trim();
    if (!t) return;
    setText("");
    await sendText(t);
  }

  return (
    <>
      <SubHeader
        title={appText("Чат заказа", "Заказ чаты")}
        subtitle={peer}
        onBack={() => navigate(-1)}
      />

      {noChat ? (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <YuChat size={36} />
          </div>
          <h2>{appText("Чат откроется после принятия", "Чат ҡабул иткәс асыла")}</h2>
          <p>
            {appText(
              "Как только водитель примет заказ — здесь можно будет списаться.",
              "Водитель заказды алғас — бында яҙышып була."
            )}
          </p>
        </div>
      ) : (
        <div className="chat chat--full">
          <div className="chat__body">
            <ChatSafetyDisclaimer />
            {!loaded && (
              <div className="chat__loading" aria-live="polite">
                <span className="skeleton chat__skeleton" />
                <span className="skeleton chat__skeleton chat__skeleton--mine" />
                <span className="skeleton chat__skeleton" />
              </div>
            )}
            {loaded && messages.length === 0 && (
              <p className="chat__empty">
                {appText("Напиши первым — обсудите детали подачи.", "Беренсе булып яҙ — килеү тәфсиләтен һөйләшегеҙ.")}
              </p>
            )}
            {messages.map((m) => {
              const mine = m.sender_id === myId;
              return (
                <div key={m.id} className={"msg" + (mine ? " msg--mine" : "")}>
                  <div className={"bubble" + (mine ? " bubble--mine" : "")}>
                    {m.from_admin && <span className="bubble__admin">Юлдаш ✓</span>}
                    {m.voice_url ? <VoiceBubble url={m.voice_url} /> : <ChatMessageBody text={m.text} />}
                  </div>
                  <ChatFlagPlate flag={m.flag} mine={mine} />
                </div>
              );
            })}
            <div ref={endRef} />
          </div>
          {readOnly ? (
            <div className="chat__readonly">
              {appText("Поездка завершена — чат доступен только для чтения.", "Сәфәр тамамланды — чат тик уҡыу өсөн.")}
            </div>
          ) : (
            <>
              {/* Быстрые ответы — один тап отправляет готовую фразу */}
              <QuickReplies onPick={(t) => void sendText(t)} />
              <div className="chat__input">
              <input
                value={text}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") send();
                }}
                placeholder={appText("Сообщение…", "Хат…")}
                aria-label={appText("Сообщение", "Хат")}
              />
              <ChatPhotoButton onReady={(t) => void sendText(t)} />
                <ChatVoiceButton onSend={(u) => void sendVoice(u)} />
          <button type="button" onClick={send} aria-label={appText("Отправить", "Ебәрергә")}>
                <IconArrow size={20} />
              </button>
              </div>
            </>
          )}
        </div>
      )}
    </>
  );
}
