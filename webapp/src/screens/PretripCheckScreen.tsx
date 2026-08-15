// ================================================================
//  «Готовность к работе» на сегодня (580-ФЗ) — GET/POST /taxi/pretrip.
//  Зеркало Android TaxiDocsScreens.kt (часть PretripCheck).
//
//  Честно называем это самодекларацией, а не медосмотром: медцентра
//  у платформы нет. Но осознанное действие работает и без врача,
//  а при разборе ДТП видно, что человек заявил в этот день.
//  Все три пункта обязательны — «частично готов» это не готов.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { confirmPretrip, fetchPretrip, type PretripState } from "../api/instant";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconHeart, IconShield, IconWheel } from "../components/Icons";
import { formatWhen } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

export default function PretripCheckScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [state, setState] = useState<PretripState | null>(null);
  const [health, setHealth] = useState(false);
  const [car, setCar] = useState(false);
  const [sober, setSober] = useState(false);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchPretrip(signal)
      .then((s) => {
        setState(s);
        setNote(s.note || "");
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && (e.status === 404 || e.status === 403) ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const allChecked = health && car && sober;

  async function confirm() {
    if (!allChecked || busy) return;
    setBusy(true);
    setErr("");
    try {
      const s = await confirmPretrip({
        health_ok: health,
        car_ok: car,
        no_alcohol: sober,
        note: note.trim().slice(0, 300),
      });
      setState(s);
    } catch {
      setErr(appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла."));
    } finally {
      setBusy(false);
    }
  }

  const items: { on: boolean; set: (v: boolean) => void; title: string; sub: string; icon: JSX.Element }[] = [
    {
      on: health,
      set: setHealth,
      title: appText("Чувствую себя хорошо", "Үҙемде яҡшы тоям"),
      sub: appText("Выспался, могу вести машину", "Йоҡлағанмын, машина йөрөтә алам"),
      icon: <IconHeart size={20} />,
    },
    {
      on: car,
      set: setCar,
      title: appText("Машина исправна", "Машина төҙөк"),
      sub: appText("Тормоза, свет, резина, стёкла — в порядке", "Тормоз, ут, резина, быяла — тәртиптә"),
      icon: <IconWheel size={20} />,
    },
    {
      on: sober,
      set: setSober,
      title: appText("Алкоголя не было", "Эсемлек эсмәнем"),
      sub: appText(
        "И лекарств, которые влияют на реакцию",
        "Реакцияға тәьҫир иткән дарыуҙар ҙа юҡ"
      ),
      icon: <IconShield size={20} />,
    },
  ];

  return (
    <>
      <SubHeader
        title={appText("Готовность к работе", "Эшкә әҙерлек")}
        subtitle={appText("Перед выходом на линию", "Линияға сығыр алдынан")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Скоро здесь", "Тиҙҙән бында")}</h2>
          <p>
            {appText(
              "Отметка готовности включится с ближайшим обновлением.",
              "Әҙерлек билдәһе яҡын яңыртыуҙа тоташа."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && state && (
        <>
          {state.confirmed ? (
            <div className="act-card act-card--mint">
              <div className="act-card__title">
                <IconCheck size={18} /> {appText("Готовность подтверждена", "Әҙерлек раҫланды")}
              </div>
              <p className="act-card__text" style={{ margin: "6px 0 0" }}>
                {appText("Хорошей смены и лёгкой дороги 💚", "Уңышлы смена һәм еңел юл 💚")}
                {state.confirmed_at && (
                  <>
                    <br />
                    {appText("Отмечено: ", "Билдәләнде: ")}
                    {formatWhen(state.confirmed_at, ru)}
                  </>
                )}
              </p>
            </div>
          ) : (
            <>
              <div className="act-card">
                <div className="act-card__title">
                  <IconShield size={18} /> {appText("Перед выходом на линию", "Линияға сығыр алдынан")}
                </div>
                <p className="act-card__text" style={{ margin: "6px 0 0" }}>
                  {appText(
                    "Это самодекларация, а не медосмотр: врача у нас нет. Но осознанное «да» работает — и при разборе видно, что ты заявил.",
                    "Был — үҙ-үҙеңде раҫлау, медосмотр түгел: беҙҙә врач юҡ. Әммә аңлы «эйе» эшләй — тикшереү булһа, нимә әйткәнең күренә."
                  )}
                </p>
                {!state.required && (
                  <p className="act-card__text" style={{ margin: "8px 0 0" }}>
                    {appText(
                      "Сейчас отметка необязательна — но она остаётся в истории как твой след.",
                      "Хәҙер билдә мотлаҡ түгел — әммә ул тарихта эҙең булып ҡала."
                    )}
                  </p>
                )}
              </div>

              <div className="list" style={{ marginTop: 12 }}>
                {items.map((it) => (
                  <label key={it.title} className="list-row list-row--check">
                    <span className="list-row__icon">{it.icon}</span>
                    <div className="list-row__main">
                      <div className="list-row__title">{it.title}</div>
                      <div className="list-row__sub">{it.sub}</div>
                    </div>
                    <input
                      type="checkbox"
                      className="checkbox"
                      checked={it.on}
                      onChange={() => it.set(!it.on)}
                      aria-label={it.title}
                    />
                  </label>
                ))}
              </div>

              <label className="field" style={{ marginTop: 12 }}>
                <span className="field__label">
                  {appText("Заметка (необязательно)", "Билдә (мотлаҡ түгел)")}
                </span>
                <textarea
                  className="field__area"
                  rows={2}
                  maxLength={300}
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder={appText(
                    "«Заменил лампу ближнего света»",
                    "«Яҡын ут лампаһын алмаштырҙым»"
                  )}
                />
                <span className="field__hint">
                  {appText("Коротко, до 300 знаков", "Ҡыҫҡа, 300 билдәгә тиклем")}
                </span>
              </label>

              {err && <p className="taxi-note">{err}</p>}

              <button
                type="button"
                className="btn-primary"
                style={{ width: "100%", marginTop: 12 }}
                onClick={confirm}
                disabled={!allChecked || busy}
              >
                {busy
                  ? appText("Отмечаем…", "Билдәләйбеҙ…")
                  : appText("Подтвердить готовность", "Әҙерлекте раҫлау")}
              </button>
              {!allChecked && (
                <p className="demand__quiet" style={{ textAlign: "center" }}>
                  {appText(
                    "Отметь все три пункта — «частично готов» это не готов.",
                    "Өс пунктты ла билдәлә — «өлөшләтә әҙер» әҙер түгел ул."
                  )}
                </p>
              )}
            </>
          )}

          <p className="receipt__foot">
            {appText(
              "Запись остаётся в журнале за сегодняшний день — это след, а не тумблер.",
              "Яҙма бөгөнгө көн журналында ҡала — был тумблер түгел, эҙ."
            )}
          </p>
        </>
      )}
    </>
  );
}
