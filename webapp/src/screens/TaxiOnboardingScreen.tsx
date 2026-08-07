// ================================================================
//  «Стать таксистом Юлдаша» (580-ФЗ). RequireAuth → /taxi-onboarding
//  (зеркало backend routers/taxi.py: GET /taxi/application,
//  POST /taxi/apply, GET /instant/availability для гейта города).
//
//  Правила + заявка (ИНН, разрешение, ОСАГО, селфи, класс авто) +
//  статус проверки (pending/approved/rejected + причина).
//  Гейт «Такси скоро в вашем городе», если taxi выключено.
//
//  Фото грузим через POST /upload/photo (uploadDoc) → защищённый url.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { uploadDoc } from "../api/driver";
import {
  fetchTaxiApplication,
  applyTaxi,
  fetchTaxiAvailability,
  type TaxiApplication,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import { IconCheck, IconCamera, IconShield, IconWarn, IconCar, IconClock } from "../components/Icons";

type Boot = "loading" | "error" | "gate" | "ready";

/** Слот загрузки документа (переиспользуем паттерн проверки водителя). */
function DocSlot({
  title,
  hint,
  url,
  uploading,
  onPick,
}: {
  title: string;
  hint: string;
  url: string;
  uploading: boolean;
  onPick: (f: File) => void;
}) {
  const { appText } = useLang();
  const ref = useRef<HTMLInputElement>(null);
  return (
    <button
      type="button"
      className={"photo-slot" + (url ? " is-done" : "")}
      onClick={() => ref.current?.click()}
      disabled={uploading}
    >
      <input
        ref={ref}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onPick(f);
          e.target.value = "";
        }}
      />
      <span className="photo-slot__icon">
        {url ? <IconCheck size={24} /> : <IconCamera size={24} />}
      </span>
      <span className="photo-slot__text">
        <b>{title}</b>
        <span>
          {uploading
            ? appText("Загружаем…", "Йөкләйбеҙ…")
            : url
              ? appText("Фото загружено", "Фото йөкләнде")
              : hint}
        </span>
      </span>
    </button>
  );
}

