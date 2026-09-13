// ================================================================
//  Фотоконтроль машины (580-ФЗ) — GET/POST /carphoto.
//  Зеркало Android CarPhotoScreen.kt.
//
//  Раз в две недели человек показывает, на чём он возит. До этого
//  машину видели ОДИН раз — на фото при регистрации, а дальше верили
//  галочке «машина исправна».
//
//  Тон экрана: это не проверка на честность, а «покажи машину, дело
//  на две минуты». Срок — словами, последствие названо заранее,
//  кадр, который не подошёл, объясняется тут же одной фразой.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchSecureDoc } from "../api/admin";
import {
  fetchCarPhoto,
  submitCarPhoto,
  uploadCarPhoto,
  type CarPhotoKind,
  type CarPhotoMode,
  type CarPhotoSlot,
  type CarPhotoState,
} from "../api/carphoto";
import { serverDate } from "../utils/serverTime";
import { SubHeader } from "./ConsentsScreen";
import { IconCamera, IconCheck, IconCar, IconClock, IconWarn } from "../components/Icons";

type Status = "loading" | "error" | "off" | "ready";

/**
 * «до 14.03.2027» — дату показываем так, как человек её пишет.
 *
 * Через `serverDate`, а не `new Date`: сервер отдаёт наивный UTC, и обычный разбор увёл бы
 * срок на пять часов — то есть на целый день у тех, кому «сегодня последний».
 */
function dateLabel(iso?: string | null): string {
  const d = serverDate(iso);
  if (!d) return "";
  return d.toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit", year: "numeric" });
}

/** «3 часа» / «11 часов» — иначе текст читается как машинный перевод. */
function hoursRu(n: number): string {
  const abs = Math.abs(n);
  if (abs % 100 >= 11 && abs % 100 <= 14) return `${abs} часов`;
  const last = abs % 10;
  if (last === 1) return `${abs} час`;
  if (last >= 2 && last <= 4) return `${abs} часа`;
  return `${abs} часов`;
}

/** «3 дня» / «14 дней» — иначе текст читается как машинный перевод. */
function daysRu(n: number): string {
  const abs = Math.abs(n);
  if (abs % 100 >= 11 && abs % 100 <= 14) return `${abs} дней`;
  const last = abs % 10;
  if (last === 1) return `${abs} день`;
  if (last >= 2 && last <= 4) return `${abs} дня`;
  return `${abs} дней`;
}

/** Миниатюра присланного кадра: приватная ссылка тянется с токеном → objectURL. */
function ShotThumb({ url, alt }: { url: string; alt: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let objUrl: string | null = null;
    const ac = new AbortController();
    fetchSecureDoc(url, ac.signal)
      .then((u) => {
        objUrl = u;
        setSrc(u);
      })
      .catch(() => undefined); // не открылось — покажем пустую рамку, это не ошибка экрана
    return () => {
      ac.abort();
      if (objUrl) URL.revokeObjectURL(objUrl);
    };
  }, [url]);
  if (!src) return <span className="cph-slot__img skeleton" aria-hidden />;
  return <img className="cph-slot__img" src={src} alt={alt} />;
}

