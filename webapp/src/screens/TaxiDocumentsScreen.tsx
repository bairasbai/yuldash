// ================================================================
//  «Документы и сроки» таксиста — GET /taxi/application + POST /taxi/documents.
//  Зеркало Android TaxiDocsScreens.kt (часть «Документы и сроки»).
//
//  Смысл экрана: продлил ОСАГО — скажи системе, НЕ пере-подавая заявку.
//  Раньше выхода не было: повторная подача сбрасывала статус в «на
//  проверке» и оставляла человека без работы до модерации — наказание
//  за законопослушность. Допуск возвращается сразу, как все сроки
//  снова в будущем.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchTaxiApplication,
  updateTaxiDocuments,
  type TaxiApplication,
} from "../api/instant";
import { fetchCarPhoto, type CarPhotoState } from "../api/carphoto";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCamera, IconCheck, IconIdCard, IconShield, IconWarn, IconWheel } from "../components/Icons";

type Status = "loading" | "error" | "none" | "ready";
type DocKey = "osago_until" | "permit_until" | "inspection_until";

/** Сколько дней осталось до даты (отрицательное = просрочено). */
function daysLeft(iso: string | null): number | null {
  if (!iso) return null;
  const d = new Date(iso + "T00:00:00");
  if (isNaN(d.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((d.getTime() - today.getTime()) / 86400000);
}

/** «до 14.03.2027» — дату показываем как человек её пишет. */
function dateLabel(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso + "T00:00:00");
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit", year: "numeric" });
}

export default function TaxiDocumentsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [app, setApp] = useState<TaxiApplication | null>(null);
  // Фотоконтроль машины (580-ФЗ): свой флаг и свой срок, поэтому отдельный запрос.
  // Ошибку его НЕ показываем — это дополнение к экрану, а не его суть.
  const [carPhoto, setCarPhoto] = useState<CarPhotoState | null>(null);
  const [editing, setEditing] = useState<DocKey | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchCarPhoto("taxi", signal)
      .then((c) => setCarPhoto(c))
      .catch(() => undefined);
    fetchTaxiApplication(signal)
      .then((a) => {
        setApp(a);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = заявку не подавал: это не ошибка, а честное состояние.
        setStatus(e instanceof ApiError && e.status === 404 ? "none" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function save(key: DocKey) {
    if (!draft) return;
    setBusy(true);
    setNote("");
    try {
      const updated = await updateTaxiDocuments({ [key]: draft });
      setApp(updated);
      setEditing(null);
      setDraft("");
      setNote(appText("Дата сохранена", "Дата һаҡланды"));
    } catch (e) {
      setNote(
        e instanceof ApiError && e.status === 400
          ? e.message
          : appText("Не получилось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  const docs: { key: DocKey; title: string; hint: string; icon: JSX.Element }[] = [
    {
      key: "osago_until",
      title: appText("ОСАГО", "ОСАГО"),
      hint: appText(
        "Страховка — без неё при ДТП платить некому",
        "Страховка — ДТП булһа түләүсе булмай"
      ),
      icon: <IconShield size={20} />,
    },
    {
      key: "permit_until",
      title: appText("Разрешение на такси", "Такси рөхсәте"),
      hint: appText("Номер в реестре перевозчиков", "Йөрөтөүселәр реестрындағы номер"),
      icon: <IconIdCard size={20} />,
    },
    {
      key: "inspection_until",
      title: appText("Диагностическая карта", "Диагностика картаһы"),
      hint: appText("Техосмотр машины", "Машинаның техник ҡарауы"),
      icon: <IconWheel size={20} />,
    },
  ];

  const left = app?.docs_days_left ?? null;
  const expired = Boolean(app?.docs_expired) || (left !== null && left < 0);
  const soon = !expired && left !== null && left <= 14;
  const attention = !expired && !soon && (app?.docs_missing?.length ?? 0) > 0;

  function headTitle(): string {
    if (expired) return appText("Такси на паузе", "Такси паузала");
    if (soon) return appText("Скоро истекает документ", "Документ ваҡыты бөтә");
    if (attention) return appText("Укажи сроки документов", "Документ ваҡыттарын күрһәт");
    return appText("Документы в порядке", "Документтар тәртиптә");
  }

  function headText(): string {
    if (expired)
      return appText(
        "Обнови дату — вернём допуск сразу. Попутки работают.",
        "Датаны яңырт — рөхсәтте шунда уҡ ҡайтарабыҙ. Юлдаш сәфәрҙәре эшләй."
      );
    if (soon && left !== null)
      return appText(
        `Осталось ${left} дн. — лучше продлить заранее`,
        `${left} көн ҡалды — алдан оҙайтҡан яҡшыраҡ`
      );
    if (attention)
      return appText(
        "Так мы предупредим заранее, а не по факту",
        "Шунда алдан иҫкәртәбеҙ, эш үткәс түгел"
      );
    return appText(
      "Спасибо, что держишь их актуальными",
      "Уларҙы яңы килеш тотҡаның өсөн рәхмәт"
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Документы и сроки", "Документтар һәм ваҡыттар")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconIdCard size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "none" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconIdCard size={34} />
          </div>
          <h2>{appText("Заявка не подана", "Заявка бирелмәгән")}</h2>
          <p>
            {appText(
              "Сроки документов появятся, когда подашь заявку «Стать таксистом Юлдаша».",
              "Документ ваҡыттары «Юлдаш таксисы булыу» заявкаһын биргәс күренәсәк."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/taxi-onboarding")}>
            {appText("Подать заявку", "Заявка биреү")}
          </button>
        </div>
      )}

      {status === "ready" && app && (
        <>
          <div
            className={
              "act-card " + (expired ? "act-card--warn" : soon || attention ? "act-card--warn" : "act-card--mint")
            }
          >
            <div className="act-card__title">
              {expired || soon || attention ? <IconWarn size={18} /> : <IconCheck size={18} />}
              {headTitle()}
            </div>
            <p className="act-card__text" style={{ margin: "6px 0 0" }}>
              {headText()}
            </p>
          </div>

          {/* Ответ государственного реестра — ВЫШЕ сроков: без разрешения на линию не выйти
              вообще, и продлевать ОСАГО в этот момент бессмысленно. Не спрашивали или реестр
              промолчал — не показываем ничего: человек не виноват в нашем таймауте. */}
          {app.permit_registry_checked && (
            app.permit_registry_ok ? (
              <div className="act-card act-card--mint">
                <div className="act-card__title">
                  <IconCheck size={18} />
                  {app.permit_registry_until
                    ? appText(
                        `Разрешение подтверждено реестром, действует до ${dateLabel(app.permit_registry_until)}`,
                        `Рөхсәт реестр менән раҫланған, ${dateLabel(app.permit_registry_until)} тиклем ғәмәлдә`
                      )
                    : appText("Разрешение подтверждено государственным реестром",
                              "Рөхсәт дәүләт реестры менән раҫланған")}
                </div>
              </div>
            ) : (
              <div className="act-card act-card--warn">
                <div className="act-card__title">
                  <IconWarn size={18} />
                  {appText("Разрешения нет в реестре такси", "Такси реестрында рөхсәт юҡ")}
                </div>
                <p className="act-card__text" style={{ margin: "6px 0 0" }}>
                  {appText(
                    "Без него мы не имеем права давать тебе заказы такси — это закон, и отвечаем по нему мы вместе с тобой. Попутка работает как обычно: ей разрешение не нужно.",
                    "Уныһыҙ һиңә такси заказдары бирергә хаҡыбыҙ юҡ — был закон, һәм уның буйынса беҙ һинең менән бергә яуап бирәбеҙ. Юлдаш ғәҙәттәгесә эшләй: уға рөхсәт кәрәкмәй."
                  )}
                </p>
                <ol className="permit-steps">
                  <li>{appText("Стань самозанятым — приложение «Мой налог», вид деятельности «перевозка пассажиров».",
                               "Үҙең эшләүсе бул — «Мой налог» ҡушымтаһы, эшмәкәрлек төрө «юлаусылар ташыу».")}</li>
                  <li>{appText("Подай заявление на Госуслугах. Бесплатно. Нужны паспорт, права, СТС и ОСАГО.",
                               "Госуслуги-ла ғариза бир. Бушлай. Паспорт, права, СТС һәм ОСАГО кәрәк.")}</li>
                  <li>{appText("Подожди 5–20 рабочих дней. Разрешение выдают на 5 лет.",
                               "5–20 эш көнө көт. Рөхсәт 5 йылға бирелә.")}</li>
                  <li>{appText("Возвращайся — проверим сами, вписывать ничего не нужно.",
                               "Кире ҡайт — үҙебеҙ тикшерәбеҙ, бер нәмә лә яҙырға кәрәкмәй.")}</li>
                </ol>
                {/* Предупреждение ДО покупки машины, а не после: человек в райцентре берёт
                    машину один раз на годы, и «выяснилось потом» здесь — потерянные деньги.
                    Формулировки осторожные: требования региональные и меняются. */}
                <p className="act-card__text" style={{ margin: "0 0 6px", fontWeight: "var(--weight-semibold)" }}>
                  {appText("Если только собираешься покупать машину",
                           "Әгәр машина һатып алырға ғына йыйынаһың")}
                </p>
                <ul className="permit-steps">
                  <li>{appText(
                    "С 1 марта 2026 новую машину вносят в реестр такси, только если она собрана в России или ЕАЭС. На другую разрешение могут не дать.",
                    "2026 йылдың 1 мартынан яңы машинаны такси реестрына Рәсәйҙә йәки ЕАЭС-та йыйылған булһа ғына индерәләр. Башҡаһына рөхсәт бирмәҫкә мөмкиндәр.")}</li>
                  <li>{appText(
                    "Для нового разрешения могут потребовать ГЛОНАСС-терминал — уточни в своём районе заранее.",
                    "Яңы рөхсәт өсөн ГЛОНАСС-терминал талап итеүҙәре мөмкин — үҙ районыңда алдан асыҡла.")}</li>
                </ul>
                <a className="btn-ghost" href="https://www.gosuslugi.ru/" target="_blank" rel="noreferrer">
                  {appText("Открыть Госуслуги", "Госуслуги-ны асыу")}
                </a>
              </div>
            )
          )}

          {/* Фотоконтроль машины — рядом с реестром, до сроков: если машину давно не
              показывали, линия встанет так же, как без разрешения. */}
          {carPhoto?.enabled && (
            <button
              type="button"
              className={
                "act-card car-photo-link" +
                (carPhoto.required && carPhoto.stage !== "ok" ? " act-card--warn" : " act-card--mint")
              }
              onClick={() => navigate("/car-photo")}
            >
              <div className="act-card__title">
                <IconCamera size={18} />
                {!carPhoto.required
                  ? appText("Фотоконтроль пройден", "Фотоконтроль үтелгән")
                  : carPhoto.status === "review"
                    ? appText("Кадры у нас — смотрим", "Кадрҙар беҙҙә — ҡарайбыҙ")
                    : carPhoto.stage === "blocked"
                      ? appText("Заказы на паузе: нужно фото машины", "Заказдар паузала: машина фотоһы кәрәк")
                      : carPhoto.stage === "slow"
                        ? appText("Фото машины просрочено — заказы уходят другим",
                                  "Машина фотоһы һуңлаған — заказдар башҡаларға китә")
                        : carPhoto.stage === "remind"
                          ? appText("Фото машины просрочено", "Машина фотоһы ваҡытында түгел")
                          : appText("Покажи машину", "Машинаны күрһәт")}
              </div>
              <p className="act-card__text" style={{ margin: "6px 0 0" }}>
                {!carPhoto.required || carPhoto.status === "review"
                  ? appText("Работать можно как обычно.", "Ғәҙәттәгесә эшләргә була.")
                  : appText("Несколько кадров с телефона — это пара минут.",
                            "Телефондан бер нисә кадр — ике минутлыҡ эш.")}
              </p>
            </button>
          )}

          <h2 className="section-title">{appText("Документы", "Документтар")}</h2>

          {docs.map((d) => {
            const value = app[d.key];
            const dl = daysLeft(value);
            const isOver = dl !== null && dl < 0;
            const isSoon = dl !== null && dl >= 0 && dl <= 14;
            return (
              <div key={d.key}>
                <div
                  className={
                    "doc-term" + (isOver ? " doc-term--over" : isSoon ? " doc-term--soon" : "")
                  }
                >
                  <span className="doc-term__ic">{d.icon}</span>
                  <span className="doc-term__main">
                    <span className="doc-term__title">{d.title}</span>
                    <span className="doc-term__sub">
                      {value
                        ? appText(`до ${dateLabel(value)}`, `${dateLabel(value)} тиклем`)
                        : appText("Дата не указана", "Дата күрһәтелмәгән")}
                      {dl !== null && (
                        <>
                          {" · "}
                          {isOver
                            ? appText("Истёк", "Үткән")
                            : appText(`${dl} дн.`, `${dl} көн`)}
                        </>
                      )}
                    </span>
                    <span className="doc-term__sub">{d.hint}</span>
                  </span>
                  <button
                    type="button"
                    className="payout__change"
                    onClick={() => {
                      setEditing(editing === d.key ? null : d.key);
                      setDraft(value ?? "");
                      setNote("");
                    }}
                  >
                    {value ? appText("Изменить", "Үҙгәртеү") : appText("Указать", "Күрһәтеү")}
                  </button>
                </div>

                {editing === d.key && (
                  <div className="act-card">
                    <label className="field">
                      <span className="field__label">
                        {appText("Действует до", "Ошо ваҡытҡа тиклем")}
                      </span>
                      <input
                        className="field__input"
                        type="date"
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                      />
                    </label>
                    <div className="act-card__actions" style={{ marginTop: 12 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => save(d.key)}
                        disabled={busy || !draft}
                      >
                        {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить", "Һаҡлау")}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => {
                          setEditing(null);
                          setDraft("");
                        }}
                      >
                        {appText("Отмена", "Баш тартыу")}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}

          {note && <p className="taxi-note">{note}</p>}

          <p className="receipt__foot">
            {appText(
              "Обновление сроков не сбрасывает заявку: статус проверки остаётся, допуск вернётся сразу.",
              "Ваҡыттарҙы яңыртыу заявканы кире ҡайтармай: тикшереү хәле ҡала, рөхсәт шунда уҡ ҡайта."
            )}
          </p>
        </>
      )}
    </>
  );
}
