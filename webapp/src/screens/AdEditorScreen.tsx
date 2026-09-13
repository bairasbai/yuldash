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
import { RideCardSkeleton } from "../components/States";
import { ListedEmpty, ListedError } from "../components/adminUi";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconMegaphone } from "../components/Icons";
import { kopExactLabel } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

export default function AdEditorScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const isEdit = Boolean(id);

  const [status, setStatus] = useState<Status>("loading");
  const [packages, setPackages] = useState<AdPackage[]>([]);
  const [packagesError, setPackagesError] = useState(false);

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
        fetchAdPackages(signal).catch((e) => {
          if (e instanceof ApiError && (e.status === 404 || e.status === 405)) throw e;
          setPackagesError(true);
          return [] as AdPackage[];
        }),
        isEdit ? fetchAdsMine(signal) : Promise.resolve<AdMine[]>([]),
      ])
        .then(([pk, mine]) => {
          setPackages(pk);
          if (pk.length) setPackagesError(false);
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

  // Как в приложении: черновик — без тарифа, на модерацию — только с тарифом.
  const canDraft = title.trim().length > 0 && busy === null;
  const canSave = canDraft && pkg.length > 0;

  async function reloadPackages() {
    setPackagesError(false);
    try {
      const pk = await fetchAdPackages();
      setPackages(pk);
    } catch {
      setPackagesError(true);
    }
  }

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
    if (!canDraft) return;
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
        title={isEdit ? appText("Изменить объявление", "Иғланды үҙгәртергә") : appText("Новое объявление", "Яңы иғлан")}
        onBack={() => {
          if (!busy) navigate(-1);
        }}
      />
      <div className="alist">
        {status === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}

        {status === "soon" && (
          <ListedEmpty
            icon={<IconMegaphone size={34} />}
            title={appText("Реклама скоро", "Реклама тиҙҙән")}
            subtitle={appText("Кабинет рекламы включится после обновления сервиса.", "Реклама кабинеты яңыртыуҙан һуң эшләй башлар.")}
          />
        )}

        {status === "error" && <ListedError onRetry={() => load()} />}

        {status === "ready" && (
          <>
            <label className="field">
              <span className="field__label">{appText("Заголовок", "Башлыҡ")}</span>
              <input className="field__input" type="text" maxLength={120} value={title} onChange={(e) => setTitle(e.target.value)} />
            </label>
            <label className="field">
              <span className="field__label">{appText("Описание", "Аңлатма")}</span>
              <textarea className="field__input field__area" rows={3} maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} />
            </label>
            <label className="field">
              <span className="field__label">{appText("Текст кнопки (напр. «Позвонить»)", "Төймә тексты (мәҫ. «Шылтыратырға»)")}</span>
              <input className="field__input" type="text" maxLength={60} value={button} onChange={(e) => setButton(e.target.value)} />
            </label>
            <label className="field">
              <span className="field__label">{appText("Ссылка или телефон", "Һылтанма йәки телефон")}</span>
              <input className="field__input" type="text" maxLength={300} value={target} onChange={(e) => setTarget(e.target.value)} />
            </label>
            <label className="field">
              <span className="field__label">{appText("Город(а) через запятую — пусто = все", "Ҡала(лар) өтөр аша — буш = бөтәһе")}</span>
              <input className="field__input" type="text" maxLength={500} value={cities} onChange={(e) => setCities(e.target.value)} />
            </label>

            <strong className="acard__title">{appText("Тариф", "Тариф")}</strong>
            {packages.length === 0 && packagesError && (
              /* Без тарифов «На модерацию» не сработает — честно объясняем и даём повтор. */
              <div className="queue-section queue-section--plain">
                <span className="acard__sub acard__grow">{appText("Не удалось загрузить тарифы", "Тарифтарҙы йөкләп булманы")}</span>
                <button type="button" className="btn-ghost queue-section__go" onClick={() => void reloadPackages()}>
                  {appText("Повторить", "Ҡабатлау")}
                </button>
              </div>
            )}
            <div className="adpkg-list" role="radiogroup" aria-label={appText("Тариф", "Тариф")}>
              {packages.map((p) => {
                const selected = pkg === p.code;
                return (
                  <button key={p.code} type="button" role="radio" aria-checked={selected} className={"adpkg" + (selected ? " is-on" : "")} onClick={() => setPkg(p.code)}>
                    <span className="adpkg__dot" aria-hidden>{selected ? <IconCheck size={20} /> : <i />}</span>
                    <strong>{appText(p.title, p.title_ba || p.title)}</strong>
                    <b>
                      {kopExactLabel(p.amount_kop)} / {p.period_days}
                      {appText(" дн", " көн")}
                    </b>
                  </button>
                );
              })}
            </div>

            {error && <div className="auth__error">{error}</div>}

            <div className="acard__actions">
              <button type="button" className="abtn abtn--52 abtn--outline abtn--body" onClick={onSaveDraft} disabled={!canDraft}>
                {busy === "draft" ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить", "Һаҡларға")}
              </button>
              <button type="button" className="abtn abtn--52 abtn--body" onClick={onSubmit} disabled={!canSave}>
                {busy === "submit" ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("На модерацию", "Модерацияға")}
              </button>
            </div>
            <small className="acard__date">
              {appText(
                "После отправки объявление проверит модератор. Затем оплатишь размещение — и оно пойдёт в показы.",
                "Ебәргәс, иғланды модератор тикшерә. Аҙаҡ урынлаштырыуҙы түләйһең — һәм ул күрһәтелә башлай."
              )}
            </small>
          </>
        )}
      </div>
    </>
  );
}
