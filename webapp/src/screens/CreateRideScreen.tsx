// ================================================================
//  Публикация поездки водителем (POST /rides). RequireAuth.
//  Маршрут (откуда/куда, тип — в т.ч. «В больницу» с выбором клиники),
//  дата/время, места, цена, удобства (тумблеры), регулярность.
//  Тип «В больницу» → GET /medical-partners (выбор клиники-назначения).
// ================================================================
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { publishRide, type RideCreateInput } from "../api/driver";
import { fetchMedicalPartners, type MedicalPartner } from "../api/medical";
import type { RideCategory } from "../api/rides";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconBolt, IconHospital, IconUsers } from "../components/Icons";
import { YuModeRideshare } from "../components/BrandIcons";
import { AmenityIcon } from "../components/amenityIcons";

type Amenity =
  | "baggage"
  | "child_seat"
  | "pets_allowed"
  | "women_only"
  | "non_smoking"
  | "air_conditioner"
  | "quiet";

type Recur = "none" | "daily" | "weekdays" | "weekly";

export default function CreateRideScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [when, setWhen] = useState(""); // datetime-local
  const [seats, setSeats] = useState(3);
  const [price, setPrice] = useState("");
  const [category, setCategory] = useState<RideCategory>("regular");
  const [partnerId, setPartnerId] = useState<number | null>(null);

  const [amen, setAmen] = useState<Record<Amenity, boolean>>({
    baggage: false,
    child_seat: false,
    pets_allowed: false,
    women_only: false,
    non_smoking: false,
    air_conditioner: false,
    quiet: false,
  });
  const [onlyTrusted, setOnlyTrusted] = useState(false);
  const [recurrence, setRecurrence] = useState<Recur>("none");
  const [comment, setComment] = useState("");

  const [showMore, setShowMore] = useState(false);

  // Клиники — грузим лениво, когда выбран тип «В больницу» (мягко: нет эндпоинта → просто без списка).
  const [clinics, setClinics] = useState<MedicalPartner[]>([]);
  const [clinicsLoaded, setClinicsLoaded] = useState(false);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [createdId, setCreatedId] = useState<number | null>(null);

  useEffect(() => {
    if (category !== "hospital" || clinicsLoaded) return;
    const ac = new AbortController();
    fetchMedicalPartners(undefined, ac.signal)
      .then((rows) => {
        setClinics(rows.filter((c) => c.active));
        setClinicsLoaded(true);
      })
      .catch(() => setClinicsLoaded(true)); // 404/скоро → тихо, тип всё равно можно выбрать
    return () => ac.abort();
  }, [category, clinicsLoaded]);

  const cats: { key: RideCategory; label: string; icon: JSX.Element }[] = [
    { key: "regular", label: appText("Обычная", "Ябай"), icon: <YuModeRideshare size={20} /> },
    { key: "urgent", label: appText("Срочно", "Ашығыс"), icon: <IconBolt size={20} /> },
    { key: "hospital", label: appText("В больницу", "Дауаханаға"), icon: <IconHospital size={20} /> },
  ];

  const amenList: { key: Amenity; label: string }[] = [
    { key: "baggage", label: appText("Багаж", "Багаж") },
    { key: "child_seat", label: appText("Детское кресло", "Бала урыны") },
    { key: "air_conditioner", label: appText("Кондиционер", "Кондиционер") },
    { key: "pets_allowed", label: appText("Можно с питомцем", "Хайуан менән") },
    { key: "non_smoking", label: appText("Без курения", "Тартмайынса") },
    { key: "quiet", label: appText("Тихая поездка", "Тыныс сәфәр") },
    { key: "women_only", label: appText("Только женщины", "Тик ҡатын-ҡыҙ") },
  ];

  const recurList: { key: Recur; label: string }[] = [
    { key: "none", label: appText("Разово", "Бер тапҡыр") },
    { key: "daily", label: appText("Каждый день", "Һәр көн") },
    { key: "weekdays", label: appText("По будням", "Эш көндәре") },
    { key: "weekly", label: appText("Раз в неделю", "Аҙнаға бер") },
  ];

  const canSubmit =
    from.trim().length > 0 && to.trim().length > 0 && when.length > 0 && !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    const body: RideCreateInput = {
      from_city: from.trim(),
      to_city: to.trim(),
      depart_at: new Date(when).toISOString(),
      seats_total: seats,
      price: price ? Math.max(0, parseInt(price, 10) || 0) : 0,
      category,
      comment: comment.trim(),
      only_trusted: onlyTrusted,
      recurrence,
      baggage: amen.baggage,
      child_seat: amen.child_seat,
      pets_allowed: amen.pets_allowed,
      women_only: amen.women_only,
      air_conditioner: amen.air_conditioner,
      quiet: amen.quiet,
      // Бэк хранит «курение», а тумблер — «без курения»: инвертируем.
      smoking: amen.non_smoking ? false : undefined,
      partner_id: category === "hospital" ? partnerId : null,
    };
    try {
      const ride = await publishRide(body);
      setCreatedId(ride.id);
      setDone(true);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось опубликовать. Попробуй снова.", "Баҫтырырға булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <>
        <SubHeader
          title={appText("Поездка опубликована", "Сәфәр баҫтырылды")}
          onBack={() => navigate("/driver")}
        />
        <div className="state">
          <div className="state__icon"><IconCheck size={34} /></div>
          <h2>{appText("Готово! Пассажиры увидят поездку", "Әҙер! Юлаусылар күрер")}</h2>
          <p>
            {appText(
              recurrence === "none"
                ? "Твоя поездка теперь в ленте и на карте. Придёт бронь — пришлём уведомление."
                : "Создали серию ближайших рейсов. Все — в ленте и на карте.",
              recurrence === "none"
                ? "Сәфәрең хәҙер таҫмала һәм картала. Бронь килһә — хәбәр итәбеҙ."
                : "Яҡын рейстар серияһын төҙөнөк. Барыһы ла таҫмала һәм картала."
            )}
          </p>
          {createdId != null && (
            <button
              type="button"
              className="btn-primary"
              onClick={() => navigate("/boost", { state: { rideId: createdId } })}
            >
              {appText("Поднять в ленте", "Таҫмала күтәреү")}
            </button>
          )}
          <button
            type="button"
            className="btn-ghost"
            style={{ marginTop: 10 }}
            onClick={() => navigate("/driver")}
          >
            {appText("В кабинет водителя", "Водитель кабинетына")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Опубликовать поездку", "Сәфәр баҫтырырға")}
        subtitle={appText("Расскажи маршрут — пассажиры найдут тебя", "Маршрутты яҙ — юлаусылар табыр")}
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
          <span className="field__label">{appText("Когда выезжаешь", "Ҡасан сығаһың")}</span>
          <input
            className="field__input"
            type="datetime-local"
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </label>

        <div className="field-row">
          <div className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Свободных мест", "Буш урын")}</span>
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
            <span className="field__label">{appText("Цена с места, ₽", "Урын хаҡы, ₽")}</span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              placeholder={appText("0 — договорная", "0 — килешеү")}
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

        {/* Клиника-назначение (только для «В больницу») */}
        {category === "hospital" && clinics.length > 0 && (
          <div className="more-body" style={{ marginTop: 12 }}>
            <span className="field__label">{appText("Клиника (куда именно)", "Клиника (ҡайҙа тап)")}</span>
            <div className="opt-grid">
              {clinics.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className={"opt-chip" + (partnerId === c.id ? " is-active" : "")}
                  onClick={() => setPartnerId((p) => (p === c.id ? null : c.id))}
                >
                  {partnerId === c.id && <IconCheck size={16} />}
                  {c.name}
                </button>
              ))}
            </div>
            <p className="sheet__note" style={{ marginTop: 8 }}>
              {appText(
                "Это только точка назначения. Никаких медицинских данных.",
                "Был тик барыу нөктәһе. Бер ниндәй медицина мәғлүмәте юҡ."
              )}
            </p>
          </div>
        )}

        <button
          type="button"
          className="more-toggle"
          onClick={() => setShowMore((v) => !v)}
          aria-expanded={showMore}
        >
          {appText("Удобства и повтор", "Уңайлыҡтар һәм ҡабатлау")}
          <span className={"more-toggle__chev" + (showMore ? " open" : "")}>⌄</span>
        </button>

        {showMore && (
          <div className="more-body">
            <div className="opt-grid">
              {amenList.map((o) => (
                <button
                  key={o.key}
                  type="button"
                  className={"opt-chip" + (amen[o.key] ? " is-active" : "")}
                  onClick={() => setAmen((p) => ({ ...p, [o.key]: !p[o.key] }))}
                >
                  {amen[o.key] ? <IconCheck size={16} /> : <AmenityIcon amenity={o.key} size={16} />}
                  {o.label}
                </button>
              ))}
            </div>

            <span className="field__label" style={{ marginTop: 14 }}>
              {appText("Регулярность", "Даималыҡ")}
            </span>
            <div className="opt-grid">
              {recurList.map((r) => (
                <button
                  key={r.key}
                  type="button"
                  className={"opt-chip" + (recurrence === r.key ? " is-active" : "")}
                  onClick={() => setRecurrence(r.key)}
                >
                  {recurrence === r.key && <IconCheck size={16} />}
                  {r.label}
                </button>
              ))}
            </div>

            <button
              type="button"
              className={"onb__simple" + (onlyTrusted ? " is-active" : "")}
              onClick={() => setOnlyTrusted((v) => !v)}
              style={{ marginTop: 14 }}
            >
              <span className="onb__simple-emoji"><IconUsers size={24} /></span>
              <span className="onb__simple-text">
                <b>{appText("Только для своих", "Тик үҙебеҙ өсөн")}</b>
                <span>
                  {appText(
                    "Поездку увидят только проверенные соседи",
                    "Сәфәрҙе тик тикшерелгән күршеләр күрер"
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
                  "Например: выезжаю от автовокзала, есть место для сумки",
                  "Мәҫәлән: автовокзалдан сығам, сумкаға урын бар"
                )}
              />
            </label>
          </div>
        )}

        {error && <div className="auth__error">{error}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={submit}
          disabled={!canSubmit}
        >
          {busy ? appText("Публикуем…", "Баҫтырабыҙ…") : appText("Опубликовать", "Баҫтырырға")}
        </button>
      </div>
    </>
  );
}
