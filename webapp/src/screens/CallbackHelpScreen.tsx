// ================================================================
//  «Перезвоните мне» — крупный простой экран для пожилых / без интернета.
//  POST /callback: поддержка Юлдаша получает телефон из профиля и звонит.
//  Ручка требует входа (телефон берётся из аккаунта) — гостю мягко
//  предлагаем войти. Всё крупно, спокойно, двуязычно.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { requestCallback } from "../api/safety";
import { SubHeader } from "./ConsentsScreen";
import { IconPhone, IconCheck, IconHeart } from "../components/Icons";

type State = "idle" | "sending" | "sent" | "error";

export default function CallbackHelpScreen() {
  const { appText } = useLang();
  const { isAuthed, user } = useAuth();
  const navigate = useNavigate();

  const [note, setNote] = useState("");
  const [state, setState] = useState<State>("idle");
  const [error, setError] = useState<string | null>(null);

  async function fire() {
    if (state === "sending") return;
    setState("sending");
    setError(null);
    try {
      await requestCallback(note.trim());
      setState("sent");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { state: { from: "/callback" } });
        return;
      }
      setError(appText("Не получилось. Попробуй ещё раз.", "Булманы. Тағы ла ҡабатла."));
      setState("error");
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Перезвоните мне", "Миңә шылтыратығыҙ")}
        subtitle={appText("Мы позвоним и всё оформим сами", "Беҙ шылтыратып, барыһын үҙебеҙ рәтләйбеҙ")}
        onBack={() => navigate(-1)}
      />

      {state === "sent" ? (
        <div className="state state--ok state--big">
          <div className="state__icon" aria-hidden><IconPhone size={34} /></div>
          <h2>{appText("Скоро перезвоним", "Тиҙҙән шылтыратабыҙ")}</h2>
          <p>
            {appText(
              "Мы получили твою просьбу и позвоним на твой номер. Держи телефон под рукой.",
              "Үтенесеңде алдыҡ, номереңә шылтыратабыҙ. Телефоныңды яҡын тот."
            )}
          </p>
          <button type="button" className="btn-primary btn-lg" onClick={() => navigate("/simple")}>
            {appText("Хорошо", "Яҡшы")}
          </button>
        </div>
      ) : !isAuthed ? (
        <div className="safe-note safe-note--big">
          <div className="safe-note__emoji" aria-hidden><IconHeart size={30} /></div>
          <p>
            {appText(
              "Войди один раз — и мы будем знать, на какой номер перезвонить. Это быстро.",
              "Бер тапҡыр ин — ниндәй номерға шылтыратырға белербеҙ. Был тиҙ."
            )}
          </p>
          <button type="button" className="btn-primary btn-lg" onClick={() => navigate("/login", { state: { from: "/callback" } })}>
            {appText("Войти", "Инеү")}
          </button>
        </div>
      ) : (
        <div className="callback-panel">
          <div className="callback-illust" aria-hidden><IconPhone size={40} /></div>
          <p className="callback-lead">
            {appText(
              "Нажми большую кнопку — и мы сами наберём тебя, поможем найти поездку или ответим на вопрос.",
              "Ҙур төймәгә баҫ — беҙ үҙебеҙ шылтыратабыҙ, сәфәр табырға ярҙам итәбеҙ йәки һорауыңа яуап бирәбеҙ."
            )}
          </p>

          {user?.phone && (
            <div className="callback-phone">
              <IconPhone size={18} />
              <span>{appText("Позвоним на", "Шылтыратабыҙ")}: <b>{user.phone}</b></span>
            </div>
          )}

          <label className="field">
            <span className="field__label">{appText("О чём вопрос (по желанию)", "Һорау ниҙә (теләһәң)")}</span>
            <textarea
              className="field__input field__area"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={2}
              maxLength={500}
              placeholder={appText("Например: помогите заказать поездку", "Мәҫәлән: сәфәр ҡалдырырға ярҙам итегеҙ")}
            />
          </label>

          {error && <div className="auth__error">{error}</div>}

          <button
            type="button"
            className="btn-primary btn-lg callback-btn"
            onClick={fire}
            disabled={state === "sending"}
          >
            {state === "sending" ? appText("Отправляем…", "Ебәрәбеҙ…") : (
              <><IconCheck size={22} /> {appText("Перезвоните мне", "Миңә шылтыратығыҙ")}</>
            )}
          </button>
        </div>
      )}
    </>
  );
}