export default function CarPhotoScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const mode: CarPhotoMode = params.get("mode") === "courier" ? "courier" : "taxi";

  const [status, setStatus] = useState<Status>("loading");
  const [state, setState] = useState<CarPhotoState | null>(null);
  const [busySlot, setBusySlot] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState("");
  const input = useRef<HTMLInputElement | null>(null);
  const pickFor = useRef<string | null>(null);
  // Какой контроль снимаем: плановый обход или требование по жалобе.
  const pickKind = useRef<CarPhotoKind>("periodic");

  const load = useCallback(
    (signal?: AbortSignal) => {
      setStatus("loading");
      fetchCarPhoto(mode, signal)
        .then((s) => {
          setState(s);
          setStatus(s.enabled ? "ready" : "off");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus(e instanceof ApiError && (e.status === 403 || e.status === 404) ? "off" : "error");
        });
    },
    [mode]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  /** Причина отказа — словами. Текст живёт здесь, чтобы он менялся вместе с языком. */
  function reasonText(reason: string): string {
    switch (reason) {
      case "too_small":
        return appText(
          "Кадр мелкий — ничего не разглядеть. Сними камерой поближе.",
          "Кадр ваҡ — бер нәмә лә күренмәй. Камера менән яҡыныраҡ төшөр."
        );
      case "too_dark":
        return appText(
          "На кадре темно — ничего не разглядеть. Сними днём или под фонарём.",
          "Кадр ҡараңғы — бер нәмә лә күренмәй. Көндөҙ йәки ут аҫтында төшөр."
        );
      case "too_bright":
        return appText(
          "Кадр пересвечен — деталей не видно. Встань так, чтобы солнце было сбоку.",
          "Кадр артыҡ яҡты — детальдәр күренмәй. Ҡояш ян яҡта булырлыҡ итеп баҫ."
        );
      case "blurry":
        return appText(
          "Кадр размыт. Придержи телефон и сними ещё раз.",
          "Кадр йәйелгән. Телефонды тотоп тор ҙа тағы төшөр."
        );
      case "screenshot":
        return appText(
          "Похоже на скриншот. Нужна фотография самой машины.",
          "Экран һүрәтенә оҡшаған. Машинаның үҙен төшөрөргә кәрәк."
        );
      case "stale":
        return appText(
          "Снимок сделан давно. Покажи машину такой, какая она сейчас.",
          "Һүрәт күптән төшөрөлгән. Машинаны хәҙерге хәлендә күрһәт."
        );
      case "duplicate":
        return appText(
          "Это фото уже присылали. Нужен новый снимок.",
          "Был һүрәт ебәрелгән инде. Яңы кадр кәрәк."
        );
      default:
        return appText("Кадр не подошёл. Сними ещё раз.", "Кадр тура килмәне. Тағы бер тапҡыр төшөр.");
    }
  }

  async function pick(file: File | undefined) {
    const slot = pickFor.current;
    const kind = pickKind.current;
    pickFor.current = null;
    if (input.current) input.current.value = ""; // тот же файл можно выбрать снова
    if (!file || !slot || busySlot) return;
    setBusySlot(slot);
    setErr("");
    try {
      const shot = await uploadCarPhoto(mode, slot, file, kind);
      if (!shot.ok) setErr(reasonText(shot.reason));
      setState(await fetchCarPhoto(mode));
    } catch {
      setErr(appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла."));
    } finally {
      setBusySlot(null);
    }
  }

  async function send(kind: CarPhotoKind) {
    if (sending) return;
    setSending(true);
    setErr("");
    try {
      setState(await submitCarPhoto(mode, kind));
    } catch {
      setErr(appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер."));
    } finally {
      setSending(false);
    }
  }

  /** Список кадров: используется и плановым обходом, и требованием по жалобе. */
  function ShotList({ slots: list, kind }: { slots: CarPhotoSlot[]; kind: CarPhotoKind }) {
    return (
      <>
        {list.map((s) => {
          const done = !!s.url && s.verdict === "ok";
          const bad = !!s.url && !!s.verdict && s.verdict !== "ok";
          const name = appText(s.ru, s.ba);
          const busy = busySlot === s.code;
          return (
            <button
              key={kind + s.code}
              type="button"
              className={"cph-slot" + (done ? " is-done" : "") + (bad ? " is-bad" : "")}
              disabled={busy}
              aria-label={`${name}. ${done ? appText("снято", "төшөрөлгән") : appText("нужно снять", "төшөрөргә кәрәк")}`}
              onClick={() => {
                pickFor.current = s.code;
                pickKind.current = kind;
                input.current?.click();
              }}
            >
              <span className="cph-slot__thumb">
                {s.url ? <ShotThumb url={s.url} alt={name} /> : <span className="cph-slot__empty" aria-hidden><IconCamera size={24} /></span>}
                {busy && <span className="cph-slot__busy" aria-hidden><span className="spinner" /></span>}
              </span>
              <span className="cph-slot__text">
                <strong>{name}</strong>
                <small className={bad ? "is-bad" : ""}>{bad ? reasonText(s.verdict) : appText(s.hint_ru, s.hint_ba)}</small>
              </span>
              {done && <span className="cph-slot__ok" aria-hidden><IconCheck size={24} /></span>}
            </button>
          );
        })}
      </>
    );
  }

  const demand = state?.demand ?? null;
  const slots: CarPhotoSlot[] = state?.slots ?? [];
  const ready = (state?.missing ?? []).length === 0 && slots.length > 0;
  const stage = state?.stage ?? "ok";
  const daysLeft = state?.days_left ?? 0;

  const headTitle =
    stage === "blocked"
      ? appText("Заказы на паузе", "Заказдар паузала")
      : stage === "slow"
        ? appText("Заказы уходят другим", "Заказдар башҡаларға китә")
        : stage === "remind"
          ? appText("Срок прошёл", "Ваҡыт үтте")
          : appText("Покажи машину", "Машинаны күрһәт");

  const headText =
    stage === "blocked"
      ? appText(
          "Пришли фото — и заказы вернутся сразу. Попутка работает как обычно.",
          "Фотоны ебәр — заказдар шунда уҡ ҡайта. Юлдаш ғәҙәттәгесә эшләй."
        )
      : stage === "slow"
        ? appText(
            `Просрочено ${daysRu(state?.late_days ?? 0)}: пока фото нет, заказы первыми видят другие.`,
            `${state?.late_days ?? 0} көн үтте: фото булмағанда, заказдарҙы башҡалар беренсе күрә.`
          )
        : stage === "remind"
          ? appText(
              "Пока это ни на что не влияет. Но через несколько дней заказы начнут уходить другим.",
              "Хәҙергә был бер нәмәгә лә тәьҫир итмәй. Әммә бер нисә көндән заказдар башҡаларға китә башлай."
            )
          : daysLeft <= 0
            ? appText("Сегодня последний день.", "Бөгөн һуңғы көн.")
            : appText(
                `Осталось ${daysRu(daysLeft)}, до ${dateLabel(state?.due_at)}.`,
                `${daysLeft} көн ҡалды, ${dateLabel(state?.due_at)} тиклем.`
              );

  const stageTone = stage === "blocked" ? "danger" : stage === "slow" || stage === "remind" ? "warn" : "mint";
  const nothingToDo = status === "off" || (status === "ready" && state && !state.required && !demand);

  return (
    <>
      <SubHeader title={appText("Фотоконтроль машины", "Машина фотоконтроле")} onBack={() => navigate(-1)} />
      <div className="alist">
        {status === "loading" && <div className="spinner-wrap"><span className="spinner" aria-hidden /></div>}

        {status === "error" && (
          <div className="cph-center">
            <IconWarn size={40} />
            <span className="cph-center__text">{appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер.")}</span>
            <button type="button" className="btn-soft cph-center__btn" onClick={() => load()}>
              {appText("Повторить", "Ҡабатларға")}
            </button>
          </div>
        )}

        {/* Контроль сейчас не нужен: выключен или уже пройден. Спокойное «всё в порядке». */}
        {nothingToDo && (
          <div className="cph-center">
            <span className="cph-center__ok" aria-hidden><IconCheck size={40} /></span>
            <strong>{appText("Сейчас ничего не нужно", "Хәҙер бер нәмә лә кәрәкмәй")}</strong>
            <span className="cph-center__text">
              {state?.last_passed_at
                ? appText(
                    `Машину показывали ${dateLabel(state.last_passed_at)}. Мы напомним заранее, когда придёт время.`,
                    `Машина ${dateLabel(state.last_passed_at)} күрһәтелгән. Ваҡыты еткәс, алдан иҫкә төшөрөрбөҙ.`
                  )
                : appText(
                    "Мы напомним заранее, когда придёт время показать машину.",
                    "Машинаны күрһәтер ваҡыт еткәс, алдан иҫкә төшөрөрбөҙ."
                  )}
            </span>
          </div>
        )}

        {/* Один общий выбор файла на весь экран: и плановый обход, и требование по жалобе
            открывают камеру через него — какой именно кадр снимаем, помнит `pickFor`. */}
        {status === "ready" && (
          <input
            ref={input}
            type="file"
            accept="image/*"
            capture="environment"
            hidden
            onChange={(e) => void pick(e.target.files?.[0])}
          />
        )}

        {/* Требование по жалобе — ВЫШЕ всего: у него сутки, а у планового обхода недели. */}
        {status === "ready" && demand && (
          <>
            <div className={"cph-head " + (demand.status === "review" ? "cph-head--mint" : "cph-head--warn")}>
              <div className="cph-head__row">
                {demand.status === "review" ? <IconClock size={24} /> : <IconCamera size={24} />}
                <strong>{demand.status === "review" ? appText("Фото салона у нас", "Салон фотоһы беҙҙә") : appText("Покажи салон", "Салонды күрһәт")}</strong>
              </div>
              <span>
                {demand.status === "review"
                  ? appText(
                      "Посмотрим и ответим. Если всё в порядке — жалоба закроется без следа.",
                      "Ҡарап сығабыҙ һәм яуап бирәбеҙ. Бөтәһе лә тәртиптә булһа — ялыу эҙһеҙ ябыла."
                    )
                  : appText(
                      "Пассажир написал, что в машине было грязно. Одно фото — и вопрос закрыт, без последствий. Заказы идут как обычно.",
                      "Юлаусы машинала бысраҡ булған тип яҙған. Бер фото — һорау ябыла, эҙемтәһеҙ. Заказдар ғәҙәттәгесә бара."
                    )}
              </span>
              {demand.status !== "review" && (
                <small>
                  {demand.overdue
                    ? appText("Сутки истекли — пришли фото, пока не разобрали без тебя.", "Тәүлек үтте — фотоны ебәр, һинһеҙ ҡарап бөтмәгәндә.")
                    : appText(`Осталось ${hoursRu(demand.hours_left)}.`, `${demand.hours_left} сәғәт ҡалды.`)}
                </small>
              )}
            </div>

            {demand.status !== "review" && (
              <>
                {/* По каким пунктам смотрят салон. Все три видны на фото — в этом весь смысл списка. */}
                {(state?.clean_rules ?? []).length > 0 && (
                  <div className="cph-rules">
                    <strong>{appText("Смотрим три вещи", "Өс нәмәгә ҡарайбыҙ")}</strong>
                    {(state?.clean_rules ?? []).map((r, i) => (
                      <span key={i} className="cph-rules__row">
                        <i>•</i>
                        <span>{appText(r.ru, r.ba)}</span>
                      </span>
                    ))}
                    <small>{appText("Запах по фото не проверить — и мы его не спрашиваем.", "Еҫте фото буйынса тикшереп булмай — беҙ уны һорамайбыҙ ҙа.")}</small>
                  </div>
                )}
                <ShotList slots={demand.slots} kind="complaint" />
                <button type="button" className="btn-primary cph-send" disabled={demand.missing.length > 0 || sending} onClick={() => void send("complaint")}>
                  {sending ? appText("Отправляем…", "Ебәрәбеҙ…") : <><IconCheck size={20} /> {appText("Отправить фото салона", "Салон фотоһын ебәрергә")}</>}
                </button>
              </>
            )}
          </>
        )}

        {status === "ready" && state?.required && (
          <>
            {/* Шапка: до какого числа и что будет, если опоздать. Цвет — по ступени лестницы. */}
            <div className={"cph-head cph-head--" + stageTone}>
              <div className="cph-head__row">
                <IconCar size={24} />
                <strong>{headTitle}</strong>
              </div>
              <span>{headText}</span>
            </div>

            {state.status === "review" ? (
              /* Кадры отправлены. Главное здесь — сказать, что человек уже свободен. */
              <div className="cph-head cph-head--mint">
                <div className="cph-head__row">
                  <IconClock size={24} />
                  <strong>{appText("Кадры у нас", "Кадрҙар беҙҙә")}</strong>
                </div>
                <span>{appText("Посмотрим и ответим. Работать можно как обычно — ждать нас не нужно.", "Ҡарап сығабыҙ һәм яуап бирәбеҙ. Эшләргә була — беҙҙе көтөп тороу кәрәкмәй.")}</span>
              </div>
            ) : (
              <>
                {state.reject_reason && (
                  /* Почему не приняли прошлый раз — словами человека, который смотрел. */
                  <div className="cph-reject">
                    <strong>{appText("Прошлые кадры не подошли", "Үткән кадрҙар тура килмәне")}</strong>
                    <span>{state.reject_reason}</span>
                  </div>
                )}

                {/* Что именно смотрим: зима и первый контроль меняют требования. */}
                <div className="cph-rules">
                  {[
                    appText("Снимай при свете и в фокусе: машину целиком, номер должен читаться.", "Яҡтыла һәм фокуста төшөр: машина тулыһынса инһен, номер уҡылырлыҡ булһын."),
                    state.winter
                      ? appText("Зимой чистый кузов не требуем — только целостность: фары, бампер, ржавчина.", "Ҡышын таҙа кузов талап итмәйбеҙ — бөтөнлөк кенә: фаралар, бампер, тут.")
                      : appText("Кузов чистый настолько, чтобы было видно состояние.", "Кузов хәлен күрерлек итеп таҙа булһын."),
                    appText("Салон — как для пассажира: без мусора и личных вещей.", "Салон — юлаусы өсөн кеүек: сүпһеҙ һәм шәхси әйберһеҙ."),
                    ...(state.check_signs
                      ? [appText("В первый раз посмотрим ещё фонарь и «шашечки» — отдельный кадр не нужен.", "Беренсе тапҡыр фонарь һәм «шашка»ларҙы ла ҡарайбыҙ — айырым кадр кәрәкмәй.")]
                      : []),
                    appText("Документы фотографировать не нужно — они у нас есть.", "Документтарҙы төшөрөргә кәрәкмәй — улар беҙҙә бар."),
                  ].map((line, i) => (
                    <span key={i} className="cph-rules__row">
                      <i>•</i>
                      <span>{line}</span>
                    </span>
                  ))}
                </div>

                <small className="cph-label">{appText("КАДРЫ", "КАДРҘАР")}</small>
                <ShotList slots={slots} kind="periodic" />

                {err && <div className="cph-error">{err}</div>}

                <button type="button" className="btn-primary cph-send" disabled={!ready || sending} onClick={() => void send("periodic")}>
                  {sending ? appText("Отправляем…", "Ебәрәбеҙ…") : <><IconCheck size={20} /> {appText("Отправить на проверку", "Тикшереүгә ебәрергә")}</>}
                </button>

                <small className="acard__date">
                  {appText(
                    `Снимки видим только мы и ты — они лежат в закрытой части и удаляются сами через ${state.keep_days ?? 90} дней.`,
                    `Һүрәттәрҙе беҙ һәм һин генә күрәбеҙ — улар ябыҡ өлөштә ята һәм ${state.keep_days ?? 90} көндән үҙҙәре юйыла.`
                  )}
                </small>
              </>
            )}
          </>
        )}
      </div>
    </>
  );
}
