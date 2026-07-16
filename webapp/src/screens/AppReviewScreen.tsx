// ================================================================
//  Оценить приложение → /app-review (RequireAuth).
//  Звёзды + отзыв → POST /reviews (reviews.py). Текст ≥ 10 символов,
//  публикуется после модерации. Спасибо-состояние. Все состояния.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { REVIEW_MAX_LEN, REVIEW_MIN_LEN, submitAppReview } from "../api/reviews";
import { SubHeader } from "./ConsentsScreen";
import { YuStar } from "../components/BrandIcons";

type State = "idle" | "sending" | "sent" | "error";

const STAR_HINT: Record<number, [string, string]> = {
  1: ["Совсем не то", "Бөтөнләй түгел"],
  2: ["Так себе", "Шәптән түгел"],
  3: ["Нормально", "Ярай"],
  4: ["Хорошо", "Яҡшы"],
  5: ["Отлично!", "Бик шәп!"],
};

export default function AppReviewScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [stars, setStars] = useState(0);
  const [text, setText] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState<State>("idle");
  const [error, setError] = useState<string | null>(null);

  const tooShort = text.trim().length < REVIEW_MIN_LEN;

  async function submit() {
    if (state === "sending") return;
    if (stars < 1) {
      setError(appText("Поставь оценку звёздами.", "Йондоҙ менән баһа ҡуй."));
      return;
    }
    if (tooShort) {
      setError(
        appText(
          `Напиши пару слов — минимум ${REVIEW_MIN_LEN} символов.`,
          `Бер-ике һүҙ яҙ — кәмендә ${REVIEW_MIN_LEN} символ.`
        )
      );
      return;
    }
    setState("sending");
    setError(null);
    try {
      await submitAppReview({ stars, text: text.trim(), city: city.trim() });
      setState("sent");
    } catch (e) {
      setState("error");
      setError(
        e instanceof ApiError && e.status === 0
          ? appText("Нет соединения. Проверь интернет.", "Бәйләнеш юҡ. Интернетты тикшер.")
          : appText("Не получилось отправить. Попробуй ещё раз.", "Ебәреп булманы. Тағы ла ҡабатла.")
      );
    }
  }

  if (state === "sent") {
    return (
      <>
        <SubHeader title={appText("Оценить приложение", "Ҡушымтаны баһалау")} onBack={() => navigate(-1)} />
        <div className="state state--ok state--big">
          <div className="state__emoji" aria-hidden>🌿</div>
          <h2>{appText("Спасибо за отзыв!", "Фекерең өсөн рәхмәт!")}</h2>
          <p>
            {appText(
              "Мы читаем каждый отзыв. После проверки он поможет другим узнать о Юлдаше.",
              "Беҙ һәр фекерҙе уҡыйбыҙ. Тикшергәс, ул башҡаларға Юлдаш тураһында белергә ярҙам итер."
            )}
          </p>
          <button type="button" className="btn-primary btn-lg" onClick={() => navigate("/profile")}>
            {appText("Готово", "Әҙер")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Оценить приложение", "Ҡушымтаны баһалау")}
        subtitle={appText("Твоё мнение делает Юлдаш лучше", "Фекерең Юлдашты яҡшыраҡ итә")}
        onBack={() => navigate(-1)}
      />

      <div className="rate-card">
        <div className="rate-card__title">
          {appText("Как тебе Юлдаш?", "Юлдаш нисек оҡшаны?")}
        </div>
        <div className="rate-stars">
          {[1, 2, 3, 4, 5].map((n) => (
            <button
              key={n}
              type="button"
              className={"rate-star" + (n <= stars ? " rate-star--on" : "")}
              onClick={() => {
                setStars(n);
                setError(null);
              }}
              aria-label={appText(`${n} звёзд`, `${n} йондоҙ`)}
              aria-pressed={n <= stars}
            >
              <YuStar size={34} />
            </button>
          ))}
        </div>
        {stars > 0 && (
          <div className="rate-card__hint">{appText(STAR_HINT[stars][0], STAR_HINT[stars][1])}</div>
        )}
      </div>

      <label className="field" style={{ marginTop: 16 }}>
        <span className="field__label">{appText("Отзыв", "Фекер")}</span>
        <textarea
          className="field__input field__area"
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={4}
          maxLength={REVIEW_MAX_LEN}
          placeholder={appText(
            "Что понравилось, чего не хватает — расскажи своими словами.",
            "Нимә оҡшаны, ниҙең етмәй — үҙ һүҙҙәрең менән яҙ."
          )}
        />
      </label>

      <label className="field">
        <span className="field__label">{appText("Город (по желанию)", "Ҡала (теләһәң)")}</span>
        <input
          className="field__input"
          value={city}
          onChange={(e) => setCity(e.target.value)}
          maxLength={60}
          placeholder={appText("Например: Уфа", "Мәҫәлән: Өфө")}
        />
      </label>

      {error && <div className="auth__error">{error}</div>}

      <button
        type="button"
        className="btn-primary btn-lg"
        style={{ marginTop: 12 }}
        onClick={submit}
        disabled={state === "sending"}
      >
        {state === "sending"
          ? appText("Отправляем…", "Ебәрәбеҙ…")
          : appText("Отправить отзыв", "Фекерҙе ебәрергә")}
      </button>
    </>
  );
}
