// ================================================================
//  SOS — спокойный крупный экран экстренной помощи.
//  Публичный: звонки 112/102/101/103 (tel:) доступны всем.
//  «Сообщить поддержке» (POST /sos) — только со входом; гостю мягко
//  предлагаем войти. Крупные тач-цели, всё двуязычно.
//
//  Обещаем только то, что точно случится: сигнал дежурному Юлдаша.
//  SMS близким на проде выключены (SMS_PROVIDER=mock), поэтому ни кнопка,
//  ни подпись под ней их не обещают; что реально дошло, показываем ПОСЛЕ
//  отправки — по тому, что вернул сервер (hint_ru / hint_ba). Зеркало
//  Android SosVerifyScreens.kt и сторожа SosNoSmsPromiseGuardTest.
//
//  Из поездки сюда ведёт `/sos?booking=<id>`: бронь уходит в сигнал, и
//  дежурный знает, в какой машине человек (как Android bookingId).
// ================================================================
import { useCallback, useEffect, useRef, useState, type ComponentType } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { sendSos, type SosCategory } from "../api/safety";
import { SubHeader } from "./ConsentsScreen";
import { IconPhone, IconShield, IconCheck, IconWarn, IconHospital, IconHeart, IconCar, IconCopy, IconPin } from "../components/Icons";
import { track } from "../analytics";
import { bookingIdFromSearch, sosPath as buildSosPath } from "../utils/sosLink";

type SendState = "idle" | "sending" | "sent" | "error";
type IconCmp = ComponentType<{ size?: number }>;
type SosPosition = { lat: number; lng: number; timestamp: number };
const LOCATION_MAX_AGE_MS = 60_000;

function isFreshPosition(position: SosPosition) {
  const age = Date.now() - position.timestamp;
  return Number.isFinite(position.timestamp) && age >= 0 && age < LOCATION_MAX_AGE_MS;
}

export default function SosScreen() {
  useAuth(); // Публичный маршрут остаётся смонтированным при смене аккаунта.
  const [params] = useSearchParams();
  const bookingId = bookingIdFromSearch(params.get("booking"));
  const generation = getSessionGeneration();
  return <SosForm key={generation} generation={generation} bookingId={bookingId} />;
}

