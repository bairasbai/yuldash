// ================================================================
//  Карточка «Чат по поездке» на странице поездки (зеркало Android TripChatCard).
//
//  Сама переписка живёт на отдельной странице /trip/:id/chat. Здесь — только вход в неё:
//  последнее сообщение и бейдж «новых». Карточка и кнопка «Написать» открывают чат.
//
//  Новые считаем локально (utils/tripChat.js): сервер «прочитано» не хранит. Первый показ —
//  вся загруженная история уже прочитана, бейдж не пугает числом на старую переписку.
//  Свежее подтягиваем редким опросом, пока экран виден, — сокет карточке не нужен.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { getSessionGeneration } from "../api/client";
import { fetchMessages, IMG_PREFIX, type ChatMessage } from "../api/chat";
import { YuChat } from "./BrandIcons";
import { IconChevron } from "./Icons";
import { useVisibleInterval } from "../utils/useVisibleInterval";
import { NO_MARK, getSeen, lastVisible, newestId, previewKind, setSeen, unreadCount } from "../utils/tripChat.js";

export default function TripChatCard({
  bookingId,
  myId,
  onOpen,
}: {
  bookingId: number;
  myId: number;
  onOpen: () => void;
}) {
  const { appText } = useLang();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [state, setState] = useState<"loading" | "error" | "ready">("loading");
  const generation = getSessionGeneration();
  const [seenId, setSeenId] = useState<number>(() => getSeen(generation, bookingId));

  const refresh = useCallback(() => {
    if (!bookingId) return;
    fetchMessages(bookingId)
      .then((list) => {
        setMessages(list);
        setState("ready");
        // Первый показ: уже загруженная история считается прочитанной.
        if (getSeen(generation, bookingId) === NO_MARK) {
          setSeenId(setSeen(generation, bookingId, newestId(list)));
        }
      })
      .catch(() => {
        // Старое превью лучше пустого места: ошибку показываем, только если показывать нечего.
        setState((prev) => (prev === "ready" ? prev : "error"));
      });
  }, [bookingId, generation]);

  useEffect(() => {
    setMessages([]);
    setState("loading");
    setSeenId(getSeen(generation, bookingId));
    refresh();
  }, [bookingId, generation, refresh]);

  useVisibleInterval(10000, refresh, !!bookingId);

  const unread = unreadCount(messages, seenId, myId);
  const last = lastVisible(messages);
  const prev = previewKind(last, IMG_PREFIX);

  let preview: string;
  if (state === "loading") preview = appText("Загружаем переписку…", "Хәбәрҙәрҙе йөкләйбеҙ…");
  else if (state === "error") preview = appText("Не удалось загрузить чат", "Чатты йөкләп булманы");
  else if (prev.kind === "none") preview = appText("Пока нет сообщений. Напиши первым", "Әлегә хәбәрҙәр юҡ. Беренсе булып яҙ");
  else {
    const body =
      prev.kind === "deleted"
        ? appText("Сообщение удалено", "Хәбәр юйылды")
        : prev.kind === "voice"
          ? appText("🎤 Голосовое", "🎤 Тауыш")
          : prev.kind === "photo"
            ? appText("📷 Фото", "📷 Фотоһүрәт")
            : prev.text;
    preview = last && last.sender_id === myId ? `${appText("Ты", "Һин")}: ${body}` : body;
  }

  return (
    <div className="tchat-card">
      <button
        type="button"
        className="tchat-card__main"
        onClick={onOpen}
        aria-label={
          unread > 0
            ? appText(`Чат по поездке, новых сообщений: ${unread}`, `Сәфәр буйынса чат, яңы хәбәрҙәр: ${unread}`)
            : appText("Чат по поездке", "Сәфәр буйынса чат")
        }
      >
        <span className="tchat-card__icon" aria-hidden>
          <YuChat size={26} />
        </span>
        <span className="tchat-card__text">
          <span className="tchat-card__title">{appText("Чат по поездке", "Сәфәр буйынса чат")}</span>
          <span className={"tchat-card__preview" + (unread > 0 ? " is-unread" : "")}>{preview}</span>
        </span>
        {unread > 0 && (
          <span className="tchat__badge" aria-hidden>
            {unread > 99 ? "99+" : unread}
          </span>
        )}
        <span className="tchat-card__go" aria-hidden>
          <IconChevron size={20} />
        </span>
      </button>
      <button type="button" className="btn-soft tchat-card__write" onClick={onOpen}>
        {appText("Написать", "Яҙыу")}
      </button>
    </div>
  );
}
