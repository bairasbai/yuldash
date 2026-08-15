// ================================================================
//  Редактор объявления (ads.py, self-serve). RequireAuth.
//  /ads/new — создать; /ads/:id/edit — править черновик/отклонённое.
//  Форма: заголовок / текст / кнопка / ссылка-цель / города (CSV) +
//  выбор пакета (город/маршрут/главный из AD_PACKAGES — цена и срок).
//  «Сохранить черновик» (POST /ads или /ads/{id}) и «На модерацию»
//  (…/submit). Оплата и запуск — после одобрения, в кабинете /ads.
//  Появится на проде после мержа release → мягкая деградация 404/405.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdPackages,
  fetchAdsMine,
  createAd,
  updateAd,
  submitAd,
  type AdPackage,
  type AdMine,
} from "../api/ads";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconMap, IconMegaphone, IconWarn } from "../components/Icons";

import { pluralRu } from "../utils/format";
type Status = "loading" | "error" | "soon" | "ready";

export default function AdEditorScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const isEdit = Boolean(id);

  const [status, setStatus] = useState<Status>("loading");
  const [packages, setPackages] = useState<AdPackage[]>([]);

  // Поля формы.
  const [title, setTitle] = useState("");
  const [text, setText] = useState("");
  const [button, setButton] = useState("");
  const [target, setTarget] = useState("");
  const [cities, setCities] = useState("");
  const [pkg, setPkg] = useState<string>("");
  // id создаётся при первом сохранении нового объявления (дальше правим его же).
  const [adId, setAdId] = useState<string | null>(id ?? null);

  const [busy, setBusy] = useState<"draft" | "submit" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      setStatus("loading");
      Promise.all([
        fetchAdPackages(signal),
        isEdit ? fetchAdsMine(signal) : Promise.resolve<AdMine[]>([]),
      ])
        .then(([pk, mine]) => {
          setPackages(pk);
          if (pk.length && !pkg) setPkg(pk[0].code);
          if (isEdit) {
            const ad = mine.find((a) => a.id === id);
            if (ad) {
              setTitle(ad.title);
              setText(ad.text);
              setButton(ad.button);
              setTarget(ad.target);
              setCities(ad.cities.join(", "));
              if (ad.package) setPkg(ad.package);
              setAdId(ad.id);
            }
          }
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus(
            e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error"
          );
        });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [id, isEdit]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const body = useMemo(
    () => ({
      title: title.trim(),
      text: text.trim(),
      button: button.trim(),
      target: target.trim(),
      cities: cities.trim(),
      package: pkg,
    }),
    [title, text, button, target, cities, pkg]
  );

  const canSave = title.trim().length > 0 && pkg.length > 0 && busy === null;

  /** Сохранить (создать или обновить) и вернуть актуальный id. */
  async function ensureSaved(): Promise<string> {
    if (adId) {
      const ad = await updateAd(adId, body);
      return ad.id;
    }
    const ad = await createAd(body);
    setAdId(ad.id);
    return ad.id;
  }

  async function onSaveDraft() {
    if (!canSave) return;
    setBusy("draft");
    setError(null);
    try {
      await ensureSaved();
      navigate("/ads");
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Попробуй снова.", "Һаҡларға булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(null);
    }
  }

  async function onSubmit() {
    if (!canSave) return;
    setBusy("submit");
    setError(null);
    try {
      const savedId = await ensureSaved();
      await submitAd(savedId);
      navigate("/ads");
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось отправить. Попробуй снова.", "Ебәрергә булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <SubHeader
        title={isEdit ? appText("Правка объявления", "Иғланды төҙәтеү") : appText("Новое объявление", "Яңы иғлан")}
        subtitle={appText("Покажем твой бизнес нужным людям", "Бизнесыңды кәрәкле кешеләргә күрһәтәбеҙ")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon"><IconMegaphone size={34} /></div>
          <h2>{appText("Реклама скоро", "Реклама тиҙҙән")}</h2>
          <p>{appText("Кабинет рекламы включится после обновления сервиса.", "Реклама кабинеты яңыртыуҙан һуң эшләй башлар.")}</p>
        </div>
      )}

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
          <div className="form">
            <label className="field">
              <span className="field__label">{appText("Заголовок", "Исем")}</span>
              <input
                className="field__input"
                type="text"
                maxLength={120}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={appText("Например, Шиномонтаж у трассы", "Мәҫәлән, Юл буйындағы шиномонтаж")}
              />
            </label>

            <label className="field">
              <span className="field__label">{appText("Текст", "Текст")}</span>
              <textarea
                className="field__input field__area"
                maxLength={2000}
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={appText("Коротко о предложении для попутчиков", "Юлдаштар өсөн тәҡдим тураһында ҡыҫҡаса")}
              />
            </label>

            <div className="field-row">
              <label className="field" style={{ flex: 1 }}>
                <span className="field__label">{appText("Текст кнопки", "Кнопка тексты")}</span>
                <input
                  className="field__input"
                  type="text"
                  maxLength={60}
                  value={button}
                  onChange={(e) => setButton(e.target.value)}
                  placeholder={appText("Позвонить", "Шылтыратыу")}
                />
              </label>
            </div>

            <label className="field">
              <span className="field__label">{appText("Куда ведёт (ссылка или телефон)", "Ҡайҙа алып бара (һылтанма йәки телефон)")}</span>
              <input
                className="field__input"
                type="text"
                maxLength={300}
                value={target}
                onChange={(e) => setTarget(e.target.value)}
                placeholder="tel:+7… / https://…"
              />
            </label>

            <label className="field">
              <span className="field__label">{appText("Города показа (через запятую)", "Күрһәтеү ҡалалары (өтөр аша)")}</span>
              <input
                className="field__input"
                type="text"
                maxLength={500}
                value={cities}
                onChange={(e) => setCities(e.target.value)}
                placeholder={appText("Пусто — все города", "Буш — бар ҡалалар")}
              />
            </label>
          </div>

          {/* Пакет размещения */}
          <h2 className="section-title">{appText("Пакет размещения", "Урынлаштырыу пакеты")}</h2>
          <div className="plan-grid">
            {packages.map((p) => (
              <button
                key={p.code}
                type="button"
                className={"plan-card" + (pkg === p.code ? " is-active" : "")}
                onClick={() => setPkg(p.code)}
              >
                <div className="plan-card__icon"><IconMap size={22} /></div>
                <div className="plan-card__title">{ru ? p.title : p.title_ba}</div>
                <div className="plan-card__hours">
                  {appText(
                    `${p.period_days} ${pluralRu(p.period_days, "день", "дня", "дней")}`,
                    `${p.period_days} көн`
                  )}
                </div>
                <div className="plan-card__price">{(p.amount_kop / 100).toLocaleString("ru-RU")} ₽</div>
              </button>
            ))}
          </div>

          {error && <div className="auth__error">{error}</div>}

          <button type="button" className="btn-primary submit-btn" onClick={onSubmit} disabled={!canSave}>
            {busy === "submit"
              ? appText("Отправляем…", "Ебәрәбеҙ…")
              : appText("Отправить на модерацию", "Модерацияға ебәреү")}
          </button>
          <button
            type="button"
            className="btn-soft"
            style={{ width: "100%", marginTop: 10, minHeight: 50 }}
            onClick={onSaveDraft}
            disabled={!canSave}
          >
            {busy === "draft" ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить черновик", "Ҡараламаны һаҡлау")}
          </button>

          <p className="receipt__foot">
            {appText(
              "Сначала объявление проверит модератор. После одобрения оплатишь размещение в кабинете рекламы — и оно пойдёт в показ.",
              "Башта иғланды модератор тикшерә. Раҫланғас, реклама кабинетында урынлаштырыуҙы түләйһең — һәм ул күрһәтелә башлай."
            )}
          </p>
        </>
      )}
    </>
  );
}
