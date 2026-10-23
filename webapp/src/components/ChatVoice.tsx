// ================================================================
//  Голос в чате: записать и послушать. Зеркало Android
//  (BookingActiveTripScreen.kt — VoiceBubble + запись).
//
//  Для кого это: человеку, которому тяжело печатать (пожилой,
//  за рулём, руки заняты), голос — единственный удобный способ
//  ответить. В приложении он есть, на сайте была только клавиатура.
//
//  Файл грузим на наш сервер (POST /voice) и шлём сообщением с
//  voice_url — сервер принимает ТОЛЬКО свои медиа-ссылки, чужие
//  отклоняет: иначе собеседнику можно было подсунуть чужой адрес.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { uploadVoice } from "../api/voice";
import { getSessionGeneration } from "../api/client";
import { IconMic, IconCheck } from "./Icons";

/** Умеет ли браузер записывать звук. Safari до 14.1 и старый Android — нет. */
export function supportsVoiceRecording(): boolean {
  return (
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof window.MediaRecorder !== "undefined"
  );
}

type Rec = "idle" | "starting" | "recording" | "sending";
type Recording = {
  owner: string; bookingId?: number; phase: Rec; stream: MediaStream | null;
  recorder: MediaRecorder | null; chunks: Blob[]; timer: number | null; abort: AbortController;
};