function SosForm({ generation, bookingId }: { generation: string; bookingId: number | null }) {
  const { appText } = useLang();
  const { isAuthed } = useAuth();
  const navigate = useNavigate();

  const [category, setCategory] = useState<SosCategory>("other");
  const [note, setNote] = useState("");
  const [state, setState] = useState<SendState>("idle");
  const sending = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [rateLimited, setRateLimited] = useState(false);
  /** Что сервер сказал про рассылку близким (hint_ru / hint_ba): показываем как есть. */
  const [serverHint, setServerHint] = useState("");
  /** Куда вернуться после входа: с той же бронью, иначе дежурный не узнает, в какой машине человек. */
  const sosPath = buildSosPath(bookingId);

  /**
   * Своё место. Нужно дважды: уходит вместе с сигналом в Юлдаш И показывается
   * на экране, чтобы продиктовать оператору 112. Оператор первым делом спросит
   * «где вы?», а человек в чужом селе на трассе этого не знает.
   */
  const [pos, setPos] = useState<SosPosition | null>(null);
  const posRef = useRef<SosPosition | null>(null);
  const mounted = useRef(false);
  const geoRequest = useRef(0);
  const permission = useRef<PermissionStatus | null>(null);
  const [geoBusy, setGeoBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const isCurrent = useCallback(() => mounted.current && generation === getSessionGeneration(), [generation]);
  const clearPosition = useCallback(() => {
    posRef.current = null;
    setPos(null);
    setCopied(false);
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      geoRequest.current += 1;
      posRef.current = null;
    };
  }, []);

  // Если браузер сообщает об отзыве разрешения, старое место сразу недействительно.
  // Отсутствие Permissions API не мешает обычному запросу Geolocation.
  useEffect(() => {
    if (!navigator.permissions?.query) return;
    let disposed = false;
    let status: PermissionStatus | null = null;
    const changed = () => {
      if (disposed || !isCurrent() || !status) return;
      if (status.state !== "granted") {
        geoRequest.current += 1;
        clearPosition();
        setGeoBusy(false);
      }
    };
    void navigator.permissions.query({ name: "geolocation" }).then((result) => {
      if (disposed || !isCurrent()) return;
      status = result;
      permission.current = result;
      status.addEventListener("change", changed);
      // Начальное prompt ещё может завершиться согласием на уже открытый запрос.
      if (status.state === "denied") changed();
      else if (status.state !== "granted" && posRef.current) clearPosition();
    }).catch(() => { /* Браузер может не поддерживать запрос этого разрешения. */ });
    return () => {
      disposed = true;
      status?.removeEventListener("change", changed);
      permission.current = null;
    };
  }, [clearPosition, isCurrent]);

  const askGeo = useCallback(() => {
    if (!isCurrent()) return;
    const request = ++geoRequest.current;
    clearPosition();
    setGeoBusy(false);
    if (!navigator.geolocation) return;
    setGeoBusy(true);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        if (!isCurrent() || request !== geoRequest.current) return;
        const next = { lat: p.coords.latitude, lng: p.coords.longitude, timestamp: p.timestamp };
        if (isFreshPosition(next) && (!permission.current || permission.current.state === "granted")) {
          posRef.current = next;
          setPos(next);
        } else {
          clearPosition();
        }
        setGeoBusy(false);
      },
      () => {
        if (!isCurrent() || request !== geoRequest.current) return;
        clearPosition();
        setGeoBusy(false);
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
    );
  }, [clearPosition, isCurrent]);

  // Спрашиваем сразу при открытии: на этом экране секунды на счету.
  useEffect(() => {
    askGeo();
  }, [askGeo]);

  useEffect(() => {
    if (!pos) return;
    const timer = window.setTimeout(() => {
      if (isCurrent() && posRef.current === pos) clearPosition();
    }, Math.max(0, pos.timestamp + LOCATION_MAX_AGE_MS - Date.now()));
    return () => window.clearTimeout(timer);
  }, [pos, clearPosition, isCurrent]);

  const readFreshPosition = useCallback(() => {
    if (!isCurrent()) return null;
    const snapshot = posRef.current;
    if (snapshot && isFreshPosition(snapshot) && (!permission.current || permission.current.state === "granted")) return snapshot;
    if (snapshot) clearPosition();
    return null;
  }, [clearPosition, isCurrent]);

  const visiblePos = pos && isFreshPosition(pos) && (!permission.current || permission.current.state === "granted") ? pos : null;
  const coordsText = visiblePos ? `${visiblePos.lat.toFixed(5)}, ${visiblePos.lng.toFixed(5)}` : "";

  async function copyCoords() {
    if (!isCurrent()) return;
    const snapshot = readFreshPosition();
    const coords = snapshot ? `${snapshot.lat.toFixed(5)}, ${snapshot.lng.toFixed(5)}` : "";
    const text = [note.trim(), coords].filter(Boolean).join("\n");
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      if (!isCurrent()) return;
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
    if (!isCurrent()) return;
    if (sending.current) return;
    sending.current = true;
    setState("sending");
    setError(null);
    setRateLimited(false);
    setServerHint("");
    try {
      // Координаты кладём, если они уже есть: ждать GPS в экстренной ситуации нельзя,
      // сигнал без места всё равно лучше, чем ничего. Бронь — если пришли из поездки:
      // по ней сервер говорит дежурному, в какой машине человек.
      const readBody = () => {
        if (!isCurrent()) throw new ApiError(409, "");
        const snapshot = readFreshPosition();
        return { category, note: note.trim(), lat: snapshot?.lat, lng: snapshot?.lng, booking_id: bookingId };
      };
      const r = await sendSos(readBody(), { _generation: generation, readBody });
      if (!isCurrent()) return;
      track("sos");
      const hint = appText(r?.hint_ru ?? "", r?.hint_ba || r?.hint_ru || "").trim();
      setServerHint(hint);
      setState("sent");
    } catch (e) {
      if (!isCurrent()) return;
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { state: { from: sosPath } });
        return;
      }
      setRateLimited(e instanceof ApiError && e.status === 429);
      setError(
        e instanceof ApiError && e.status === 429 ? appText(
          "Не удалось отправить. Позвони в службу выше — это быстрее.",
          "Ебәреп булманы. Юғарыла хеҙмәткә шылтырат — тиҙерәк."
        ) : appText(
          "Не удалось подтвердить отправку. Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.",
          "Ебәреүҙе раҫлап булманы. Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат."
        )
      );
      setState("error");
    } finally {
      sending.current = false;
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

        {/* Сообщить поддержке — требует входа. SMS близким не обещаем: на проде канал выключен. */}
        <div className="sos-notify">
          <strong>{appText("Сообщить поддержке", "Ярҙамға хәбәр итеү")}</strong>
          <p>
            {isAuthed
              ? appText(
                  "Сигнал будет отправлен в Юлдаш. Близким позвони сам, если можешь.",
                  "Сигнал Юлдашҡа ебәреләсәк. Мөмкин булһа, яҡындарыңа үҙең шылтырат."
                )
              : appText(
                  "Для сигнала поддержке нужно войти. Звонок 112 работает без входа.",
                  "Ярҙамға сигнал өсөн инергә кәрәк. 112 шылтырауы инеүһеҙ эшләй."
                )}
          </p>
        </div>

        {!isAuthed ? (
          <button type="button" className="btn-primary submit-btn" onClick={() => navigate("/login", { state: { from: sosPath } })}>
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
                    "Сигнал сохранён в Юлдаш. Не жди ответа — если можешь, позвони 112 и близким сам.",
                    "Сигнал Юлдашта һаҡланды. Яуап көтмә — мөмкин булһа, 112-гә һәм яҡындарыңа үҙең шылтырат."
                  )}
                </span>
                {/* Что сервер честно сказал про рассылку близким (канал выключен / потолок за час). */}
                {serverHint && <span>{serverHint}</span>}
              </div>
            )}
            {state === "error" && (
              <div className="sos-info sos-info--bad">
                <strong>{rateLimited
                  ? appText("Сигнал не отправлен", "Сигнал ебәрелмәне")
                  : appText("Не удалось подтвердить отправку", "Ебәреүҙе раҫлап булманы")}</strong>
                <span>{error ?? appText("Похоже, нет сети. Проверь связь и нажми ещё раз.", "Бәйләнеш юҡ кеүек. Тикшереп, тағы баҫ.")}</span>
              </div>
            )}
            <button type="button" className="btn-danger submit-btn" onClick={fire} disabled={state === "sending"}>
              {state === "sending" ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Сообщить поддержке", "Ярҙамға хәбәр итеү")}
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
