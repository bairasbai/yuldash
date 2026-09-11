// ================================================================
//  «Голосовая заявка» — надиктовать поездку голосом (для тех, кому
//  печатать тяжело). Запись — браузерный MediaRecorder; аудио грузим
//  в POST /voice → voice_url, затем POST /requests (assisted).
//  Нет микрофона / отказ / неподдержка / сбой загрузки → честный
//  текстовый фолбэк на ту же заявку. RequireAuth.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { uploadVoice } from "../api/voice";
import { createRequest } from "../api/requests";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconMic } from "../components/Icons";

type Rec = "idle" | "recording" | "recorded";
type Send = "idle" | "sending" | "sent" | "error";

function supportsRecording(): boolean {
  return (
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof window !== "undefined" &&
    typeof window.MediaRecorder !== "undefined"
  );
}

function extFor(mime: string): string {
  if (mime.includes("mp4") || mime.includes("aac")) return "m4a";
  if (mime.includes("ogg")) return "ogg";
  if (mime.includes("wav")) return "wav";
  return "webm";
}

export default function VoiceRequestScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [fromCity, setFromCity] = useState("");
  const [toCity, setToCity] = useState("");
  const [comment, setComment] = useState(""); // текстовый фолбэк
  const [textMode, setTextMode] = useState(!supportsRecording());

  const [rec, setRec] = useState<Rec>("idle");
  const [seconds, setSeconds] = useState(0);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);

  const [send, setSend] = useState<Send>("idle");
  const [error, setError] = useState<string | null>(null);

  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const blobRef = useRef<Blob | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<number | null>(null);

  const valid = fromCity.trim() && toCity.trim();

  // Чистим ресурсы при уходе с экрана.
  useEffect(() => {
    return () => {
      stopTimer();
      streamRef.current?.getTracks().forEach((t) => t.stop());
      if (audioUrl) URL.revokeObjectURL(audioUrl);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function stopTimer() {
    if (timerRef.current) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }

  async function startRecording() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      chunksRef.current = [];
      const mr = new MediaRecorder(stream);
      mr.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      mr.onstop = () => {
        const type = mr.mimeType || "audio/webm";
        const blob = new Blob(chunksRef.current, { type });
        blobRef.current = blob;
        if (audioUrl) URL.revokeObjectURL(audioUrl);
        setAudioUrl(URL.createObjectURL(blob));
        setRec("recorded");
        streamRef.current?.getTracks().forEach((t) => t.stop());
        streamRef.current = null;
      };
      recorderRef.current = mr;
      mr.start();
      setSeconds(0);
      setRec("recording");
      timerRef.current = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    } catch {
      // Отказ в доступе / нет устройства → честный текстовый фолбэк.
      setTextMode(true);
      setRec("idle");
    }
  }

  function stopRecording() {
    stopTimer();
    recorderRef.current?.stop();
  }

  function resetRecording() {
    if (audioUrl) URL.revokeObjectURL(audioUrl);
    setAudioUrl(null);
    blobRef.current = null;
    setSeconds(0);
    setRec("idle");
  }

  async function submit() {
    if (!valid || send === "sending") return;
    setSend("sending");
    setError(null);
    try {
      let voiceUrl: string | null = null;
      if (!textMode && blobRef.current) {
        try {
          const type = blobRef.current.type || "audio/webm";
          const up = await uploadVoice(blobRef.current, `voice.${extFor(type)}`);
          voiceUrl = up.url;
        } catch (e) {
          // Эндпоинта загрузки может не быть на проде (404/405) → мягко уходим в текст.
          if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
            voiceUrl = null;
          } else throw e;
        }
      }
      await createRequest({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        voice_url: voiceUrl,
        comment: comment.trim() || (voiceUrl ? appText("Голосовая заявка", "Тауышлы заявка") : ""),
        assisted: true,
      });
      setSend("sent");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { state: { from: "/voice" } });
        return;
      }
      setError(appText("Не получилось отправить. Попробуй снова.", "Ебәреп булманы. Ҡабат ҡара."));
      setSend("error");
    }
  }

  if (send === "sent") {
    return (
      <>
        <SubHeader title={appText("Голосовая заявка", "Тауышлы заявка")} onBack={() => navigate("/profile")} />
        <div className="state state--ok state--big">
          <div className="state__icon" aria-hidden><IconMic size={34} /></div>
          <h2>{appText("Заявка отправлена", "Заявка ебәрелде")}</h2>
          <p>
            {appText(
              "Мы уже подбираем попутку. Если понадобится — перезвоним и уточним детали.",
              "Юлдаш эҙләй башланыҡ. Кәрәк булһа — шылтыратып асыҡлайбыҙ."
            )}
          </p>
          <button type="button" className="btn-primary btn-lg" onClick={() => navigate("/map")}>
            {appText("Хорошо", "Яҡшы")}
          </button>
        </div>
      </>
    );
  }

  const mm = String(Math.floor(seconds / 60)).padStart(2, "0");
  const ss = String(seconds % 60).padStart(2, "0");

  return (
    <>
      <SubHeader
        title={appText("Голосовая заявка", "Тауышлы заявка")}
        subtitle={appText("Скажи, куда нужно — мы поймём", "Ҡайҙа кәрәклеген әйт — беҙ аңлайбыҙ")}
        onBack={() => navigate(-1)}
      />

      <div className="form">
        <label className="field">
          <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
          <input
            className="field__input"
            value={fromCity}
            onChange={(e) => setFromCity(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>
        <label className="field">
          <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
          <input
            className="field__input"
            value={toCity}
            onChange={(e) => setToCity(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>
      </div>

      {!textMode ? (
        <div className="voice-panel">
          {rec === "idle" && (
            <>
              <button type="button" className="rec-btn" onClick={startRecording} aria-label={appText("Записать", "Яҙырға")}>
                <span className="rec-btn__mic" aria-hidden><IconMic size={26} /></span>
              </button>
              <p className="voice-hint">{appText("Нажми и наговори, куда и когда нужно ехать", "Баҫ та ҡайҙа, ҡасан барырға кәрәклеген һөйлә")}</p>
            </>
          )}

          {rec === "recording" && (
            <>
              <button type="button" className="rec-btn is-recording" onClick={stopRecording} aria-label={appText("Остановить", "Туҡтатырға")}>
                <span className="rec-btn__stop" aria-hidden />
              </button>
              <p className="rec-time">{mm}:{ss}</p>
              <p className="voice-hint">{appText("Идёт запись… нажми, чтобы остановить", "Яҙыла… туҡтатыр өсөн баҫ")}</p>
            </>
          )}

          {rec === "recorded" && (
            <>
              {audioUrl && <audio className="voice-audio" src={audioUrl} controls />}
              <div className="voice-actions">
                <button type="button" className="btn-ghost" onClick={resetRecording}>
                  {appText("Записать заново", "Яңынан яҙырға")}
                </button>
              </div>
            </>
          )}

          <button type="button" className="link-btn" onClick={() => setTextMode(true)}>
            {appText("Лучше напишу текстом", "Яҡшыраҡ яҙып ебәрәм")}
          </button>
        </div>
      ) : (
        <div className="form">
          <div className="soft-note">
            {supportsRecording()
              ? appText("Хорошо, опиши поездку словами.", "Яҡшы, сәфәрҙе һүҙ менән яҙ.")
              : appText("Микрофон недоступен — опиши поездку текстом.", "Микрофон юҡ — сәфәрҙе текст менән яҙ.")}
          </div>
          <label className="field">
            <span className="field__label">{appText("Что нужно", "Ни кәрәк")}</span>
            <textarea
              className="field__input field__area"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={3}
              maxLength={2000}
              placeholder={appText("Например: завтра утром в Уфу, из Стерлитамака", "Мәҫәлән: иртәгә иртән Өфөгә, Стәрлетамаҡтан")}
            />
          </label>
          {supportsRecording() && (
            <button type="button" className="link-btn" onClick={() => setTextMode(false)}>
              {appText("Записать голос", "Тауыш менән яҙырға")}
            </button>
          )}
        </div>
      )}

      {error && <div className="auth__error">{error}</div>}

      <button
        type="button"
        className="btn-primary btn-lg submit-btn"
        onClick={submit}
        disabled={!valid || send === "sending" || (!textMode && rec !== "recorded")}
      >
        {send === "sending" ? appText("Отправляем…", "Ебәрәбеҙ…") : (
          <><IconCheck size={20} /> {appText("Отправить заявку", "Заявка ебәрергә")}</>
        )}
      </button>
      {!valid && (
        <p className="voice-hint voice-hint--center">
          {appText("Укажи, откуда и куда ехать", "Ҡайҙан, ҡайҙа барырға икәнен яҙ")}
        </p>
      )}
    </>
  );
}
