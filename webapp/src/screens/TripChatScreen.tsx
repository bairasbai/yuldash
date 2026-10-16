// ================================================================
//  Чат поездки на отдельной странице: /trip/:id/chat (зеркало Android Screen.TripChat).
//
//  Жалоба, с которой всё началось: строка ввода стояла посреди экрана. Чат был куском
//  длинной страницы поездки — при двух сообщениях поле висело сразу под ними, при сорока
//  уезжало за край. Здесь, как в мессенджерах: лента на всю высоту прижата к низу
//  (новое — внизу), строка ввода прибита к нижнему краю и поднимается вместе с экранной
//  клавиатурой (visualViewport), отступ снизу один — safe-area, без двойных.
//
//  Логика чата перенесена из ActiveTripScreen 1:1 (WS → REST, очередь без сети, правка и
//  удаление, голос, фото, быстрые фразы, дисклеймер безопасности). Новое здесь только
//  каркас: шапка, лента с «к новым», состояния загрузки/пусто/ошибка, «соединение
//  восстанавливается» и непрочитанные (utils/tripChat.js).
// ================================================================
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { getSessionGeneration } from "../api/client";
import { fetchBookingDetails, type BookingDetails } from "../api/bookings";
import {
  fetchMessages,
  sendMessageRest,
  openBookingChat,
  editMessage,
  deleteMessage,
  type ChatMessage,
} from "../api/chat";
import { ChatFlagPlate, ChatSafetyDisclaimer } from "../components/ChatSafety";
import QuickReplies from "../components/QuickReplies";
import { ChatPhotoButton, ChatMessageBody } from "../components/ChatPhoto";
import { ChatVoiceButton, VoiceBubble } from "../components/ChatVoice";
import { IconArrow, IconCar, IconChevron, IconWarn } from "../components/Icons";
import { acknowledgeMessage, createOutboxId, enqueue, isPermanentRejection, outboxCount, subscribeOutbox, watchOutbox } from "../utils/outbox";
import {
  NO_MARK,
  getSeen,
  isNearBottom,
  newestId,
  setSeen,
  unreadCount,
  viewportBox,
} from "../utils/tripChat.js";

/** «1 сообщение ждёт» / «2 сообщения ждут» / «5 сообщений ждут». */
function queuedWordRu(n: number): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return "сообщение ждёт";
  if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return "сообщения ждут";
  return "сообщений ждут";
}

/**
 * Рамка чата = то, что человек реально видит. Когда на телефоне выезжает клавиатура,
 * visualViewport становится ниже окна: рамка садится ровно на видимую часть, и строка
 * ввода оказывается над клавиатурой, а не под ней. `data-kb` — клавиатура открыта
 * (тогда отступ под «домой»-полоску не нужен: её закрывает клавиатура).
 * Пишем прямо в элемент, а не в state: на iOS visualViewport шлёт события при каждом
 * движении пальца, перерисовывать на это весь чат незачем.
 */
function useViewportFrame(ref: RefObject<HTMLElement>) {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const vv = window.visualViewport;
    const apply = () => {
      // Щипок-зум меняет высоту visualViewport — это не клавиатура, рамку не трогаем.
      if (!vv || vv.scale > 1.01) {
        el.style.removeProperty("--tchat-top");
        el.style.removeProperty("--tchat-h");
        el.dataset.kb = "0";
        return;
      }
      const box = viewportBox(window.innerHeight, vv.height, vv.offsetTop);
      el.style.setProperty("--tchat-top", `${box.top}px`);
      el.style.setProperty("--tchat-h", `${box.height}px`);
      el.dataset.kb = box.keyboardOpen ? "1" : "0";
    };
    apply();
    vv?.addEventListener("resize", apply);
    vv?.addEventListener("scroll", apply);
    window.addEventListener("resize", apply);
    return () => {
      vv?.removeEventListener("resize", apply);
      vv?.removeEventListener("scroll", apply);
      window.removeEventListener("resize", apply);
    };
  }, [ref]);
}

