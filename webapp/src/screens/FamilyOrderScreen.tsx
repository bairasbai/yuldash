// ================================================================
//  «За близкого» — заявка на поездку для другого человека (пожилого
//  родителя, ребёнка). POST /requests с for_relative_name + assisted
//  (сервер сразу уведомляет админа). Поля «телефон близкого» здесь нет совсем (решение Александра
//  17.10.2026, как и в Android): номер никуда не уходил, а человек ждал, что водитель позвонит маме.
//  Комментарий видят все водители в ленте — в нём только нейтральная метка. RequireAuth.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { createRequest } from "../api/requests";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconHeart } from "../components/Icons";

type State = "idle" | "sending" | "sent" | "error";

export default function FamilyOrderScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const generation = useRef(getSessionGeneration()).current;
  const mounted = useRef(false);
  const submitting = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const current = () => mounted.current && generation === getSessionGeneration();

  const [relName, setRelName] = useState("");
  const [fromCity, setFromCity] = useState("");
  const [toCity, setToCity] = useState("");
  const [comment, setComment] = useState("");
  const [state, setState] = useState<State>("idle");
  const [createdId, setCreatedId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const valid = relName.trim() && fromCity.trim() && toCity.trim();

  async function submit() {
    if (!valid || !current() || submitting.current) return;
    submitting.current = true;
    setState("sending");
    setError(null);
    // ПРИВАТНОСТЬ: заявку со всем комментарием видят все водители в ленте ещё до отклика, поэтому
    // в комментарии — нейтральная метка, как в Android (AccessibilityScreens.kt, FamilyOrderScreen),
    // и заметка самого человека. Телефона близкого в форме нет (17.10.2026).
    const parts = [appText("Заказ за близкого", "Яҡын кеше өсөн заказ"), comment.trim()].filter(Boolean);
    try {
      const row = await createRequest({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        for_relative_name: relName.trim(),
        comment: parts.join(". "),
        assisted: true,
      });
      if (!current()) return;
      setCreatedId(row.id);
      setState("sent");
    } catch (e) {
      submitting.current = false;
      if (!current()) return;
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

  if (generation !== getSessionGeneration()) return null;

  if (state === "sent" && createdId !== null) {
    return (
      <>
        <SubHeader title={appText("За близкого", "Яҡын өсөн")} onBack={() => navigate("/profile")} />
        <div className="state state--ok">
          <div className="state__icon" aria-hidden><IconHeart size={34} /></div>
          <h2>{appText("Заявка принята", "Заявка ҡабул ителде")}</h2>
          <p>
            {appText(
              "Водители увидят заявку. Их предложения появятся в откликах.",
              "Йөрөтөүселәр заявканы күрер. Тәҡдимдәре яуаптарҙа күренер."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => {
            if (current()) navigate(`/requests/${createdId}/responses`);
          }}>
            {appText("Смотреть отклики", "Яуаптарҙы ҡарарға")}
          </button>
          <button type="button" className="btn-ghost" onClick={() => {
            if (current()) navigate("/my-requests");
          }}>
            {appText("Мои заявки", "Минең заявкалар")}
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
        <span className="safe-note__ic" aria-hidden><IconHeart size={18} /></span>
        <p>
          {appText(
            "Оставь заявку — водители увидят, что поездка нужна близкому человеку.",
            "Заявка ҡалдыр — йөрөтөүселәр сәфәр яҡын кешегә кәрәклеген күрер."
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
