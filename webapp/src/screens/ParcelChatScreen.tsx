// ================================================================
//  Чат по посылке: отправитель ↔ курьер. RequireAuth →
//  /parcel-chat/:parcelId (зеркало chat.py: /parcels/{id}/messages
//  + WS /ws/parcel/{id}/chat). Зеркало Android ParcelChatScreen.kt.
//
//  До этого у посылки была ТОЛЬКО кнопка «позвонить»: договориться
//  письменно — где оставить, кому отдать, когда будут дома — было
//  нечем, а на ходу за рулём никто не звонит.
//
//  После вручения/возврата/отмены чат остаётся на чтение, но не
//  на запись — история спора никуда не девается.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchParcelMessages,
  sendParcelMessageRest,
  openParcelChat,
  type ChatMessage,
} from "../api/chat";
import { fetchMyParcels, fetchCarrying, type Parcel } from "../api/parcels";
import { SubHeader } from "./ConsentsScreen";
import { ChatPhotoButton, ChatMessageBody } from "../components/ChatPhoto";
import { ChatVoiceButton, VoiceBubble } from "../components/ChatVoice";
import { IconArrow } from "../components/Icons";
import { YuChat } from "../components/BrandIcons";

/** Готовые фразы под доставку — один тап вместо набора на ходу. */
function ParcelQuickReplies({ onPick }: { onPick: (t: string) => void }) {
  const { appText } = useLang();
  const items = [
    appText("Выезжаю", "Сығам"),
    appText("Забрал посылку", "Аҫылманы алдым"),
    appText("Буду через 15 минут", "15 минуттан булам"),
    appText("Я на месте", "Мин урында"),
    appText("Спасибо!", "Рәхмәт!"),
  ];
  return (
    <div className="quick-replies chip-scroll">
      {items.map((t) => (
        <button key={t} type="button" className="chip" onClick={() => onPick(t)}>
          {t}
        </button>
      ))}
    </div>
  );
}

export default function ParcelChatScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { parcelId } = useParams();
  const id = Number(parcelId);
  const myId = user?.id ?? -1;

  const [parcel, setParcel] = useState<Parcel | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState("");
  const [live, setLive] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [noChat, setNoChat] = useState(false); // курьер ещё не взял посылку
  const endRef = useRef<HTMLDivElement | null>(null);
  const chatRef = useRef<ReturnType<typeof openParcelChat> | null>(null);

  const upsert = useCallback((m: ChatMessage) => {
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
  }, []);

  // Шапка: кто на той стороне. Ищем посылку среди своих и среди тех, что везу.
  useEffect(() => {
    if (!id) return;
    let alive = true;
    Promise.allSettled([fetchMyParcels(), fetchCarrying()]).then((res) => {
      if (!alive) return;
      const all = res.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
      setParcel(all.find((p) => p.id === id) ?? null);
    });
    return () => {
      alive = false;
    };
  }, [id]);

  // История + живой сокет.
  useEffect(() => {
    if (!id) return;
    let alive = true;
    fetchParcelMessages(id)
      .then((list) => {
        if (!alive) return;
        setMessages(list);
        setLoaded(true);
      })
      .catch((e) => {
        if (!alive) return;
        // 409/403 = чат ещё не открыт (курьера нет) или доступа нет.
        if (e instanceof ApiError && (e.status === 409 || e.status === 403)) setNoChat(true);
        setLoaded(true);
      });

    const chat = openParcelChat(id, {
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
      fetchParcelMessages(id)
        .then(setMessages)
        .catch(() => {});
    }, 7000);
    return () => window.clearInterval(iv);
  }, [live, loaded, noChat, id]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  // Доставлена/отменена → только чтение. Статус неизвестен — не запрещаем писать заранее.
  const readOnly = parcel
    ? ["delivered", "canceled", "returned"].includes(String(parcel.status))
    : false;
  const iAmSender = parcel ? parcel.sender_id === myId : false;
  const peer = parcel
    ? iAmSender
      ? parcel.courier?.name || appText("Курьер", "Курьер")
      : appText("Отправитель", "Ебәреүсе")
    : appText("Посылка", "Аҫылма");

  async function sendText(t: string) {
    if (!t) return;
    const sentLive = chatRef.current?.send(t);
    if (!sentLive) {
      try {
        const m = await sendParcelMessageRest(id, t);
        upsert(m);
      } catch (e) {
        if (e instanceof ApiError) setText(t); // вернём текст в поле, чтобы не потерялся
      }
    }
  }

  /** Голос уходит по REST: сокет передаёт только текст, а ссылку надо положить в поле. */
  async function sendVoice(voiceUrl: string) {
    try {
      const m = await sendParcelMessageRest(id, "", voiceUrl);
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
        title={appText("Чат по посылке", "Аҫылма чаты")}
        subtitle={
          parcel ? `${parcel.from_city} → ${parcel.to_city} · ${peer}` : peer
        }
        onBack={() => navigate(-1)}
      />

      {noChat ? (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <YuChat size={36} />
          </div>
          <h2>{appText("Чат откроется, когда посылку возьмут", "Аҫылманы алғас чат асыла")}</h2>
          <p>
            {appText(
              "Как только попутчик или курьер примет доставку — здесь можно будет списаться.",
              "Юлдаш йәки курьер илтеүҙе алғас — бында яҙышып була."
            )}
          </p>
        </div>
      ) : (
        <div className="chat chat--full">
          <div className="chat__body">
            {loaded && messages.length === 0 && (
              <p className="chat__empty">
                {appText(
                  "Напиши первым — где забрать, кому отдать, когда будут дома.",
                  "Беренсе булып яҙ — ҡайҙан алырға, кемгә бирергә, ҡасан өйҙә булалар."
                )}
              </p>
            )}
            {messages.map((m) => {
              const mine = m.sender_id === myId;
              return (
                <div key={m.id} className={"bubble" + (mine ? " bubble--mine" : "")}>
                  {m.from_admin && (
                    <span className="bubble__admin">{appText("Поддержка", "Ярҙам")}</span>
                  )}
                  {m.voice_url ? <VoiceBubble url={m.voice_url} /> : <ChatMessageBody text={m.text} />}
                </div>
              );
            })}
            <div ref={endRef} />
          </div>

          {readOnly ? (
            <div className="chat__readonly">
              {appText(
                "Доставка закрыта — переписка осталась только для чтения.",
                "Илтеү ябылған — чат тик уҡыу өсөн."
              )}
            </div>
          ) : (
            <>
              <ParcelQuickReplies onPick={(t) => void sendText(t)} />
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