/** Страница: адрес, вход, шапка из данных поездки, «Назад». Сам чат — в TripChat ниже. */
export default function TripChatScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();
  const { id } = useParams();
  const bookingId = Number(id);

  // ---- Шапка: собеседник и маршрут (один запрос, ответ позднего экрана отбрасываем) ----
  const [details, setDetails] = useState<BookingDetails | null>(null);
  useEffect(() => {
    if (!bookingId) return;
    const ac = new AbortController();
    setDetails(null);
    fetchBookingDetails(bookingId, ac.signal)
      .then(setDetails)
      .catch(() => {
        /* шапка без имени — не беда: чат от неё не зависит */
      });
    return () => ac.abort();
  }, [bookingId]);

  const peer =
    details == null
      ? ""
      : details.role === "driver"
        ? appText("Пассажир", "Юлсы")
        : details.driver_name || appText("Водитель", "Йөрөтөүсе");
  const route = details ? `${details.from_city} → ${details.to_city}` : "";

  // «Назад»: пришли изнутри приложения — на шаг назад (поездка или список чатов);
  // открыли ссылкой из пуша — истории нет, ведём на страницу поездки.
  const cameFromApp = location.key !== "default";
  const fromTrip = (location.state as { fromTrip?: boolean } | null)?.fromTrip === true;
  function goBack() {
    if (cameFromApp) navigate(-1);
    else navigate(`/trip/${bookingId}`, { replace: true });
  }
  function openTrip() {
    // Из поездки пришли — возвращаемся в ту же запись, а не плодим круг «поездка ↔ чат».
    if (fromTrip && cameFromApp) navigate(-1);
    else navigate(`/trip/${bookingId}`, { replace: true });
  }

  return (
    <TripChat
      key={`${getSessionGeneration()}:${bookingId}`}
      bookingId={bookingId}
      myId={user?.id ?? -1}
      peer={peer}
      route={route}
      onBack={goBack}
      onOpenTrip={openTrip}
      onSos={() => navigate("/sos")}
    />
  );
}

interface TripChatProps {
  bookingId: number;
  myId: number;
  peer?: string;
  route?: string;
  onBack?: () => void;
  onOpenTrip?: () => void;
  onSos?: () => void;
}