export default function TaxiOnboardingScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [gateMsg, setGateMsg] = useState<{ ru: string; ba: string } | null>(null);
  const [app, setApp] = useState<TaxiApplication | null>(null);
  const [editing, setEditing] = useState(false);

  // Форма.
  const [inn, setInn] = useState("");
  const [permit, setPermit] = useState("");
  const [birth, setBirth] = useState("");
  const [licenseYear, setLicenseYear] = useState("");
  const [carClass, setCarClass] = useState<"economy" | "comfort">("economy");
  const [permitUrl, setPermitUrl] = useState("");
  const [osagoUrl, setOsagoUrl] = useState("");
  const [selfieUrl, setSelfieUrl] = useState("");
  const [uploading, setUploading] = useState<"" | "permit" | "osago" | "selfie">("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    // Сначала гейт города, затем заявка.
    fetchTaxiAvailability(undefined, undefined, signal)
      .then((av) => {
        if (!av.enabled) {
          setGateMsg(av.message);
          setBoot("gate");
          return;
        }
        return fetchTaxiApplication(signal)
          .then((a) => {
            setApp(a);
            setBoot("ready");
          })
          .catch((e) => {
            if (e instanceof ApiError && e.status === 404) {
              setApp(null); // ещё не подавал
              setBoot("ready");
            } else throw e;
          });
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Гейт-эндпоинта нет на проде → пускаем к форме (мягко).
        if (e instanceof ApiError && e.status === 404) {
          fetchTaxiApplication(signal)
            .then((a) => {
              setApp(a);
              setBoot("ready");
            })
            .catch(() => {
              setApp(null);
              setBoot("ready");
            });
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function pick(kind: "permit" | "osago" | "selfie", f: File) {
    setUploading(kind);
    setError(null);
    try {
      const { url } = await uploadDoc(f);
      if (kind === "permit") setPermitUrl(url);
      else if (kind === "osago") setOsagoUrl(url);
      else setSelfieUrl(url);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось загрузить фото. Попробуй снова.", "Фотоны йөкләргә булманы. Ҡабат ҡара.")
      );
    } finally {
      setUploading("");
    }
  }

  const canSubmit =
    inn.trim().length >= 10 && permit.trim() && birth && licenseYear.trim() && !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const a = await applyTaxi({
        inn: inn.trim(),
        permit_number: permit.trim(),
        birth_date: birth,
        license_since_year: Number(licenseYear),
        permit_photo_url: permitUrl,
        osago_url: osagoUrl,
        selfie_url: selfieUrl,
        car_class: carClass,
      });
      setApp(a);
      setEditing(false);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось отправить заявку. Попробуй снова.", "Ғаризаны ебәрергә булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  // ---------------- Состояния ----------------
  if (boot === "loading") {
    return (
      <>
        <SubHeader title={appText("Стать таксистом", "Таксист булыу")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }

  if (boot === "error") {
    return (
      <>
        <SubHeader title={appText("Стать таксистом", "Таксист булыу")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      </>
    );
  }

  if (boot === "gate") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon"><IconCar size={34} /></div>
          <h2>{appText("Такси скоро в вашем городе", "Такси тиҙҙән ҡалағыҙҙа")}</h2>
          <p>
            {gateMsg
              ? gateMsg.ru
              : appText(
                  "Мы запускаем такси Юлдаша по городам постепенно. Оставайся с нами — скоро откроем и у тебя.",
                  "Юлдаш таксиһын ҡалалар буйынса аҫтабан асабыҙ. Беҙҙең менән ҡал — тиҙҙән һиндә лә асыла."
                )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/driver")}>
            {appText("В кабинет водителя", "Водитель кабинетына")}
          </button>
        </div>
      </>
    );
  }

  // Статус: заявка уже подана и не в режиме редактирования.
  const st = app?.status;
  if (app && !editing && (st === "pending" || st === "approved" || st === "rejected")) {
    const map = {
      approved: {
        Icon: IconCheck,
        warn: false,
        title: appText("Ты в такси Юлдаша!", "Һин Юлдаш таксиһында!"),
        body: appText(
          "Заявка одобрена. Выходи на линию — принимай быстрые заказы рядом.",
          "Ғариза хупланды. Линияға сыҡ — яҡындағы тиҙ заказдарҙы ал."
        ),
      },
      pending: {
        Icon: IconClock,
        warn: false,
        title: appText("Заявка на проверке", "Ғариза тикшереүҙә"),
        body: appText(
          "Мы проверяем документы. Обычно это недолго — пришлём уведомление.",
          "Документтарҙы тикшерәбеҙ. Ғәҙәттә оҙаҡ түгел — хәбәр итәбеҙ."
        ),
      },
      rejected: {
        Icon: IconWarn,
        warn: true,
        title: appText("Заявка отклонена", "Ғариза кире ҡағылды"),
        body: appText(
          "Поправь данные или документы и подай снова.",
          "Мәғлүмәтте йәки документтарҙы төҙәт тә ҡабат ебәр."
        ),
      },
    }[st as "approved" | "pending" | "rejected"];

    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 36 }}>
          <div className={"state__icon" + (map.warn ? " state__icon--warn" : "")}>
            <map.Icon size={34} />
          </div>
          <h2>{map.title}</h2>
          <p>{map.body}</p>
          {st === "rejected" && app.comment && (
            <div className="consents__status" style={{ marginTop: 4 }}>
              <IconWarn size={15} /> {app.comment}
            </div>
          )}
          {st === "approved" ? (
            <button type="button" className="btn-primary" onClick={() => navigate("/taxi-drive")}>
              {appText("Выйти на линию", "Линияға сығыу")}
            </button>
          ) : st === "rejected" ? (
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                // Предзаполним форму из отклонённой заявки.
                setInn(app.inn || "");
                setPermit(app.permit_number || "");
                setBirth(app.birth_date || "");
                setLicenseYear(app.license_since_year ? String(app.license_since_year) : "");
                setEditing(true);
              }}
            >
              {appText("Подать снова", "Ҡабат ебәреү")}
            </button>
          ) : (
            <button type="button" className="btn-soft" onClick={() => navigate("/driver")}>
              {appText("В кабинет водителя", "Водитель кабинетына")}
            </button>
          )}
        </div>
      </>
    );
  }

  // Форма заявки.
  return (
    <>
      <SubHeader
        title={appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу")}
        subtitle={appText("По 580-ФЗ · документы под защитой", "580-ФЗ буйынса · документтар яҡлауҙа")}
        onBack={() => navigate(-1)}
      />

      <div className="verify-rules">
        <div className="verify-rules__head">
          <IconShield size={22} />
          {appText("Что понадобится", "Ни кәрәк")}
        </div>
        <ul>
          <li>{appText("ИНН (10–12 цифр)", "ИНН (10–12 һан)")}</li>
          <li>{appText("Номер разрешения на такси", "Такси рөхсәте номеры")}</li>
          <li>{appText("Стаж вождения от 2 лет, возраст 20+", "Йөрөтөү стажы 2 йылдан, йәш 20+")}</li>
          <li>{appText("Фото разрешения и ОСАГО", "Рөхсәт һәм ОСАГО фотоһы")}</li>
        </ul>
        <p>
          {appText(
            "Документы видит только модератор. Мы не публикуем их и телефон.",
            "Документтарҙы тик модератор күрә. Уларҙы һәм телефонды баҫмайбыҙ."
          )}
        </p>
      </div>

      <label className="field">
        <span className="field__label">{appText("ИНН", "ИНН")}</span>
        <input
          className="field__input"
          value={inn}
          onChange={(e) => setInn(e.target.value.replace(/\D/g, ""))}
          inputMode="numeric"
          placeholder="1234567890"
          autoComplete="off"
        />
      </label>

      <label className="field" style={{ marginTop: 10 }}>
        <span className="field__label">{appText("Номер разрешения на такси", "Такси рөхсәте номеры")}</span>
        <input
          className="field__input"
          value={permit}
          onChange={(e) => setPermit(e.target.value)}
          placeholder="АА-102-000123"
          autoComplete="off"
        />
      </label>

      <div className="field-row" style={{ marginTop: 10 }}>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Дата рождения", "Тыуған көн")}</span>
          <input className="field__input" type="date" value={birth} onChange={(e) => setBirth(e.target.value)} />
        </label>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Права с года", "Права алған йыл")}</span>
          <input
            className="field__input"
            value={licenseYear}
            onChange={(e) => setLicenseYear(e.target.value.replace(/\D/g, "").slice(0, 4))}
            inputMode="numeric"
            placeholder="2015"
          />
        </label>
      </div>

      {/* Класс авто */}
      <span className="field__label" style={{ marginTop: 12, display: "block" }}>
        {appText("Класс автомобиля", "Автомобиль класы")}
      </span>
      <div className="taxi-when" style={{ marginTop: 6 }}>
        <button
          type="button"
          className={"taxi-when__tab" + (carClass === "economy" ? " is-active" : "")}
          onClick={() => setCarClass("economy")}
        >
          {appText("Эконом", "Эконом")}
        </button>
        <button
          type="button"
          className={"taxi-when__tab" + (carClass === "comfort" ? " is-active" : "")}
          onClick={() => setCarClass("comfort")}
        >
          {appText("Комфорт", "Комфорт")}
        </button>
      </div>

      {/* Документы */}
      <h2 className="section-title">{appText("Документы", "Документтар")}</h2>
      <DocSlot
        title={appText("Разрешение на такси", "Такси рөхсәте")}
        hint={appText("Фото разрешения", "Рөхсәт фотоһы")}
        url={permitUrl}
        uploading={uploading === "permit"}
        onPick={(f) => pick("permit", f)}
      />
      <DocSlot
        title={appText("Полис ОСАГО", "ОСАГО полисы")}
        hint={appText("Фото полиса", "Полис фотоһы")}
        url={osagoUrl}
        uploading={uploading === "osago"}
        onPick={(f) => pick("osago", f)}
      />
      <DocSlot
        title={appText("Селфи с правами", "Права менән селфи")}
        hint={appText("Необязательно, но ускорит проверку", "Мотлаҡ түгел, әммә тикшереүҙе тиҙләтә")}
        url={selfieUrl}
        uploading={uploading === "selfie"}
        onPick={(f) => pick("selfie", f)}
      />

      {error && <div className="auth__error">{error}</div>}

      <button type="button" className="btn-primary submit-btn" style={{ marginTop: 14 }} onClick={submit} disabled={!canSubmit}>
        {busy ? (
          appText("Отправляем…", "Ебәрәбеҙ…")
        ) : (
          <>
            <IconCheck size={18} /> {appText("Отправить заявку", "Ғариза ебәреү")}
          </>
        )}
      </button>
    </>
  );
}
