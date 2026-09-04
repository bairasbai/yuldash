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
import { IconCheck, IconCamera, IconShield, IconBox, IconCar, IconClock, IconWarn } from "../components/Icons";
import { useDraftSync, clearDraft } from "../utils/formDraft";
import { track } from "../analytics";

type Boot = "loading" | "error" | "ready";

/** Ключ черновика анкеты курьера. */
const COURIER_DRAFT = "courier-application";

export default function CourierOnboardingScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [app, setApp] = useState<CourierApplication | null>(null);
  const [editing, setEditing] = useState(false);

  // Форма.
  const [transport, setTransport] = useState<CourierTransport>("car");
  const [selfieUrl, setSelfieUrl] = useState("");
  // Кто и на чём везёт. Сервер пока принимает мягко, но спрашиваем сразу:
  // человеку доверяют чужую посылку, а по госномеру его узнают у подъезда.
  const [fullName, setFullName] = useState("");
  const [carPlate, setCarPlate] = useState("");
  const [rulesOk, setRulesOk] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // За селфи человек уходит в камеру, и вкладка на айфоне может выгрузиться.
  // Черновик держит анкету на месте, пока она не отправлена.
  useDraftSync(
    COURIER_DRAFT,
    { transport, selfieUrl, fullName, carPlate, rulesOk },
    (d) => {
      if (d.transport) setTransport(d.transport);
      if (d.selfieUrl) setSelfieUrl(d.selfieUrl);
      if (d.fullName) setFullName(d.fullName);
      if (d.carPlate) setCarPlate(d.carPlate);
      if (d.rulesOk) setRulesOk(d.rulesOk);
    }
  );
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
          : appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләргә булманы. Ҡабат ҡара.")
      );
    } finally {
      setUploading(false);
    }
  }

  const canSubmit =
    !!selfieUrl && fullName.trim().split(/\s+/).length >= 2 && rulesOk && !busy && !uploading;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const a = await applyCourier({
        transport,
        selfie_url: selfieUrl,
        full_name: fullName.trim(),
        car_plate: carPlate.trim().toUpperCase(),
        rules_accepted: rulesOk,
      });
      track("courier_apply");
      setApp(a);
      setEditing(false);
      clearDraft(COURIER_DRAFT); // отправлено — черновик больше не нужен
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
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
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
        title: appText("Ты курьер Юлдаша!", "Һин Юлдаш курьеры!"),
        body: appText(
          "Заявка одобрена. Выходи на линию — бери доставки рядом и зарабатывай.",
          "Ғариза хупланды. Линияға сыҡ — яҡындағы доставкаларҙы ал һәм эшлә."
        ),
      },
      pending: {
        Icon: IconClock,
        warn: false,
        title: appText("Заявка на проверке", "Ғариза тикшереүҙә"),
        body: appText(
          "Мы проверяем селфи и приглашение. Обычно это недолго — пришлём уведомление.",
          "Селфи менән саҡырыуҙы тикшерәбеҙ. Ғәҙәттә оҙаҡ түгел — хәбәр итәбеҙ."
        ),
      },
      rejected: {
        Icon: IconWarn,
        warn: true,
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
          <div className={"state__icon" + (map.warn ? " state__icon--warn" : "")}>
            <map.Icon size={34} />
          </div>
          <h2>{map.title}</h2>
          <p>{map.body}</p>
          {st === "rejected" && app.reject_reason && (
            <div className="consents__status" style={{ marginTop: 4 }}>
              <IconWarn size={15} /> {app.reject_reason}
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

      {/* На что человек соглашается. Он берёт в руки чужое — и должен знать
          границы ДО заявки, а не выяснять их в первом же споре. */}
      <div className="verify-rules" style={{ marginTop: 12 }}>
        <div className="verify-rules__head">
          <IconWarn size={22} />
          {appText("Что важно знать заранее", "Алдан белергә кәрәк")}
        </div>

        <div className="courier-rule">
          <b>{appText("Что нельзя возить", "Нимә йөрөтөргә ярамай")}</b>
          <p>
            {appText(
              "Деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие, запрещённое законом.",
              "Аҡса, күрһәтеүсегә документтар, рецепһыҙ дарыуҙар, тиҙ боҙолған аҙыҡ, ҡорал, закон тыйған нәмәләр."
            )}
          </p>
        </div>

        <div className="courier-rule">
          <b>{appText("Ответственность на курьере", "Яуаплылыҡ курьерҙа")}</b>
          <p>
            {appText(
              "Ты отвечаешь за сохранность посылки от приёма до вручения по коду. Береги чужое как своё.",
              "Алғандан алып код буйынса тапшырғанға тиклем бандероль өсөн һин яуаплы. Кеше әйберен үҙеңдеке кеүек һаҡла."
            )}
          </p>
        </div>

        <div className="courier-rule">
          <b>{appText("«Купи и привези» — до 5000 ₽", "«Һатып ал һәм килтер» — 5000 ₽-ға тиклем")}</b>
          <p>
            {appText(
              "Можешь купить товар за клиента и привезти. Лимит покупки — 5000 ₽, чтобы ты не рисковал крупным.",
              "Клиент өсөн тауар һатып алып килтерә алаһың. Лимит — 5000 ₽, ҙур аҡса менән тәүәкәлләмәҫкә."
            )}
          </p>
        </div>
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
          <span><IconCar size={20} /></span>
          {appText("Легковой", "Еңел авто")}
        </button>
        <button
          type="button"
          className={"seg__item" + (transport === "cargo" ? " is-active" : "")}
          onClick={() => setTransport("cargo")}
        >
          <span><IconBox size={20} /></span>
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

      {/* Кто везёт. Имя — как в документе на селфи: модератор сверяет одно с другим. */}
      <label className="field" style={{ marginTop: 14 }}>
        <span className="field__label">{appText("Фамилия и имя как в документе", "Документтағыса фамилия һәм исем")}</span>
        <input
          className="field__input"
          value={fullName}
          maxLength={120}
          onChange={(e) => setFullName(e.target.value)}
          placeholder={appText("Иванов Ринат", "Иванов Ринат")}
          autoComplete="name"
        />
      </label>

      <label className="field">
        <span className="field__label">{appText("Госномер машины", "Машинаның дәүләт номеры")}</span>
        <input
          className="field__input"
          value={carPlate}
          maxLength={16}
          onChange={(e) => setCarPlate(e.target.value.toUpperCase())}
          placeholder="А123ВС102"
          autoComplete="off"
        />
        <span className="field__hint">
          {appText("По нему тебя узнают у подъезда", "Уның буйынса подъезд янында һине таныйҙар")}
        </span>
      </label>

      <label className="admin-check">
        <input type="checkbox" checked={rulesOk} onChange={(e) => setRulesOk(e.target.checked)} />
        <span>{appText("Согласен с правилами доставки", "Илтеү ҡағиҙәләре менән килешәм")}</span>
      </label>
      <p className="courier-rule" style={{ marginTop: 4 }}>
        <span style={{ display: "block" }}>
          {appText(
            "Везу бережно, не вскрываю, запрещённое не беру. Если что-то пошло не так — говорю сразу, а не молчу.",
            "Һаҡ илтәм, асмайым, тыйылғанды алмайым. Берәй нәмә дөрөҫ булмаһа — шунда уҡ әйтәм, өндәшмәй ҡалмайым."
          )}
        </span>
      </p>

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