/** Сам чат: рамка, шапка, лента, низ со строкой ввода и вся логика переписки. */
function TripChat({ bookingId, myId, peer = "", route = "", onBack, onOpenTrip, onSos }: TripChatProps) {
  const { appText } = useLang();
  const peerInitial = (Array.from(peer.trim())[0] ?? "").toUpperCase();

  const frameRef = useRef<HTMLDivElement | null>(null);
  useViewportFrame(frameRef);

  // Страница чата — отдельный экран: то, что под ней, не должно ползти от прокрутки пальцем.
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  // ---- Состояние чата (перенесено из ActiveTripScreen) ----
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState("");
  const [textBusy, setTextBusy] = useState(false);
  const textSending = useRef(false);
  const ownerSession = useRef(getSessionGeneration()).current;
  type Intent = { id: string; text: string; persisted: boolean; confirmed?: ChatMessage; acknowledgment: Promise<ChatMessage>; confirm: (m: ChatMessage) => void };
  const intentRef = useRef<Intent | null>(null);
  const [live, setLive] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const chatRef = useRef<ReturnType<typeof openBookingChat> | null>(null);

  /** Правка/удаление: id сообщения, которое сейчас в работе. */
  const [menuId, setMenuId] = useState<number | null>(null);
  const [editId, setEditId] = useState<number | null>(null);
  const [editText, setEditText] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);

  /** Сколько сообщений ждёт сети (плашка «отправим, когда появится связь»). */
  const [queued, setQueued] = useState(() => outboxCount(bookingId));
  /** Вложение (голосовое или фото) не ушло — говорим об этом человеку. */
  const [attachNote, setAttachNote] = useState("");

  const upsert = useCallback((m: ChatMessage) => {
    if (ownerSession !== getSessionGeneration()) return;
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
  }, []);

  /** Сервер вернул обновлённое сообщение — подменяем его на месте, не дёргая всю историю. */
  const replace = useCallback((m: ChatMessage) => {
    setMessages((prev) => prev.map((x) => (x.id === m.id ? m : x)));
  }, []);

  const loadHistory = useCallback(() => {
    if (!bookingId || ownerSession !== getSessionGeneration()) return () => {};
    let alive = true;
    setLoadError(false);
    fetchMessages(bookingId)
      .then((list) => {
        if (!alive || ownerSession !== getSessionGeneration()) return;
        setMessages(list);
        setLoaded(true);
      })
      .catch(() => {
        if (!alive || ownerSession !== getSessionGeneration()) return;
        setLoadError(true);
        setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, [bookingId, ownerSession]);

  // История + WS.
  useEffect(() => {
    if (!bookingId || ownerSession !== getSessionGeneration()) return;
    setMessages([]);
    setLoaded(false);
    setLive(false);
    const stopHistory = loadHistory();
    const chat = openBookingChat(bookingId, {
      senderId: myId,
      onAcknowledged: (message) => {
        if (ownerSession !== getSessionGeneration() || !message.request_id) return;
        const intent = intentRef.current;
        if (intent?.id === message.request_id) { intent.confirmed = message; intent.confirm(message); }
        // A late ACK can arrive during REST fallback. Cleanup failure never changes delivery success.
        void acknowledgeMessage(bookingId, message.request_id, ownerSession).catch(() => {});
      },
      onMessage: upsert,
      onOpen: () => setLive(true),
      onClose: () => setLive(false),
      onError: () => setLive(false),
    });
    chatRef.current = chat;
    return () => {
      stopHistory();
      chat.close();
      chatRef.current = null;
    };
  }, [bookingId, myId, ownerSession, upsert, loadHistory]);

  // Сеть вернулась → досылаем накопленное и забираем историю с сервера (она авторитетная).
  useEffect(() => {
    if (!bookingId || ownerSession !== getSessionGeneration()) return;
    const stop = watchOutbox(() => {
      if (ownerSession !== getSessionGeneration()) return;
      setQueued(outboxCount(bookingId));
      fetchMessages(bookingId)
        .then((list) => {
          if (ownerSession !== getSessionGeneration()) return;
          setMessages(list);
          setLoadError(false);
        })
        .catch(() => {});
    });
    const unsub = subscribeOutbox(() => { if (ownerSession === getSessionGeneration()) setQueued(outboxCount(bookingId)); });
    return () => {
      stop();
      unsub();
    };
  }, [bookingId]);

  // Поллинг-фолбэк, когда живого WS нет.
  useEffect(() => {
    if (live || !loaded || ownerSession !== getSessionGeneration()) return;
    const iv = window.setInterval(() => {
      fetchMessages(bookingId)
        .then((list) => {
          if (ownerSession !== getSessionGeneration()) return;
          setMessages(list);
          setLoadError(false);
        })
        .catch(() => {});
    }, 7000);
    return () => window.clearInterval(iv);
  }, [live, loaded, bookingId]);

  // «Соединение восстанавливается»: показываем, если живой канал молчит дольше пары секунд
  // (пока сокет только подключается — не пугаем).
  const [reconnecting, setReconnecting] = useState(false);
  useEffect(() => {
    if (live || !loaded) {
      setReconnecting(false);
      return;
    }
    const t = window.setTimeout(() => setReconnecting(true), 2000);
    return () => window.clearTimeout(t);
  }, [live, loaded]);

  // ---- Лента: прижата к низу, открывается на последнем, не дёргает при чтении старого ----
  const feedRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true); // человек у нижнего края → новое подтягивает ленту
  const [atBottom, setAtBottom] = useState(true);
  const firstScrollDone = useRef(false);

  const scrollToEnd = useCallback((smooth: boolean) => {
    const el = feedRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  }, []);

  const onFeedScroll = useCallback(() => {
    const el = feedRef.current;
    if (!el) return;
    const near = isNearBottom(el.scrollTop, el.clientHeight, el.scrollHeight);
    stickRef.current = near;
    setAtBottom((prev) => (prev === near ? prev : near));
  }, []);

  useEffect(() => {
    firstScrollDone.current = false;
    stickRef.current = true;
    setAtBottom(true);
  }, [bookingId]);

  useLayoutEffect(() => {
    if (!loaded) return;
    if (!firstScrollDone.current) {
      // Первый показ — сразу на последнем сообщении, без анимации.
      firstScrollDone.current = true;
      stickRef.current = true;
      scrollToEnd(false);
      return;
    }
    const last = messages[messages.length - 1];
    // Своё сообщение показываем всегда; чужое — только если человек и так внизу.
    if (stickRef.current || (last && last.sender_id === myId)) {
      stickRef.current = true;
      scrollToEnd(false);
    }
  }, [messages, loaded, myId, scrollToEnd]);

  // Клавиатура выехала, фото догрузилось — высота ленты/списка поменялась: если человек
  // внизу, остаёмся внизу.
  useEffect(() => {
    const feed = feedRef.current;
    const list = listRef.current;
    if (!feed || !list || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      if (stickRef.current) scrollToEnd(false);
    });
    ro.observe(feed);
    ro.observe(list);
    return () => ro.disconnect();
  }, [scrollToEnd]);

  // ---- Непрочитанные: метка живёт в памяти и привязана ко входу (utils/tripChat.js) ----
  const generation = getSessionGeneration();
  const [seenId, setSeenId] = useState<number>(() => getSeen(generation, bookingId));
  const [pageVisible, setPageVisible] = useState(true);
  useEffect(() => {
    const onVis = () => setPageVisible(document.visibilityState === "visible");
    onVis();
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);
  useEffect(() => {
    setSeenId(getSeen(generation, bookingId));
  }, [generation, bookingId]);
  useEffect(() => {
    if (!loaded || loadError) return;
    const top = newestId(messages);
    if (seenId === NO_MARK) {
      // Первый показ: уже загруженная история считается прочитанной — лучше не показать
      // бейдж, чем «12 новых» на переписку, которую человек давно прочёл.
      setSeenId(setSeen(generation, bookingId, top));
      return;
    }
    // Прочитано — только то, что человек правда видит: лента внизу, страница на экране.
    if (atBottom && pageVisible && top > seenId) {
      setSeenId(setSeen(generation, bookingId, top));
    }
  }, [loaded, loadError, messages, atBottom, pageVisible, seenId, generation, bookingId]);
  const unread = unreadCount(messages, seenId, myId);

  // ---- Отправка ----
  // Общая отправка (поле ввода и быстрые ответы): живой сокет, фолбэк — REST.
  async function sendText(t: string): Promise<boolean> {
    if (!t || textSending.current || ownerSession !== getSessionGeneration()) return false;
    const currentSession = () => ownerSession === getSessionGeneration();
    textSending.current = true;
    setTextBusy(true);
    setAttachNote("");
    // A manual retry of the rejected draft keeps its original identity. Editing creates a new intent.
    let intent = intentRef.current;
    if (!intent || intent.text !== t) {
      let confirm!: (m: ChatMessage) => void;
      const acknowledgment = new Promise<ChatMessage>(resolve => { confirm = resolve; });
      intent = { id: createOutboxId(), text: t, persisted: false, acknowledgment, confirm };
      intentRef.current = intent;
    }
    try {
      if (!intent.persisted) {
        try { await enqueue(bookingId, "message", t, intent.id); }
        catch {
          if (!currentSession()) return false;
          setText(current => current || t);
          setAttachNote(appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер."));
          return false;
        }
        if (!currentSession()) return false;
        intent.persisted = true;
      }
      try {
        const liveMessage = await chatRef.current?.send(t, intent.id);
        if (!currentSession()) return false;
        const message = liveMessage ?? intent.confirmed ?? await Promise.race([
          sendMessageRest(bookingId, t, undefined, intent.id), intent.acknowledgment,
        ]);
        if (!currentSession()) return false;
        upsert(message);
        intent.confirmed = message;
        // The server already committed: a failed local removal must not restore text/new UUID.
        await acknowledgeMessage(bookingId, intent.id, ownerSession).catch(() => {});
        if (!currentSession()) return false;
        if (intentRef.current === intent) intentRef.current = null;
        return true;
      } catch (error) {
        if (!currentSession()) return false;
        if (intent.confirmed) {
          if (intentRef.current === intent) intentRef.current = null;
          return true;
        }
        if (isPermanentRejection(error)) {
          // Preserve the existing rejection policy; same-screen manual retry retains this UUID.
          try { await acknowledgeMessage(bookingId, intent.id, ownerSession); intent.persisted = false; }
          catch { /* Same durable key remains if cleanup cannot commit. */ }
          if (!currentSession()) return false;
          setText(current => current || t);
          setAttachNote(appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер."));
          return false;
        }
        // No confirmation (network, timeout, 429 or 5xx): the saved action survives restart.
        setQueued(outboxCount(bookingId));
        if (intentRef.current === intent) intentRef.current = null;
        return true;
      }
    } finally {
      textSending.current = false;
      if (currentSession()) setTextBusy(false);
    }
  }

  /** Голос уходит по REST: сокет передаёт только текст, а ссылку надо положить в поле. */
  async function sendVoice(voiceUrl: string) {
    try {
      const m = await sendMessageRest(bookingId, "", voiceUrl);
      upsert(m);
      setAttachNote("");
    } catch {
      // Раньше голосовое пропадало молча: человек говорил в трубку, отпускал —
      // и не знал, что записи никто не получил. В очередь его не положить
      // (запись живёт на сервере ограниченно), поэтому честно просим повторить.
      setAttachNote(
        appText("Голосовое не ушло. Запиши ещё раз.", "Тауыш китмәне. Тағы яҙып ҡара.")
      );
    }
  }

  /** Сохранить правку своего сообщения. Пустой текст = отмена, а не «стереть текст». */
  async function saveEdit(msgId: number) {
    const t = editText.trim();
    if (!t || busyId) {
      setEditId(null);
      return;
    }
    setBusyId(msgId);
    try {
      replace(await editMessage(bookingId, msgId, t));
      setEditId(null);
    } catch {
      // Поле остаётся открытым — текст не потерян. Но человек не знает, что
      // правка не ушла: он видит своё новое сообщение и думает, что сохранил.
      setAttachNote(
        appText("Правка не сохранилась. Попробуй ещё раз.", "Төҙәтеү һаҡланманы. Тағы ҡабатла.")
      );
    } finally {
      setBusyId(null);
    }
  }

  /** Удалить: у всех (только своё) или скрыть у себя. */
  async function removeMessage(msgId: number, scope: "all" | "me") {
    if (busyId) return;
    setMenuId(null);
    setBusyId(msgId);
    try {
      const m = await deleteMessage(bookingId, msgId, scope);
      // «У себя» сервер не возвращает пустой текст — просто убираем из своего списка.
      if (scope === "me") setMessages((prev) => prev.filter((x) => x.id !== msgId));
      else replace(m);
    } catch {
      // Сообщение осталось на месте — но человек нажал «удалить» и ждёт,
      // что оно исчезнет. Молчание он прочитает как «кнопка не работает».
      setAttachNote(
        appText("Не получилось удалить. Попробуй ещё раз.", "Юйып булманы. Тағы ҡабатла.")
      );
    } finally {
      setBusyId(null);
    }
  }

  async function send() {
    const t = text.trim();
    if (!t) return;
    const sessionAtStart = getSessionGeneration();
    if (await sendText(t)) {
      if (sessionAtStart === getSessionGeneration()) setText((current) => (current.trim() === t ? "" : current));
    }
  }

  const showJump = !atBottom && messages.length > 0;

  return (
    <div className="tchat" ref={frameRef} data-kb="0">
      {/* Шапка: назад, собеседник и маршрут, к поездке, SOS */}
      <header className="tchat__head">
        <div className="tchat__head-in">
          <button
            type="button"
            className="tchat__iconbtn tchat__back"
            onClick={onBack}
            aria-label={appText("Назад", "Артҡа")}
          >
            <span className="tchat__flip">
              <IconChevron size={22} />
            </span>
          </button>
          {peerInitial && (
            <span className="tchat__avatar" aria-hidden>
              {peerInitial}
            </span>
          )}
          <div className="tchat__who">
            <h1>{peer || appText("Чат по поездке", "Сәфәр буйынса чат")}</h1>
            {route && <p>{route}</p>}
          </div>
          <button
            type="button"
            className="tchat__iconbtn"
            onClick={onOpenTrip}
            aria-label={appText("Открыть поездку", "Сәфәрҙе асыу")}
          >
            <IconCar size={22} />
          </button>
          <button
            type="button"
            className="tchat__iconbtn tchat__sos"
            onClick={onSos}
            aria-label={appText("Экстренный вызов", "Ашығыс саҡырыу")}
          >
            <IconWarn size={22} />
          </button>
        </div>
      </header>

      {/* Лента на всю высоту, прижата к низу */}
      <div className="tchat__main">
        <div className="tchat__feed" ref={feedRef} onScroll={onFeedScroll}>
          <div className="tchat__list" ref={listRef}>
            {!loaded && (
              <div className="chat__loading" aria-live="polite">
                <span className="skeleton chat__skeleton" />
                <span className="skeleton chat__skeleton chat__skeleton--mine" />
                <span className="skeleton chat__skeleton" />
              </div>
            )}
            {loaded && loadError && messages.length === 0 && (
              <div className="chat__empty" role="alert">
                <p>{appText("Не удалось загрузить чат.", "Чатты йөкләп булманы.")}</p>
                <button
                  type="button"
                  className="btn-soft tchat__retry"
                  onClick={() => {
                    setLoaded(false);
                    loadHistory();
                  }}
                >
                  {appText("Повторить", "Ҡабатлау")}
                </button>
              </div>
            )}
            {loaded && !loadError && messages.length === 0 && (
              <p className="chat__empty">
                {appText("Напиши первым — обсудите детали встречи.", "Беренсе булып яҙ — осрашыуҙы һөйләшегеҙ.")}
              </p>
            )}
            {messages.map((m) => {
              const mine = m.sender_id === myId;
              // Своё текстовое можно поправить, удалить у всех или скрыть у себя.
              // Чужое — только скрыть у себя: чужие слова не наши.
              const canEdit = mine && !m.deleted && !m.voice_url;
              if (m.deleted) {
                return (
                  <div key={m.id} className={"bubble bubble--gone" + (mine ? " bubble--mine" : "")}>
                    {appText("Сообщение удалено", "Хәбәр юйылды")}
                  </div>
                );
              }
              return (
                <div key={m.id} className={"msg" + (mine ? " msg--mine" : "")}>
                  <div className={"bubble" + (mine ? " bubble--mine" : "")}>
                    {m.from_admin && <span className="bubble__admin">Юлдаш ✓</span>}

                    {editId === m.id ? (
                      <div className="bubble__edit">
                        <input
                          value={editText}
                          autoFocus
                          onChange={(e) => setEditText(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") void saveEdit(m.id);
                            if (e.key === "Escape") setEditId(null);
                          }}
                          aria-label={appText("Поправить сообщение", "Хәбәрҙе төҙәтеү")}
                        />
                        <button type="button" onClick={() => void saveEdit(m.id)} disabled={busyId === m.id}>
                          {appText("Сохранить", "Һаҡлау")}
                        </button>
                        <button type="button" onClick={() => setEditId(null)}>
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    ) : (
                      <>
                        {m.voice_url ? <VoiceBubble url={m.voice_url} /> : <ChatMessageBody text={m.text} />}
                        {m.edited && (
                          <span className="bubble__edited">{appText("изменено", "төҙәтелгән")}</span>
                        )}
                        <button
                          type="button"
                          className="bubble__more"
                          aria-label={appText("Действия с сообщением", "Хәбәр менән эштәр")}
                          onClick={() => setMenuId(menuId === m.id ? null : m.id)}
                        >
                          ⋯
                        </button>
                      </>
                    )}

                    {menuId === m.id && (
                      <div className="bubble__menu" role="menu">
                        {canEdit && (
                          <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                              setEditText(m.text);
                              setEditId(m.id);
                              setMenuId(null);
                            }}
                          >
                            {appText("Поправить", "Төҙәтергә")}
                          </button>
                        )}
                        {mine && (
                          <button
                            type="button"
                            role="menuitem"
                            className="bubble__menu-danger"
                            onClick={() => void removeMessage(m.id, "all")}
                          >
                            {appText("Удалить у всех", "Барыһынан юйырға")}
                          </button>
                        )}
                        <button type="button" role="menuitem" onClick={() => void removeMessage(m.id, "me")}>
                          {appText("Скрыть у себя", "Үҙемдән йәшерергә")}
                        </button>
                      </div>
                    )}
                  </div>
                  <ChatFlagPlate flag={m.flag} mine={mine} />
                </div>
              );
            })}
          </div>
        </div>

        {/* «К новым»: листаешь старое — лента не дёргается, а кнопка с числом ведёт вниз */}
        {showJump && (
          <button
            type="button"
            className="tchat__jump"
            onClick={() => {
              stickRef.current = true;
              scrollToEnd(true);
            }}
            aria-label={
              unread > 0
                ? appText(`К новым сообщениям: ${unread}`, `Яңы хәбәрҙәргә: ${unread}`)
                : appText("К новым сообщениям", "Яңы хәбәрҙәргә")
            }
          >
            <span className="tchat__jump-ico" aria-hidden>
              <IconChevron size={22} />
            </span>
            {unread > 0 && (
              <span className="tchat__badge" aria-hidden>
                {unread > 99 ? "99+" : unread}
              </span>
            )}
          </button>
        )}
      </div>

      {/* Низ: всегда у нижнего края экрана */}
      <div className="tchat__foot">
        <div className="tchat__foot-in">
          <ChatSafetyDisclaimer />
          {reconnecting && (
            <div className="chat__queued tchat__strip" role="status">
              {appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…")}
            </div>
          )}
          {/* Написал на трассе без связи — сообщение не пропало, ждёт сети */}
          {queued > 0 && (
            <div className="chat__queued tchat__strip" role="status">
              {appText(
                `${queued} ${queuedWordRu(queued)} сети — отправим сами`,
                `${queued} хат селтәрҙе көтә — үҙебеҙ ебәрербеҙ`
              )}
            </div>
          )}
          {attachNote && (
            <div className="chat__queued tchat__strip" role="status">
              {attachNote}
            </div>
          )}
          {/* Быстрые ответы — один тап отправляет готовую фразу */}
          <QuickReplies onPick={(t) => void sendText(t)} />
          <div className="chat__input tchat__input">
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") void send();
              }}
              placeholder={appText("Сообщение…", "Хат…")}
              maxLength={4000}
              enterKeyHint="send"
              aria-label={appText("Сообщение", "Хәбәр")}
            />
            <ChatPhotoButton onReady={(t) => void sendText(t)} onProblem={setAttachNote} />
            <ChatVoiceButton onSend={(u) => void sendVoice(u)} onProblem={setAttachNote} />
            <button type="button" onClick={() => void send()} disabled={textBusy} aria-label={appText("Отправить", "Ебәреү")}>
              <IconArrow size={20} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
