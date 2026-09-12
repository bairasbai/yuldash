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
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCamera, IconCheck, IconChevron, IconClock, IconIdCard, IconInfo, IconShield, IconWarn, IconWheel } from "../components/Icons";

type Status = "loading" | "error" | "none" | "ready";
type DocKey = "osago_until" | "permit_until" | "inspection_until" | "osgop_until";

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
  const [noteOk, setNoteOk] = useState(true);
  const [permitHelp, setPermitHelp] = useState(false);

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
      setNoteOk(true);
      setNote(appText("Дата сохранена", "Дата һаҡланды"));
    } catch (e) {
      setNoteOk(false);
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
    {
      key: "osgop_until",
      title: appText("ОСГОП", "ОСГОП"),
      hint: appText("Страховка пассажиров — обязательна для такси с 2024 года", "Юлаусылар страховкаһы — 2024 йылдан такси өсөн мотлаҡ"),
      icon: <IconShield size={20} />,
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

  const heroTone = expired ? "danger" : soon || attention ? "warn" : "ok";

  return (
    <>
      <SubHeader title={appText("Документы и сроки", "Документтар һәм ваҡыттар")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        {status === "loading" && <LoadingList count={3} />}
        {status === "error" && <ErrorState onRetry={() => load()} />}
        {status === "none" && (
          <EmptyStateCard
            icon={<IconIdCard size={30} />}
            title={appText("Заявка не подана", "Заявка бирелмәгән")}
            text={appText(
              "Сроки документов появятся, когда подашь заявку «Стать таксистом Юлдаша».",
              "Документ ваҡыттары «Юлдаш таксисы булыу» заявкаһын биргәс күренәсәк."
            )}
            action={appText("Подать заявку", "Заявка биреү")}
            onAction={() => navigate("/taxi-onboarding")}
          />
        )}

        {status === "ready" && app && (
          <>
            {/* TaxiDocsHeader: AppCard, круг с иконкой (мятный / жёлтый / красный), заголовок 19 Bold, подпись. */}
            <section className="docs-hero">
              <span className={"docs-hero__icon docs-hero__icon--" + heroTone} aria-hidden>
                {heroTone === "ok" ? <IconCheck size={24} /> : heroTone === "warn" ? <IconClock size={24} /> : <IconWarn size={24} />}
              </span>
              <span className="docs-hero__text">
                <strong>{headTitle()}</strong>
                <small>{headText()}</small>
              </span>
            </section>
            {/* InlineNotice: «Дата сохранена» мятной строкой, ошибка — красной. */}
            {note && (
              <div className={"docs-notice" + (noteOk ? "" : " is-error")} role="status">
                {noteOk ? <IconCheck size={20} /> : <IconWarn size={20} />}
                <span>{note}</span>
              </div>
            )}

            {/* Ответ государственного реестра — ВЫШЕ сроков: без разрешения на линию не выйти
                вообще, и продлевать ОСАГО в этот момент бессмысленно. Не спрашивали или реестр
                промолчал — не показываем ничего: человек не виноват в нашем таймауте. */}
            {app.permit_registry_checked &&
              (app.permit_registry_ok ? (
                <div className="docs-registry docs-registry--ok">
                  <IconCheck size={20} />
                  <span>
                    {app.permit_registry_until
                      ? appText(
                          `Разрешение подтверждено реестром, действует до ${dateLabel(app.permit_registry_until)}`,
                          `Рөхсәт реестр менән раҫланған, ${dateLabel(app.permit_registry_until)} тиклем ғәмәлдә`
                        )
                      : appText("Разрешение подтверждено государственным реестром", "Рөхсәт дәүләт реестры менән раҫланған")}
                  </span>
                </div>
              ) : (
                <div className="docs-registry docs-registry--bad">
                  <div className="docs-registry__head">
                    <IconWarn size={20} />
                    <strong>{appText("Разрешения нет в реестре такси", "Такси реестрында рөхсәт юҡ")}</strong>
                  </div>
                  <p>
                    {appText(
                      "Без него мы не имеем права давать тебе заказы такси — это закон, и отвечаем по нему мы вместе с тобой. Попутка работает как обычно: ей разрешение не нужно.",
                      "Уныһыҙ һиңә такси заказдары бирергә хаҡыбыҙ юҡ — был закон, һәм уның буйынса беҙ һинең менән бергә яуап бирәбеҙ. Юлдаш ғәҙәттәгесә эшләй: уға рөхсәт кәрәкмәй."
                    )}
                  </p>
                  <button type="button" className="btn-ghost docs-registry__more" onClick={() => setPermitHelp((v) => !v)}>
                    {permitHelp ? appText("Свернуть", "Йыйыу") : appText("Как получить разрешение", "Рөхсәтте нисек алырға")}
                  </button>
                  {permitHelp && (
                    <>
                      <ol className="permit-steps">
                        <li>{appText("Стань самозанятым — приложение «Мой налог», вид деятельности «перевозка пассажиров».", "Үҙмәшғүл бул — «Мой налог» ҡушымтаһы, эшмәкәрлек төрө — «юлаусыларҙы ташыу».")}</li>
                        <li>{appText("Подай заявление на Госуслугах. Бесплатно. Нужны паспорт, права, СТС и ОСАГО.", "Госуслуги аша ғариза бир. Бушлай. Паспорт, права, СТС һәм ОСАГО кәрәк.")}</li>
                        <li>{appText("Подожди 5–20 рабочих дней. Разрешение выдают на 5 лет.", "5–20 эш көнө көт. Рөхсәтте 5 йылға бирәләр.")}</li>
                        <li>{appText("Возвращайся — проверим сами, вписывать ничего не нужно.", "Кире кил — үҙебеҙ тикшерәбеҙ, бер нәмә лә яҙырға кәрәкмәй.")}</li>
                      </ol>
                      <a className="btn-ghost" href="https://www.gosuslugi.ru/" target="_blank" rel="noreferrer">
                        {appText("Открыть Госуслуги", "Госуслуги-ны асыу")}
                      </a>
                    </>
                  )}
                </div>
              ))}

            {/* Фотоконтроль машины (TaxiCarPhotoBlock): цвет по стадии, стрелка вправо. */}
            {carPhoto?.enabled && (
              <button
                type="button"
                className={
                  "docs-photo " +
                  (carPhoto.required && carPhoto.stage === "blocked"
                    ? "docs-photo--danger"
                    : carPhoto.required && (carPhoto.stage === "slow" || carPhoto.stage === "remind")
                      ? "docs-photo--warn"
                      : "docs-photo--ok")
                }
                onClick={() => navigate("/car-photo")}
              >
                <IconCamera size={20} />
                <span className="docs-photo__text">
                  <strong>
                    {!carPhoto.required
                      ? appText("Фотоконтроль пройден", "Фотоконтроль үтелгән")
                      : carPhoto.status === "review"
                        ? appText("Кадры у нас — смотрим", "Кадрҙар беҙҙә — ҡарайбыҙ")
                        : carPhoto.stage === "blocked"
                          ? appText("Заказы на паузе: нужно фото машины", "Заказдар паузала: машина фотоһы кәрәк")
                          : carPhoto.stage === "slow"
                            ? appText("Фото машины просрочено — заказы уходят другим", "Машина фотоһы һуңлаған — заказдар башҡаларға китә")
                            : carPhoto.stage === "remind"
                              ? appText("Фото машины просрочено", "Машина фотоһы ваҡытында түгел")
                              : appText("Покажи машину", "Машинаны күрһәт")}
                  </strong>
                  <small>
                    {!carPhoto.required || carPhoto.status === "review"
                      ? appText("Работать можно как обычно.", "Ғәҙәттәгесә эшләргә була.")
                      : appText("Несколько кадров с телефона — это пара минут.", "Телефондан бер нисә кадр — ике минутлыҡ эш.")}
                  </small>
                </span>
                <IconChevron size={20} />
              </button>
            )}

            <span className="docs-label">{appText("ДОКУМЕНТЫ", "ДОКУМЕНТТАР")}</span>

            {docs.map((d) => {
              const value = app[d.key] ?? null;
              const dl = daysLeft(value);
              const missing = !value;
              const isOver = dl !== null && dl < 0;
              const isSoon = dl !== null && dl >= 0 && dl <= 14;
              const tone = isOver ? "danger" : isSoon ? "warn" : missing ? "muted" : "ok";
              const open = editing === d.key;
              return (
                <div key={d.key} className={"doc-row doc-row--" + tone + (busy ? " is-busy" : "")}>
                  <div className="doc-row__head">
                    <span className="doc-row__icon" aria-hidden>{d.icon}</span>
                    <span className="doc-row__text">
                      <strong>{d.title}</strong>
                      <small>{d.hint}</small>
                    </span>
                    {(isOver || isSoon) && (
                      <span className="doc-row__pill">{isOver ? appText("Истёк", "Үткән") : appText(`${dl} дн.`, `${dl} көн`)}</span>
                    )}
                  </div>
                  <button
                    type="button"
                    className="doc-row__date"
                    onClick={() => {
                      setEditing(open ? null : d.key);
                      setDraft(value ?? "");
                      setNote("");
                    }}
                    disabled={busy}
                  >
                    <b>{missing ? appText("Дата не указана", "Дата күрһәтелмәгән") : appText(`до ${dateLabel(value)}`, `${dateLabel(value)} тиклем`)}</b>
                    <span>
                      {missing ? appText("Указать", "Күрһәтеү") : appText("Изменить", "Үҙгәртеү")} <IconChevron size={16} />
                    </span>
                  </button>
                  {open && (
                    <div className="doc-row__edit">
                      <label className="field dl-field">
                        <span className="field__label">{appText("Действует до", "Ошо ваҡытҡа тиклем")}</span>
                        <input className="field__input" type="date" value={draft} onChange={(e) => setDraft(e.target.value)} />
                      </label>
                      <div className="doc-row__actions">
                        <button type="button" className="btn-primary" onClick={() => save(d.key)} disabled={busy || !draft}>
                          {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить", "Һаҡлау")}
                        </button>
                        <button type="button" className="btn-ghost" onClick={() => { setEditing(null); setDraft(""); }}>
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}

            <p className="docs-foot">
              <IconInfo size={16} />
              <span>
                {appText(
                  "Мы напомним за две недели и ещё раз за три дня. Если срок всё же выйдет — такси встанет на паузу, а попутки продолжат работать. Обновишь дату — допуск вернётся сразу.",
                  "Ике аҙна алдан һәм тағы өс көн ҡалғас иҫкә төшөрәбеҙ. Ваҡыт үтһә — такси паузаға китә, ә юлдаш сәфәрҙәре эшләй бирә. Датаны яңыртҡас — рөхсәт шунда уҡ ҡайта."
                )}
              </span>
            </p>
          </>
        )}
      </div>
    </>
  );
}