/** Кнопка записи: нажал — говоришь, нажал ещё — ушло. Без «удержания»: с ним промахиваются. */
export function ChatVoiceButton({
  onSend,
  onProblem,
  bookingId,
  disabled = false,
}: {
  onSend: (voiceUrl: string) => void | Promise<void>;
  /** Экран покажет это человеку: молчащая кнопка выглядит поломкой. */
  onProblem?: (message: string) => void;
  bookingId?: number;
  disabled?: boolean;
}) {
  const { appText } = useLang();
  const [rec, setRec] = useState<Rec>("idle");
  const [seconds, setSeconds] = useState(0);
  const owner = useRef(getSessionGeneration()).current;
  const mounted = useRef(true);
  const target = useRef(bookingId);
  const blocked = useRef(disabled);
  target.current = bookingId;
  blocked.current = disabled;
  const recording = useRef<Recording | null>(null);

  function current(intent: Recording): boolean {
    return mounted.current && recording.current === intent && intent.bookingId === target.current &&
      intent.owner === getSessionGeneration();
  }

  function release(intent: Recording) {
    if (intent.timer != null) window.clearInterval(intent.timer);
    intent.timer = null;
    // Уход не отправляет запись: обработчики отключаем ДО stop/остановки дорожек.
    if (intent.recorder) {
      intent.recorder.onstop = null;
      intent.recorder.ondataavailable = null;
      if (intent.recorder.state !== "inactive") intent.recorder.stop();
    }
    const stream = intent.stream;
    intent.stream = null;
    stream?.getTracks().forEach(track => track.stop());
    intent.abort.abort();
  }

  function finish(intent: Recording) {
    const active = current(intent);
    release(intent);
    if (recording.current === intent) recording.current = null;
    if (active) setRec("idle");
  }

  useEffect(() => {
    mounted.current = true;
    setRec("idle");
    return () => {
      mounted.current = false;
      const intent = recording.current;
      recording.current = null;
      if (intent) release(intent);
    };
  }, [bookingId]);

  if (!supportsVoiceRecording()) return null;

  async function start() {
    if (blocked.current || recording.current || !mounted.current || bookingId !== target.current || owner !== getSessionGeneration()) return;
    const intent: Recording = { owner, bookingId, phase: "starting", stream: null, recorder: null,
      chunks: [], timer: null, abort: new AbortController() };
    recording.current = intent; // Синхронный замок до окна разрешения микрофона.
    setRec("starting");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      intent.stream = stream;
      if (!current(intent) || blocked.current) { finish(intent); return; }
      const mr = new MediaRecorder(stream);
      intent.recorder = mr;
      mr.ondataavailable = (e) => {
        if (current(intent) && e.data.size > 0) intent.chunks.push(e.data);
      };
      mr.onstop = async () => {
        if (!current(intent) || blocked.current) { finish(intent); return; }
        mr.onstop = null;
        mr.ondataavailable = null;
        const source = intent.stream;
        intent.stream = null;
        source?.getTracks().forEach(track => track.stop());
        if (intent.timer != null) window.clearInterval(intent.timer);
        intent.timer = null;
        const blob = new Blob(intent.chunks, { type: mr.mimeType || "audio/webm" });
        // Совсем короткие нажатия — это промах по кнопке, а не сообщение.
        if (blob.size < 1200) {
          finish(intent);
          return;
        }
        setRec("sending");
        intent.phase = "sending";
        try {
          const mime = blob.type.split(";", 1)[0].trim().toLowerCase();
          const extension = ({ "audio/mp4": "m4a", "audio/ogg": "ogg", "audio/wav": "wav",
            "audio/mpeg": "mp3", "audio/webm": "webm" } as Record<string, string>)[mime] ?? "webm";
          const { url } = await uploadVoice(blob, `voice.${extension}`, intent.abort.signal, intent.owner);
          if (!current(intent)) return;
          if (!url) throw new Error("Missing uploaded voice URL");
          await onSend(url);
        } catch {
          if (current(intent)) onProblem?.(appText(
            "Не получилось загрузить запись. Проверь сеть и запиши ещё раз.",
            "Яҙманы тейәп булманы. Селтәрҙе тикшереп, тағы яҙып ҡара.",
          ));
        } finally {
          finish(intent);
        }
      };
      mr.start();
      intent.phase = "recording";
      setSeconds(0);
      setRec("recording");
      onProblem?.("");
      intent.timer = window.setInterval(() => { if (current(intent)) setSeconds((s) => s + 1); }, 1000);
    } catch {
      // Раньше кнопка просто ничего не делала: человек жал, тишина, и понять,
      // что телефон запретил микрофон, было неоткуда.
      if (current(intent)) onProblem?.(
        appText(
          "Нет доступа к микрофону. Разреши его в настройках браузера — тогда сможешь записывать голосовые.",
          "Микрофонға рөхсәт юҡ. Браузер көйләүҙәрендә рөхсәт бир — шунда тауыш яҙа алырһың."
        )
      );
      finish(intent);
    }
  }

  function stop() {
    const intent = recording.current;
    if (!intent || !current(intent) || intent.phase !== "recording") return;
    intent.phase = "sending";
    setRec("sending");
    if (intent.timer != null) window.clearInterval(intent.timer);
    intent.timer = null;
    intent.recorder?.stop();
  }

  return (
    <button
      type="button"
      onClick={() => (rec === "recording" ? stop() : rec === "idle" ? void start() : undefined)}
      disabled={disabled || rec === "starting" || rec === "sending"}
      aria-label={
        rec === "recording"
          ? appText("Остановить и отправить", "Туҡтатып ебәреү")
          : appText("Записать голос", "Тауыш яҙыу")
      }
      className={rec === "recording" ? "chat-voice-btn is-rec" : "chat-voice-btn"}
    >
      {rec === "recording" ? (
        <>
          <IconCheck size={18} />
          <span className="chat-voice-btn__time">
            {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")}
          </span>
        </>
      ) : (
        <IconMic size={20} />
      )}
    </button>
  );
}

/** Пузырь с голосовым: стандартный плеер браузера — он уже знает про паузу и перемотку. */
export function VoiceBubble({ url }: { url: string }) {
  const { appText } = useLang();
  return (
    <audio
      className="chat-voice"
      src={url}
      controls
      preload="none"
      aria-label={appText("Голосовое сообщение", "Тауыш хәбәре")}
    />
  );
}
