// ================================================================
//  SOS — спокойный крупный экран экстренной помощи.
//  Публичный: звонки 112/102/101/103 (tel:) доступны всем.
//  «Сообщить своим» (POST /sos) — только со входом; гостю мягко
//  предлагаем войти. Крупные тач-цели, всё двуязычно.
// ================================================================
import { useCallback, useEffect, useState, type ComponentType } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { sendSos, type SosCategory } from "../api/safety";
import { SubHeader } from "./ConsentsScreen";
import { IconPhone, IconShield, IconCheck, IconWarn, IconHospital, IconHeart, IconCar, IconCopy, IconPin } from "../components/Icons";
import { track } from "../analytics";

type SendState = "idle" | "sending" | "sent" | "error";
type IconCmp = ComponentType<{ size?: number }>;

export default function SosScreen() {
  useAuth(); // Публичный маршрут остаётся смонтированным при смене аккаунта.
  const generation = getSessionGeneration();
  return <SosForm key={generation} generation={generation} />;
}

function SosForm({ generation }: { generation: string }) {
  const { appText } = useLang();
  const { isAuthed } = useAuth();
  const navigate = useNavigate();

  const [category, setCategory] = useState<SosCategory>("other");
  const [note, setNote] = useState("");
  const [state, setState] = useState<SendState>("idle");
  const [error, setError] = useState<string | null>(null);

  /**
   * Своё место. Нужно дважды: уходит вместе с сигналом близким И показывается
   * на экране, чтобы продиктовать оператору 112. Оператор первым делом спросит
   * «где вы?», а человек в чужом селе на трассе этого не знает.
   */
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(null);
  const [geoBusy, setGeoBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const askGeo = useCallback(() => {
    if (generation !== getSessionGeneration()) return;
    if (!navigator.geolocation) return;
    setGeoBusy(true);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        if (generation !== getSessionGeneration()) return;
        setPos({ lat: p.coords.latitude, lng: p.coords.longitude });
        setGeoBusy(false);
      },
      () => {
        if (generation === getSessionGeneration()) setGeoBusy(false);
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
    );
  }, [generation]);

  // Спрашиваем сразу при открытии: на этом экране секунды на счету.
  useEffect(() => {
    askGeo();
  }, [askGeo]);

  const coordsText = pos ? `${pos.lat.toFixed(5)}, ${pos.lng.toFixed(5)}` : "";

  async function copyCoords() {
    if (generation !== getSessionGeneration()) return;
    const text = [note.trim(), coordsText ? `${coordsText}` : ""].filter(Boolean).join("\n");
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      if (generation !== getSessionGeneration()) return;
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* буфер недоступен — цифры на экране, их можно продиктовать */
    }
  }

  const cats: { key: SosCategory; label: string; Icon: IconCmp }[] = [
    { key: "medical", label: appText("Здоровье", "Һаулыҡ"), Icon: IconHeart },
    { key: "breakdown", label: appText("На трассе", "Юлда"), Icon: IconCar },
    { key: "other", label: appText("Другое", "Башҡа"), Icon: IconWarn },
  ];

  async function fire() {
    if (generation !== getSessionGeneration()) return;
    if (state === "sending") return;
    setState("sending");
    setError(null);
    try {
      // Координаты кладём, если они уже есть: ждать GPS в экстренной ситуации нельзя,
      // сигнал без места всё равно лучше, чем ничего.
      await sendSos({ category, note: note.trim(), lat: pos?.lat, lng: pos?.lng });
      if (generation !== getSessionGeneration()) return;
      track("sos");
      setState("sent");
    } catch (e) {
      if (generation !== getSessionGeneration()) return;
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

  const services = [
    { num: "102", label: appText("Полиция", "Полиция"), Icon: IconShield },
    { num: "101", label: appText("Пожарные", "Янғын"), Icon: IconWarn },
    { num: "103", label: appText("Скорая", "Тиҙ ярҙам"), Icon: IconHospital },
  ];

  return (
    <>
      <SubHeader title="SOS" onBack={() => navigate(-1)} />
      <div className="cabinet sos">
        {/* Карточка «Срочный вызов»: красный круг с SOS, заголовок 24, подпись. */}
        <section className="sos-head">
          <span className="sos-head__icon" aria-hidden><IconWarn size={24} /></span>
          <span className="sos-head__text">
            <strong>{appText("Срочный вызов", "Ашығыс саҡырыу")}</strong>
            <small>{appText("Звонок в экстренные службы с твоего номера.", "Ашығыс хеҙмәттәргә үҙ номерыңдан шылтырау.")}</small>
          </span>
        </section>

        <a className="sos-112" href="tel:112">
          <IconPhone size={24} />
          {appText("Позвонить 112", "112 — шылтыратыу")}
        </a>
        <p className="sos-note">
          {appText("Звонок идёт с твоего номера. 112 — единый номер всех служб.", "Шылтырау үҙ номерыңдан бара. 112 — бөтә хеҙмәттәрҙең уртаҡ номеры.")}
        </p>

        {/* Прямой вызов конкретной службы — быстрее 112 (без оператора-маршрутизатора). Тап = сразу звонок. */}
        <strong className="sos-section">{appText("Прямой вызов службы", "Хеҙмәткә туранан-тура")}</strong>
        <div className="sos-services">
          {services.map((svc) => (
            <a key={svc.num} className="sos-service" href={`tel:${svc.num}`}>
              <svc.Icon size={26} />
              <strong>{svc.label}</strong>
              <b>{svc.num}</b>
            </a>
          ))}
        </div>

        <label className="field dl-field">
          <span className="field__label">{appText("Что случилось?", "Нимә булды?")}</span>
          <textarea
            className="field__input field__area dl-field__area"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            maxLength={500}
            placeholder={appText("Где ты, что нужно…", "Ҡайҙа һин, ни кәрәк…")}
          />
        </label>

        {/* Первое, что спросит оператор, — «где вы?». На трассе или в чужом селе человек этого
            не знает. Цифры крупно и рядом «скопировать». */}
        <section className="sos-dictate">
          <strong>{appText("Продиктуй оператору", "Операторға әйт")}</strong>
          {note.trim() && <p className="sos-dictate__note">{note.trim()}</p>}
          {coordsText ? (
            <span className="sos-dictate__coords">
              <IconPin size={18} />
              {appText("Координаты: ", "Координаталар: ")}{coordsText}
            </span>
          ) : (
            <p className="sos-dictate__muted">
              {geoBusy
                ? appText("Определяем место…", "Урынды билдәләйбеҙ…")
                : appText("Геолокация выключена — включи, чтобы продиктовать координаты.", "Геолокация һүндерелгән — координаталарҙы әйтер өсөн ҡабыҙ.")}
            </p>
          )}
          <div className="sos-dictate__row">
            <button type="button" className="btn-soft" onClick={askGeo} disabled={geoBusy}>
              {geoBusy ? appText("Обновляю…", "Яңыртам…") : coordsText ? appText("Обновить", "Яңыртыу") : appText("Включить гео", "Геоны ҡабыҙыу")}
            </button>
            {(coordsText || note.trim()) && (
              <button type="button" className="btn-soft" onClick={copyCoords}>
                {copied ? <IconCheck size={18} /> : <IconCopy size={18} />}
                {copied ? appText("Скопировано", "Күсерелде") : appText("Скопировать", "Күсереү")}
              </button>
            )}
          </div>
        </section>

        {/* Сообщить своим — требует входа. */}
        <div className="sos-notify">
          <strong>{appText("Сообщить близким и поддержке", "Яҡындарға һәм ярҙамға хәбәр итеү")}</strong>
          <p>
            {isAuthed
              ? appText(
                  "Доверенные получат SMS с твоим местом, а дежурный Юлдаша — сигнал. Звонить 112 всё равно нужно самому.",
                  "Ышаныслылар урының менән SMS алыр, Юлдаш дежуры — сигнал. 112-гә барыбер үҙең шылтырат."
                )
              : appText(
                  "Для SMS близким и сигнала поддержке нужно войти. Звонок 112 работает без входа.",
                  "Яҡындарға SMS һәм ярҙамға сигнал өсөн инергә кәрәк. 112 шылтырауы инеүһеҙ эшләй."
                )}
          </p>
        </div>

        {!isAuthed ? (
          <button type="button" className="btn-primary submit-btn" onClick={() => navigate("/login", { state: { from: "/sos" } })}>
            {appText("Войти", "Инеү")}
          </button>
        ) : (
          <>
            <div className="sos-cats" role="radiogroup" aria-label={appText("Что случилось?", "Нимә булды?")}>
              {cats.map((c) => (
                <button
                  key={c.key}
                  type="button"
                  role="radio"
                  aria-checked={category === c.key}
                  className={"sos-cat" + (category === c.key ? " is-active" : "")}
                  onClick={() => setCategory(c.key)}
                >
                  <c.Icon size={18} />
                  {c.label}
                </button>
              ))}
            </div>
            {state === "sent" && (
              <div className="sos-info sos-info--ok">
                <strong>{appText("Сигнал отправлен", "Сигнал ебәрелде")}</strong>
                <span>
                  {appText(
                    "Поддержка Юлдаш получила сигнал с твоими координатами. Не жди — если можешь, позвони 112 и близким сам.",
                    "Юлдаш ярҙамы координаталарың менән сигнал алды. Көтмә — мөмкин булһа, 112-гә һәм яҡындарыңа үҙең шылтырат."
                  )}
                </span>
              </div>
            )}
            {state === "error" && (
              <div className="sos-info sos-info--bad">
                <strong>{appText("Сигнал не отправлен", "Сигнал ебәрелмәне")}</strong>
                <span>{error ?? appText("Похоже, нет сети. Проверь связь и нажми ещё раз.", "Бәйләнеш юҡ кеүек. Тикшереп, тағы баҫ.")}</span>
              </div>
            )}
            <button type="button" className="btn-danger submit-btn" onClick={fire} disabled={state === "sending"}>
              {state === "sending" ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Сообщить близким и поддержке", "Яҡындарға һәм ярҙамға хәбәр итеү")}
            </button>
          </>
        )}

        <p className="sos-law">{appText("Ложный вызов экстренных служб наказуем по закону.", "Ялған ашығыс саҡырыу закон буйынса язаға тарттырыла.")}</p>
        <button type="button" className="btn-ghost sos-back" onClick={() => navigate(-1)}>
          {appText("Назад", "Артҡа")}
        </button>
      </div>
    </>
  );
}
