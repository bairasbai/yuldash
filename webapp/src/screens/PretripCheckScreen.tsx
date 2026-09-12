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
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconHeart, IconShield, IconWarn, IconWheel } from "../components/Icons";
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

  const done = [health, car, sober].filter(Boolean).length;

  return (
    <>
      <SubHeader title={appText("Готовность к работе", "Эшкә әҙерлек")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        {status === "loading" && <LoadingList count={3} />}
        {status === "soon" && (
          <EmptyStateCard
            icon={<IconShield size={30} />}
            title={appText("Скоро здесь", "Тиҙҙән бында")}
            text={appText(
              "Раздел включится после ближайшего обновления.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар."
            )}
          />
        )}
        {status === "error" && <ErrorState onRetry={() => load()} />}

        {status === "ready" && state && (
          <>
            {state.confirmed ? (
              /* PretripDoneCard: печать вырастает, заголовок 19 Bold, пожелание, время отметки. */
              <section className="pretrip-done">
                <span className="pretrip-done__seal" aria-hidden><IconCheck size={32} /></span>
                <strong>{appText("Готовность подтверждена", "Әҙерлек раҫланды")}</strong>
                <span>{appText("Хорошей смены и лёгкой дороги 💚", "Уңышлы смена һәм еңел юл 💚")}</span>
                {state.confirmed_at && (
                  <small>{appText("Отмечено: ", "Билдәләнде: ")}{formatWhen(state.confirmed_at, ru)}</small>
                )}
              </section>
            ) : (
              <>
                {/* PretripIntroCard: что это и почему честно. */}
                <section className="pretrip-intro">
                  <strong>{appText("Перед выходом на линию", "Линияға сығыр алдынан")}</strong>
                  <p>
                    {appText(
                      "Отметь три пункта — раз в день. Это не медосмотр: врача у нас нет, и мы не будем притворяться. Это твоё слово, и оно остаётся записью — если что-то случится, будет видно, что ты подтвердил в этот день.",
                      "Өс пунктты билдәлә — көнөнә бер тапҡыр. Был медосмотр түгел: табибыбыҙ юҡ, һәм беҙ уны уйнап күрһәтмәйбеҙ. Был — һинең һүҙең, ул яҙма булып ҡала: берәй хәл булһа, ошо көндә нимә раҫлағаның күренәсәк."
                    )}
                  </p>
                  <p className={state.required ? "is-required" : ""}>
                    {state.required
                      ? appText("Сегодня без этой отметки заказы такси брать нельзя.", "Бөгөн был билдәһеҙ такси заказдары алып булмай.")
                      : appText(
                          "Пока не обязательно — но отметка сохранится и пригодится при разборе.",
                          "Әлегә мотлаҡ түгел — әммә билдә һаҡлана һәм тикшереүҙә ярҙам итә."
                        )}
                  </p>
                </section>

                {/* PretripProgress: «ОТМЕЧЕНО · 2 / 3» и полоска 4dp. */}
                <div className="pretrip-progress">
                  <div className="pretrip-progress__row">
                    <span>{appText("ОТМЕЧЕНО", "БИЛДӘЛӘНГӘН")}</span>
                    <b className={done === items.length ? "is-done" : ""}>{done} / {items.length}</b>
                  </div>
                  <div className="pretrip-progress__track" aria-hidden>
                    <div className="pretrip-progress__fill" style={{ width: `${(done / items.length) * 100}%` }} />
                  </div>
                </div>

                {items.map((it) => (
                  <button
                    key={it.title}
                    type="button"
                    role="checkbox"
                    aria-checked={it.on}
                    className={"pretrip-item" + (it.on ? " is-on" : "")}
                    onClick={() => it.set(!it.on)}
                  >
                    <span className="pretrip-item__bubble" aria-hidden>{it.icon}</span>
                    <span className="pretrip-item__text">
                      <strong>{it.title}</strong>
                      <small>{it.sub}</small>
                    </span>
                    <span className="pretrip-item__mark" aria-hidden>{it.on && <IconCheck size={14} />}</span>
                  </button>
                ))}

                <label className="field dl-field">
                  <span className="field__label">{appText("Заметка (необязательно)", "Билдә (мотлаҡ түгел)")}</span>
                  <textarea
                    className="field__input field__area dl-field__area"
                    rows={2}
                    maxLength={300}
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder={appText("«Заменил лампу ближнего света»", "«Яҡын ут лампаһын алмаштырҙым»")}
                  />
                  <span className="field__hint">
                    {note.length === 0
                      ? appText("Коротко, до 300 знаков", "Ҡыҫҡа, 300 билдәгә тиклем")
                      : appText(`${note.length} / 300`, `${note.length} / 300`)}
                  </span>
                </label>

                <div className="pretrip-confirm">
                  <button type="button" className="btn-primary submit-btn" onClick={confirm} disabled={!allChecked || busy}>
                    {busy ? appText("Отмечаем…", "Билдәләйбеҙ…") : appText("Подтвердить готовность", "Әҙерлекте раҫлау")}
                  </button>
                  {!allChecked && (
                    <p className="dl-blocked pretrip-confirm__hint">
                      {appText(
                        "Если хоть один пункт не про тебя сегодня — не выезжай. Заказы подождут, здоровье нет.",
                        "Бөгөн пункттарҙың береһе лә тап килмәһә — сыҡма. Заказдар көтә, ә һаулыҡ көтмәй."
                      )}
                    </p>
                  )}
                  {err && (
                    <div className="docs-notice is-error" role="alert">
                      <IconWarn size={20} />
                      <span>{err}</span>
                    </div>
                  )}
                </div>
              </>
            )}
          </>
        )}
      </div>
    </>
  );
}
