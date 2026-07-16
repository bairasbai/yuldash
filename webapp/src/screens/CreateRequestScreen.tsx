// ================================================================
//  Создание заявки пассажира (POST /requests). Требует вход.
//  Откуда/куда/когда/места/цена/категория + сворачиваемый блок
//  «Дополнительно» (условия, «только для своих», комментарий).
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createRequest, type RequestInput } from "../api/requests";
import type { RideCategory } from "../api/rides";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconBolt, IconHospital, IconUsers } from "../components/Icons";
import { YuModeRideshare } from "../components/BrandIcons";
import { AmenityIcon } from "../components/amenityIcons";

type Opt =
  | "baggage"
  | "child_seat"
  | "pets"
  | "women_only"
  | "non_smoking"
  | "air_conditioner";

export default function CreateRequestScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [when, setWhen] = useState(""); // datetime-local
  const [seats, setSeats] = useState(1);
  const [maxPrice, setMaxPrice] = useState("");
  const [category, setCategory] = useState<RideCategory>("regular");

  const [showMore, setShowMore] = useState(false);
  const [opts, setOpts] = useState<Record<Opt, boolean>>({
    baggage: false,
    child_seat: false,
    pets: false,
    women_only: false,
    non_smoking: false,
    air_conditioner: false,
  });
  const [onlyTrusted, setOnlyTrusted] = useState(false);
  const [comment, setComment] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [createdId, setCreatedId] = useState<number | null>(null);

  const cats: { key: RideCategory; label: string; icon: JSX.Element }[] = [
    { key: "regular", label: appText("Обычная", "Ябай"), icon: <YuModeRideshare size={20} /> },
    { key: "urgent", label: appText("Срочно", "Ашығыс"), icon: <IconBolt size={20} /> },
    { key: "hospital", label: appText("В больницу", "Дауаханаға"), icon: <IconHospital size={20} /> },
  ];

  const optList: { key: Opt; label: string }[] = [
    { key: "baggage", label: appText("Есть багаж", "Багаж бар") },
    { key: "child_seat", label: appText("Нужно детское кресло", "Бала урыны кәрәк") },
    { key: "pets", label: appText("Со мной питомец", "Хайуан менән") },
    { key: "women_only", label: appText("Только женщины", "Тик ҡатын-ҡыҙ") },
    { key: "non_smoking", label: appText("Без курения", "Тартмайынса") },
    { key: "air_conditioner", label: appText("С кондиционером", "Кондиционер менән") },
  ];

  const canSubmit = from.trim().length > 0 && to.trim().length > 0 && !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    const body: RequestInput = {
      from_city: from.trim(),
      to_city: to.trim(),
      desired_at: when ? new Date(when).toISOString() : null,
      seats,
      max_price: maxPrice ? Math.max(0, parseInt(maxPrice, 10) || 0) : null,
      category,
      only_trusted: onlyTrusted,
      comment: comment.trim(),
      ...opts,
    };
    try {
      const row = await createRequest(body);
      setCreatedId(row.id);
      setDone(true);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось отправить. Попробуй снова.", "Ебәрергә булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <>
        <SubHeader
          title={appText("Заявка отправлена", "Заявка ебәрелде")}
          onBack={() => navigate("/map")}
        />
        <div className="state">
          <div className="state__icon"><IconCheck size={34} /></div>
          <h2>{appText("Готово! Ищем водителя", "Әҙер! Водитель эҙләйбеҙ")}</h2>
          <p>
            {appText(
              "Как только кто-то откликнется — покажем предложения. Загляни во «Мои заявки».",
              "Кемдер яуап бирһә — тәҡдимдәрҙе күрһәтәбеҙ. «Минең заявкалар»ға кил."
            )}
          </p>
          {createdId != null && (
            <button
              type="button"
              className="btn-primary"
              onClick={() => navigate(`/requests/${createdId}/responses`)}
            >
              {appText("Смотреть отклики", "Яуаптарҙы ҡарарға")}
            </button>
          )}
          <button
            type="button"
            className="btn-ghost"
            style={{ marginTop: 10 }}
            onClick={() => navigate("/map")}
          >
            {appText("На карту", "Картаға")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Куда едем?", "Ҡайҙа барабыҙ?")}
        subtitle={appText("Оставь заявку — водители откликнутся", "Заявка ҡалдыр — водителдәр яуап бирер")}
        onBack={() => navigate(-1)}
      />

      <div className="form">
        <label className="field">
          <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
          <input
            className="field__input"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
          <input
            className="field__input"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            placeholder={appText("Город или село", "Ҡала йәки ауыл")}
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Когда (необязательно)", "Ҡасан (мотлаҡ түгел)")}</span>
          <input
            className="field__input"
            type="datetime-local"
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </label>

        <div className="field-row">
          <div className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Мест", "Урын")}</span>
            <div className="stepper">
              <button
                type="button"
                onClick={() => setSeats((s) => Math.max(1, s - 1))}
                aria-label={appText("Меньше", "Кәм")}
              >
                −
              </button>
              <b>{seats}</b>
              <button
                type="button"
                onClick={() => setSeats((s) => Math.min(8, s + 1))}
                aria-label={appText("Больше", "Күберәк")}
              >
                +
              </button>
            </div>
          </div>
          <label className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Цена до, ₽", "Хаҡ, ₽ ҡәҙәр")}</span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              value={maxPrice}
              onChange={(e) => setMaxPrice(e.target.value)}
              placeholder={appText("не важно", "мөһим түгел")}
            />
          </label>
        </div>

        <span className="field__label" style={{ marginTop: 4 }}>
          {appText("Тип поездки", "Сәфәр төрө")}
        </span>
        <div className="seg">
          {cats.map((c) => (
            <button
              key={c.key}
              type="button"
              className={"seg__item" + (category === c.key ? " is-active" : "")}
              onClick={() => setCategory(c.key)}
            >
              <span>{c.icon}</span>
              {c.label}
            </button>
          ))}
        </div>

        <div className="more-block">
          <button
            type="button"
            className="more-toggle"
            onClick={() => setShowMore((v) => !v)}
            aria-expanded={showMore}
          >
            {appText("Дополнительно", "Өҫтәмә")}
            <span className={"more-toggle__chev" + (showMore ? " open" : "")}>⌄</span>
          </button>

          {showMore && (
          <div className="more-body">
            <div className="opt-grid">
              {optList.map((o) => (
                <button
                  key={o.key}
                  type="button"
                  className={"opt-chip" + (opts[o.key] ? " is-active" : "")}
                  onClick={() => setOpts((p) => ({ ...p, [o.key]: !p[o.key] }))}
                >
                  {opts[o.key] ? <IconCheck size={16} /> : <AmenityIcon amenity={o.key} size={16} />}
                  {o.label}
                </button>
              ))}
            </div>

            <button
              type="button"
              className={"onb__simple" + (onlyTrusted ? " is-active" : "")}
              onClick={() => setOnlyTrusted((v) => !v)}
              style={{ marginTop: 12 }}
            >
              <span className="onb__simple-emoji"><IconUsers size={24} /></span>
              <span className="onb__simple-text">
                <b>{appText("Только для своих", "Тик үҙебеҙ өсөн")}</b>
                <span>
                  {appText(
                    "Заявку увидят только проверенные соседи",
                    "Заявканы тик тикшерелгән күршеләр күрер"
                  )}
                </span>
              </span>
              <span className={"switch" + (onlyTrusted ? " on" : "")} />
            </button>

            <label className="field" style={{ marginTop: 12 }}>
              <span className="field__label">{appText("Комментарий", "Аңлатма")}</span>
              <textarea
                className="field__input field__area"
                value={comment}
                maxLength={2000}
                onChange={(e) => setComment(e.target.value)}
                placeholder={appText(
                  "Например: заберите у школы, еду с одной сумкой",
                  "Мәҫәлән: мәктәп янынан алығыҙ, бер сумка менән"
                )}
              />
            </label>
          </div>
          )}
        </div>

        {error && <div className="auth__error">{error}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={submit}
          disabled={!canSubmit}
        >
          {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Найти водителя", "Водитель табырға")}
        </button>
      </div>
    </>
  );
}
