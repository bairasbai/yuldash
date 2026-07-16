// ================================================================
//  SOS — спокойный крупный экран экстренной помощи.
//  Публичный: звонки 112/102/101/103 (tel:) доступны всем.
//  «Сообщить своим» (POST /sos) — только со входом; гостю мягко
//  предлагаем войти. Крупные тач-цели, всё двуязычно.
// ================================================================
import { useState, type ComponentType } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { sendSos, type SosCategory } from "../api/safety";
import { SubHeader } from "./ConsentsScreen";
import { IconPhone, IconShield, IconCheck, IconWarn, IconHospital, IconHeart, IconCar } from "../components/Icons";

type SendState = "idle" | "sending" | "sent" | "error";
type IconCmp = ComponentType<{ size?: number }>;

const EMERGENCY: { num: string; ru: string; ba: string; Icon: IconCmp }[] = [
  { num: "112", ru: "Единая служба", ba: "Берҙәм хеҙмәт", Icon: IconWarn },
  { num: "103", ru: "Скорая", ba: "Тиҙ ярҙам", Icon: IconHospital },
  { num: "102", ru: "Полиция", ba: "Полиция", Icon: IconShield },
  { num: "101", ru: "Пожарные / МЧС", ba: "Янғын / ФАЙ", Icon: IconWarn },
];

export default function SosScreen() {
  const { appText } = useLang();
  const { isAuthed } = useAuth();
  const navigate = useNavigate();

  const [category, setCategory] = useState<SosCategory>("other");
  const [note, setNote] = useState("");
  const [state, setState] = useState<SendState>("idle");
  const [error, setError] = useState<string | null>(null);

  const cats: { key: SosCategory; label: string; Icon: IconCmp }[] = [
    { key: "medical", label: appText("Здоровье", "Һаулыҡ"), Icon: IconHeart },
    { key: "breakdown", label: appText("На трассе", "Юлда"), Icon: IconCar },
    { key: "other", label: appText("Другое", "Башҡа"), Icon: IconWarn },
  ];

  async function fire() {
    if (state === "sending") return;
    setState("sending");
    setError(null);
    try {
      await sendSos({ category, note: note.trim() });
      setState("sent");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { state: { from: "/sos" } });
        return;
      }
      setError(
        appText(
          "Не удалось отправить. Позвони в службу выше — это быстрее.",
          "Ебәреп булманы. Юғарыла хеҙмәткә шылтырат — тиҙерәк."
        )
      );
      setState("error");
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Экстренная помощь", "Ашығыс ярҙам")}
        subtitle={appText("Спокойно. Мы рядом.", "Тыныс. Беҙ янда.")}
        onBack={() => navigate(-1)}
      />

      {/* Экстренные звонки — публично, крупными кнопками */}
      <h2 className="section-title">{appText("Позвонить в службу", "Хеҙмәткә шылтыратырға")}</h2>
      <div className="sos-grid">
        {EMERGENCY.map((e) => (
          <a key={e.num} className="sos-call" href={`tel:${e.num}`}>
            <span className="sos-call__emoji" aria-hidden><e.Icon size={26} /></span>
            <span className="sos-call__num">{e.num}</span>
            <span className="sos-call__label">{appText(e.ru, e.ba)}</span>
          </a>
        ))}
      </div>
      <p className="sos-hint">
        <IconPhone size={16} />{" "}
        {appText(
          "Звонок бесплатный и работает даже без интернета.",
          "Шылтыратыу түләүһеҙ, интернетһеҙ ҙә эшләй."
        )}
      </p>

      {/* Сообщить своим — требует входа */}
      <h2 className="section-title">{appText("Сообщить близким", "Яҡындарға хәбәр итергә")}</h2>

      {!isAuthed ? (
        <div className="safe-note">
          <div className="safe-note__emoji" aria-hidden><IconShield size={30} /></div>
          <p>
            {appText(
              "Войди, чтобы одним касанием оповестить своих доверенных о том, что нужна помощь.",
              "Ин, бер баҫыуҙа үҙ ышаныслыларыңа ярҙам кәрәклеген хәбәр ит."
            )}
          </p>
          <button type="button" className="btn-primary btn-lg" onClick={() => navigate("/login", { state: { from: "/sos" } })}>
            {appText("Войти", "Инеү")}
          </button>
        </div>
      ) : state === "sent" ? (
        <div className="state state--ok">
          <div className="state__emoji" aria-hidden><IconCheck size={34} /></div>
          <h2>{appText("Мы получили сигнал", "Сигнал ҡабул ителде")}</h2>
          <p>
            {appText(
              "Твоим доверенным ушло сообщение, а поддержка Юлдаша уже в курсе. Если опасно — звони в службу выше.",
              "Ышаныслыларыңа хәбәр китте, Юлдаш ярҙамы хәбәрҙар. Хәүефле булһа — юғарыла хеҙмәткә шылтырат."
            )}
          </p>
          <button type="button" className="btn-ghost" onClick={() => { setState("idle"); setNote(""); }}>
            {appText("Готово", "Әҙер")}
          </button>
        </div>
      ) : (
        <div className="sos-panel">
          <div className="sos-panel__q">{appText("Что случилось?", "Ни булды?")}</div>
          <div className="seg">
            {cats.map((c) => (
              <button
                key={c.key}
                type="button"
                className={"seg__item" + (category === c.key ? " is-active" : "")}
                onClick={() => setCategory(c.key)}
              >
                <span><c.Icon size={18} /></span>
                {c.label}
              </button>
            ))}
          </div>

          <label className="field">
            <span className="field__label">{appText("Коротко (по желанию)", "Ҡыҫҡаса (теләһәң)")}</span>
            <textarea
              className="field__input field__area"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={2}
              maxLength={500}
              placeholder={appText("Где ты, что нужно…", "Ҡайҙа һин, ни кәрәк…")}
            />
          </label>

          {error && <div className="auth__error">{error}</div>}

          <button
            type="button"
            className="btn-danger btn-lg sos-send"
            onClick={fire}
            disabled={state === "sending"}
          >
            {state === "sending" ? (
              appText("Отправляем…", "Ебәрәбеҙ…")
            ) : (
              <><IconShield size={20} /> {appText("Отправить SOS близким", "Яҡындарға SOS ебәрергә")}</>
            )}
          </button>
          <p className="sos-hint sos-hint--center">
            <IconCheck size={15} />{" "}
            {appText(
              "Придёт SMS твоим доверенным контактам.",
              "Ышаныслы контакттарыңа SMS килер."
            )}
          </p>
        </div>
      )}
    </>
  );
}
