// ================================================================
//  «Стать курьером Юлдаша» (C1). RequireAuth → /courier-onboarding
//  (зеркало backend routers/courier.py: GET /courier/application,
//  POST /courier/apply).
//
//  Правила + выбор транспорта (авто/грузовой) + селфи с документом
//  (аплоад через POST /upload/photo) + статус заявки
//  (pending/approved/rejected + причина).
//
//  Мягкая деградация: courier/* появятся на проде после мержа release
//  → 404/403 ловим спокойным состоянием без краша.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { uploadDoc } from "../api/driver";
import {
  fetchCourierApplication,
  applyCourier,
  type CourierApplication,
  type CourierTransport,
} from "../api/courier";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import { IconCheck, IconCamera, IconShield, IconBox } from "../components/Icons";

type Boot = "loading" | "error" | "ready";

export default function CourierOnboardingScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [app, setApp] = useState<CourierApplication | null>(null);
  const [editing, setEditing] = useState(false);

  // Форма.
  const [transport, setTransport] = useState<CourierTransport>("car");
  const [selfieUrl, setSelfieUrl] = useState("");
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCourierApplication(signal)
      .then((r) => {
        setApp(r.application);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Эндпоинта ещё нет на проде (404) → пускаем к форме (мягко).
        if (e instanceof ApiError && e.status === 404) {
          setApp(null);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function pickSelfie(f: File) {
    setUploading(true);
    setError(null);
    try {
      const { url } = await uploadDoc(f);
      setSelfieUrl(url);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось загрузить фото. Попробуй снова.", "Фотоны йөкләргә булманы. Ҡабат ҡара.")
      );
    } finally {
      setUploading(false);
    }
  }

  const canSubmit = !!selfieUrl && !busy && !uploading;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const a = await applyCourier({ transport, selfie_url: selfieUrl });
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
        <SubHeader title={appText("Стать курьером", "Курьер булыу")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }

  if (boot === "error") {
    return (
      <>
        <SubHeader title={appText("Стать курьером", "Курьер булыу")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">📡</div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
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
        emoji: "🎉",
        title: appText("Ты курьер Юлдаша!", "Һин Юлдаш курьеры!"),
        body: appText(
          "Заявка одобрена. Выходи на линию — бери доставки рядом и зарабатывай.",
          "Ғариза хупланды. Линияға сыҡ — яҡындағы доставкаларҙы ал һәм эшлә."
        ),
      },
      pending: {
        emoji: "⏳",
        title: appText("Заявка на проверке", "Ғариза тикшереүҙә"),
        body: appText(
          "Мы проверяем селфи и приглашение. Обычно это недолго — пришлём уведомление.",
          "Селфи менән саҡырыуҙы тикшерәбеҙ. Ғәҙәттә оҙаҡ түгел — хәбәр итәбеҙ."
        ),
      },
      rejected: {
        emoji: "⚠️",
        title: appText("Заявка отклонена", "Ғариза кире ҡағылды"),
        body: appText(
          "Поправь фото и подай снова.",
          "Фотоны төҙәт тә ҡабат ебәр."
        ),
      },
    }[st as "approved" | "pending" | "rejected"];

    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 36 }}>
          <div className="state__emoji">{map.emoji}</div>
          <h2>{map.title}</h2>
          <p>{map.body}</p>
          {st === "rejected" && app.reject_reason && (
            <div className="consents__status" style={{ marginTop: 4 }}>
              ⚠️ {app.reject_reason}
            </div>
          )}
          {st === "approved" ? (
            <button type="button" className="btn-primary" onClick={() => navigate("/courier")}>
              {appText("Выйти на линию", "Линияға сығыу")}
            </button>
          ) : st === "rejected" ? (
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                setTransport((app.transport as CourierTransport) || "car");
                setEditing(true);
              }}
            >
              {appText("Подать снова", "Ҡабат ебәреү")}
            </button>
          ) : (
            <button type="button" className="btn-soft" onClick={() => navigate("/parcels")}>
              {appText("К посылкам", "Бандеролдәргә")}
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
        title={appText("Стать курьером Юлдаша", "Юлдаш курьеры булыу")}
        subtitle={appText("Доставка между своими · честная комиссия", "Үҙебеҙ араһында доставка · ғәҙел комиссия")}
        onBack={() => navigate(-1)}
      />

      <div className="verify-rules">
        <div className="verify-rules__head">
          <IconShield size={22} />
          {appText("Как это работает", "Был нисек эшләй")}
        </div>
        <ul>
          <li>{appText("Возишь посылки и товары «между своими»", "«Үҙебеҙ араһында» бандеролдәр һәм тауар йөрөтәһең")}</li>
          <li>{appText("Комиссия маленькая и честная — 3–8% по стажу", "Комиссия бәләкәй һәм ғәҙел — стаж буйынса 3–8%")}</li>
          <li>{appText("Оплата от получателя — напрямую тебе", "Түләү алыусынан — тура һиңә")}</li>
          <li>{appText("Проверка Уровень 1: селфи с документом", "1-се кимәл тикшереү: документ менән селфи")}</li>
        </ul>
        <p>
          {appText(
            "Селфи видит только модератор. Мы не публикуем его и телефон.",
            "Селфины тик модератор күрә. Уны һәм телефонды баҫмайбыҙ."
          )}
        </p>
      </div>

      {/* Транспорт */}
      <span className="field__label" style={{ marginTop: 14, display: "block" }}>
        {appText("На чём возишь", "Нимәлә йөрөтәһең")}
      </span>
      <div className="seg" style={{ marginTop: 6 }}>
        <button
          type="button"
          className={"seg__item" + (transport === "car" ? " is-active" : "")}
          onClick={() => setTransport("car")}
        >
          <span>🚗</span>
          {appText("Легковой", "Еңел авто")}
        </button>
        <button
          type="button"
          className={"seg__item" + (transport === "cargo" ? " is-active" : "")}
          onClick={() => setTransport("cargo")}
        >
          <span>🚚</span>
          {appText("Грузовой", "Йөк авто")}
        </button>
      </div>

      {/* Селфи с документом */}
      <h2 className="section-title">{appText("Проверка", "Тикшереү")}</h2>
      <button
        type="button"
        className={"photo-slot" + (selfieUrl ? " is-done" : "")}
        onClick={() => fileRef.current?.click()}
        disabled={uploading}
      >
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) pickSelfie(f);
            e.target.value = "";
          }}
        />
        <span className="photo-slot__icon">
          {selfieUrl ? <IconCheck size={24} /> : <IconCamera size={24} />}
        </span>
        <span className="photo-slot__text">
          <b>{appText("Селфи с документом", "Документ менән селфи")}</b>
          <span>
            {uploading
              ? appText("Загружаем…", "Йөкләйбеҙ…")
              : selfieUrl
                ? appText("Фото загружено", "Фото йөкләнде")
                : appText("Лицо и паспорт/права в кадре", "Йөҙ һәм паспорт/права кадрҙа")}
          </span>
        </span>
      </button>

      {error && <div className="auth__error">{error}</div>}

      <button
        type="button"
        className="btn-primary submit-btn"
        style={{ marginTop: 14 }}
        onClick={submit}
        disabled={!canSubmit}
      >
        {busy ? (
          appText("Отправляем…", "Ебәрәбеҙ…")
        ) : (
          <>
            <IconBox size={18} /> {appText("Отправить заявку", "Ғариза ебәреү")}
          </>
        )}
      </button>
    </>
  );
}
