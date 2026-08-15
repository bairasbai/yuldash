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
import { IconMic, IconCheck } from "./Icons";

/** Умеет ли браузер записывать звук. Safari до 14.1 и старый Android — нет. */
export function supportsVoiceRecording(): boolean {
  return (
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof window.MediaRecorder !== "undefined"
  );
}

type Rec = "idle" | "recording" | "sending";

/** Кнопка записи: нажал — говоришь, нажал ещё — ушло. Без «удержания»: с ним промахиваются. */
export function ChatVoiceButton({
  onSend,
  onProblem,
}: {
  onSend: (voiceUrl: string) => void;
  /** Экран покажет это человеку: молчащая кнопка выглядит поломкой. */
  onProblem?: (message: string) => void;
}) {
  const { appText } = useLang();
  const [rec, setRec] = useState<Rec>("idle");
  const [seconds, setSeconds] = useState(0);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  if (!supportsVoiceRecording()) return null;

  async function start() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      chunksRef.current = [];
      const mr = new MediaRecorder(stream);
      mr.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      mr.onstop = async () => {
        streamRef.current?.getTracks().forEach((t) => t.stop());
        streamRef.current = null;
        const blob = new Blob(chunksRef.current, { type: mr.mimeType || "audio/webm" });
        // Совсем короткие нажатия — это промах по кнопке, а не сообщение.
        if (blob.size < 1200) {
          setRec("idle");
          return;
        }
        setRec("sending");
        try {
          const { url } = await uploadVoice(blob, "voice.webm");
          if (url) onSend(url);
        } catch {
          // Запись сделана, но не ушла (сеть моргнула). Молчать нельзя: человек
          // уверен, что его услышали.
          onProblem?.(
            appText("Голосовое не ушло. Запиши ещё раз.", "Тауыш китмәне. Тағы яҙып ҡара.")
          );
        } finally {
          setRec("idle");
        }
      };
      recorderRef.current = mr;
      mr.start();
      setSeconds(0);
      setRec("recording");
      onProblem?.("");
      timerRef.current = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    } catch {
      // Раньше кнопка просто ничего не делала: человек жал, тишина, и понять,
      // что телефон запретил микрофон, было неоткуда.
      onProblem?.(
        appText(
          "Нет доступа к микрофону. Разреши его в настройках браузера — тогда сможешь записывать голосовые.",
          "Микрофонға рөхсәт юҡ. Браузер көйләүҙәрендә рөхсәт бир — шунда тауыш яҙа алырһың."
        )
      );
    }
  }

  function stop() {
    if (timerRef.current) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    recorderRef.current?.stop();
    recorderRef.current = null;
  }

  return (
    <button
      type="button"
      onClick={() => (rec === "recording" ? stop() : rec === "idle" ? void start() : undefined)}
      disabled={rec === "sending"}
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
