// ================================================================
//  «Проверка водителя» / стать водителем. RequireAuth.
//  Правила + данные авто + загрузка фото прав/авто
//  (POST /upload/photo multipart `file` → защищённый url) →
//  отправка на проверку (POST /driver/verify {license_url, car_photo_url}).
//  Статус проверки (pending/verified/rejected) — из GET /driver/status.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverStatus,
  uploadDoc,
  submitDriverVerify,
  setDriverProfile,
  type DriverStatus,
} from "../api/driver";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconCamera, IconShield, IconClock, IconWarn } from "../components/Icons";

type Status = "loading" | "error" | "ready";

/** Один слот загрузки фото (права / авто). */
function PhotoSlot({
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
          e.target.value = ""; // позволить выбрать тот же файл снова
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

export default function VerifyDriverScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [driver, setDriver] = useState<DriverStatus | null>(null);

  const [licenseUrl, setLicenseUrl] = useState("");
  const [carPhotoUrl, setCarPhotoUrl] = useState("");
  const [uploadingLic, setUploadingLic] = useState(false);
  const [uploadingCar, setUploadingCar] = useState(false);

  const [carMake, setCarMake] = useState("");
  const [carModel, setCarModel] = useState("");
  const [carPlate, setCarPlate] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchDriverStatus(signal)
      .then((st) => {
        setDriver(st);
        setLicenseUrl(st.license_url || "");
        setCarPhotoUrl(st.car_photo_url || "");
        setCarMake(st.car_make || "");
        setCarModel(st.car_model || "");
        setCarPlate(st.car_plate || "");
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function pickLicense(f: File) {
    setUploadingLic(true);
    setError(null);
    try {
      const { url } = await uploadDoc(f);
      setLicenseUrl(url);
    } catch (e) {
      setError(uploadErr(e));
    } finally {
      setUploadingLic(false);
    }
  }
  async function pickCar(f: File) {
    setUploadingCar(true);
    setError(null);
    try {
      const { url } = await uploadDoc(f);
      setCarPhotoUrl(url);
    } catch (e) {
      setError(uploadErr(e));
    } finally {
      setUploadingCar(false);
    }
  }
  function uploadErr(e: unknown): string {
    return e instanceof ApiError && e.message
      ? e.message
      : appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләргә булманы. Ҡабат ҡара."); // DRAFT
  }

  const canSubmit = !!licenseUrl && !!carPhotoUrl && !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      // Сначала сохраняем данные авто (если заполнены) — админ увидит их в заявке.
      if (carMake || carModel || carPlate) {
        await setDriverProfile({
          car_make: carMake.trim(),
          car_model: carModel.trim(),
          car_plate: carPlate.trim(),
        }).catch(() => undefined); // не критично для проверки
      }
      await submitDriverVerify({ license_url: licenseUrl, car_photo_url: carPhotoUrl });
      setSubmitted(true);
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

  // Уже проверен или только что отправлено — показываем статус.
  const docs = submitted ? "pending" : driver?.docs_status;

  if (status === "ready" && (docs === "verified" || docs === "pending")) {
    const verified = docs === "verified";
    return (
      <>
        <SubHeader title={appText("Проверка водителя", "Йөрөтөүсе тикшереүе")} onBack={() => navigate("/driver")} />
        <div className="state">
          <div className="state__icon">{verified ? <IconCheck size={34} /> : <IconClock size={34} />}</div>
          <h2>
            {verified
              ? appText("Ты проверенный водитель", "Һин тикшерелгән йөрөтөүсе")
              : appText("Документы на проверке", "Документтар тикшереүҙә")}
          </h2>
          <p>
            {verified
              ? appText(
                  "Бейдж «Проверен» уже виден пассажирам. Спасибо за доверие 🤝",
                  "«Тикшерелгән» билдәһе юлаусыларға күренә инде. Ышаныс өсөн рәхмәт 🤝"
                )
              : appText(
                  "Мы проверим фото и данные. Обычно это недолго — пришлём уведомление.",
                  "Фото һәм мәғлүмәтте тикшерәбеҙ. Ғәҙәттә оҙаҡ түгел — хәбәр итәбеҙ."
                )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/driver")}>
            {appText("В кабинет водителя", "Йөрөтөүсе кабинетына")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Стать водителем", "Йөрөтөүсе булыу")}
        subtitle={appText("Проверка — это доверие «между своими»", "Тикшереү — был «үҙебеҙ араһында» ышаныс")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && (
        <>
          {docs === "rejected" && (
            <div className="consents__status" style={{ marginTop: 12 }}>
              <IconWarn size={15} /> {appText(
                "Прошлая проверка не пройдена. Проверь, что фото чёткие, и отправь снова.",
                "Үткән тикшереү үтмәне. Фото асыҡ булһын, ҡабат ебәр."
              )}
              {/* Отказ без совета — тупик: человек шлёт то же самое фото по второму
                  разу и снова получает отказ. Говорим, что именно проверить. */}
              <p className="verify-hint">
                {appText(
                  "Сделай фото прав чётким: хорошо освещено, без бликов, номер и срок читаются.",
                  "Права фотоһын асыҡ яһа: яҡшы яҡтыртылған, ялтырауһыҙ, номер һәм ваҡыт уҡыла."
                )}
              </p>
            </div>
          )}

          {/* Правила */}
          <div className="verify-rules">
            <div className="verify-rules__head">
              <IconShield size={22} />
              {appText("Что нужно", "Ни кәрәк")}
            </div>
            <ul>
              <li>{appText("Действующие водительские права", "Ғәмәлдәге йөрөтөүсе таныҡлығы")}</li>
              <li>{appText("Фото автомобиля целиком", "Автомобилдең тулы фотоһы")}</li>
              <li>{appText("Данные совпадают с документами", "Мәғлүмәт документтарға тап килә")}</li>
            </ul>
            <p>
              {appText(
                "Фото видят только модератор и ты. Мы не публикуем документы и телефон.",
                "Фотоны тик модератор һәм һин күрә. Документ һәм телефон баҫылмай."
              )}
            </p>
          </div>

          {/* Загрузка фото */}
          <PhotoSlot
            title={appText("Водительские права", "Водитель праваһы")}
            hint={appText("Нажми, чтобы выбрать фото", "Фото һайлар өсөн баҫ")}
            url={licenseUrl}
            uploading={uploadingLic}
            onPick={pickLicense}
          />
          <PhotoSlot
            title={appText("Фото автомобиля", "Автомобиль фотоһы")}
            hint={appText("Машина целиком, виден госномер", "Машина тулыһынса, номер күренә")}
            url={carPhotoUrl}
            uploading={uploadingCar}
            onPick={pickCar}
          />

          {/* Данные авто (необязательно, но помогает проверке) */}
          <h2 className="section-title">{appText("Об автомобиле", "Автомобиль тураһында")}</h2>
          <div className="field-row">
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Марка", "Марка")}</span>
              <input className="field__input" value={carMake} onChange={(e) => setCarMake(e.target.value)}
                placeholder={appText("Лада", "Лада")} autoComplete="off" />
            </label>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Модель", "Модель")}</span>
              <input className="field__input" value={carModel} onChange={(e) => setCarModel(e.target.value)}
                placeholder={appText("Веста", "Веста")} autoComplete="off" />
            </label>
          </div>
          <label className="field">
            <span className="field__label">{appText("Госномер", "Дәүләт номеры")}</span>
            <input className="field__input" value={carPlate} onChange={(e) => setCarPlate(e.target.value)}
              placeholder="А123ВС 102" autoComplete="off" />
          </label>

          {error && <div className="auth__error">{error}</div>}

          <button type="button" className="btn-primary submit-btn" onClick={submit} disabled={!canSubmit}>
            {busy ? (
              appText("Отправляем…", "Ебәрәбеҙ…")
            ) : (
              <>
                <IconCheck size={18} /> {appText("Отправить на проверку", "Тикшереүгә ебәреү")}
              </>
            )}
          </button>
        </>
      )}
    </>
  );
}
