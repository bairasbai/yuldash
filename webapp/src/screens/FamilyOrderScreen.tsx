// ================================================================
//  «За близкого» — заявка на поездку для другого человека (пожилого
//  родителя, ребёнка). POST /requests с for_relative_name + assisted
//  (сервер сразу уведомляет админа). Телефон близкого — в комментарии
//  (у заявки нет отдельного поля телефона). RequireAuth.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createRequest } from "../api/requests";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck } from "../components/Icons";

type State = "idle" | "sending" | "sent" | "error";

export default function FamilyOrderScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [relName, setRelName] = useState("");
  const [relPhone, setRelPhone] = useState("");
  const [fromCity, setFromCity] = useState("");
  const [toCity, setToCity] = useState("");
  const [comment, setComment] = useState("");
  const [state, setState] = useState<State>("idle");
  const [error, setError] = useState<string | null>(null);

  const valid = relName.trim() && fromCity.trim() && toCity.trim();

  async function submit() {
    if (!valid || state === "sending") return;
    setState("sending");
    setError(null);
    // Телефон близкого + заметку кладём в комментарий: у RequestIn нет поля phone.
    const parts = [
      relPhone.trim() ? appText(`Телефон близкого: ${relPhone.trim()}`, `Яҡындың телефоны: ${relPhone.trim()}`) : "",
      comment.trim(),
    ].filter(Boolean);
    try {
      await createRequest({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        for_relative_name: relName.trim(),
        comment: parts.join(". "),
        assisted: true,
      });
      setState("sent");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { state: { from: "/family-order" } });
        return;
      }
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось отправить. Попробуй снова.", "Ебәреп булманы. Ҡабат ҡара.")
      );
      setState("error");
    }
  }

  if (state === "sent") {
    return (
      <>
        <SubHeader title={appText("За близкого", "Яҡын өсөн")} onBack={() => navigate("/profile")} />
        <div className="state state--ok">
          <div className="state__emoji" aria-hidden>💚</div>
          <h2>{appText("Заявка принята", "Заявка ҡабул ителде")}</h2>
          <p>
            {appText(
              "Мы уже подбираем попутку для близкого. Поддержка Юлдаша на связи, если нужно — поможем.",
              "Яҡының өсөн юлдаш эҙләй башланыҡ. Кәрәк булһа, Юлдаш ярҙамы бәйләнештә."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/rides")}>
            {appText("Хорошо", "Яҡшы")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("За близкого", "Яҡын өсөн")}
        subtitle={appText("Закажи поездку тому, кому сложно самому", "Үҙенә ауыр булғанға сәфәр ҡалдыр")}
        onBack={() => navigate(-1)}
      />

      <div className="safe-note safe-note--tight">
        <span className="safe-note__ic" aria-hidden>💚</span>
        <p>
          {appText(
            "Оставь заявку — водители увидят, что поездка нужна близкому человеку.",
            "Заявка ҡалдыр — водителдәр сәфәр яҡын кешегә кәрәклеген күрер."
          )}
        </p>
      </div>

      <div className="form">
        <label className="field">
          <span className="field__label">{appText("Имя близкого", "Яҡындың исеме")}</span>
          <input
            className="field__input"
            value={relName}
            onChange={(e) => setRelName(e.target.value)}
            placeholder={appText("Например: Мама, Фаниль", "Мәҫәлән: Әсәй, Фәниль")}
            autoComplete="off"
          />
        </label>
        <label className="field">
          <span className="field__label">{appText("Его телефон (по желанию)", "Уның телефоны (теләһәң)")}</span>
          <input
            className="field__input"
            value={relPhone}
            onChange={(e) => setRelPhone(e.target.value)}
            placeholder="+7 999 000-00-00"
            inputMode="tel"
            autoComplete="off"
          />
        </label>
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
        <label className="field">
          <span className="field__label">{appText("Что важно знать (по желанию)", "Ни мөһим (теләһәң)")}</span>
          <textarea
            className="field__input field__area"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            rows={2}
            maxLength={2000}
            placeholder={appText("Время, вещи, помощь при посадке…", "Ваҡыт, әйберҙәр, ултырышҡа ярҙам…")}
          />
        </label>

        {error && <div className="auth__error">{error}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={submit}
          disabled={!valid || state === "sending"}
        >
          {state === "sending" ? appText("Отправляем…", "Ебәрәбеҙ…") : (
            <><IconCheck size={18} /> {appText("Оставить заявку", "Заявка ҡалдырырға")}</>
          )}
        </button>
      </div>
    </>
  );
}
