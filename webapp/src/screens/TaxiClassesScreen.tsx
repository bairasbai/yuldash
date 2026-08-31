// ================================================================
//  «Что я вожу» — классы машины и опции салона у таксиста.
//  Зеркало Android (TaxiOnboardingScreen.kt, TaxiMyClassesCard)
//  + backend taxi.py: GET/POST /taxi/classes.
//
//  Класс машина ЗАСЛУЖИВАЕТ, а не заявляет: характеристики ставит
//  модератор при допуске, водитель только включает то, что ему уже
//  доступно. Иначе классификатор обходится одной галочкой: пассажир
//  платит за Комфорт, приезжает Гранта.
//
//  Опции салона — не класс. Одна машина не может стоять в двух
//  классах, а детское кресло возит любая: поэтому кресла, коляска
//  и животные живут галочками поверх любого класса. Включить их
//  можно в среду, а не в день модерации, — пере-подача заявки для
//  этого не нужна.
//
//  В вебе экрана не было совсем: таксист с сайта не мог ни узнать,
//  чего не хватает до Комфорта, ни включить Минивэн
//  (сверка с Android, 2026-08-30).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverClasses,
  saveDriverClasses,
  type CarClassRow,
  type DriverClasses,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCar, IconCheck } from "../components/Icons";

type Boot = "loading" | "error" | "ready";

/** Названия классов. Те же слова, что в витрине тарифов у пассажира. */
const CLASS_TITLES: Record<string, { ru: string; ba: string; subRu: string; subBa: string }> = {
  economy: { ru: "Эконом", ba: "Эконом", subRu: "обычная машина", subBa: "ғәҙәти машина" },
  comfort: {
    ru: "Комфорт",
    ba: "Комфорт",
    subRu: "новее и с кондиционером",
    subBa: "яңыраҡ, кондиционерлы",
  },
  business: {
    ru: "Бизнес",
    ba: "Бизнес",
    subRu: "седан премиум-класса",
    subBa: "премиум класслы седан",
  },
  minivan: { ru: "Минивэн", ba: "Минивэн", subRu: "6–8 мест", subBa: "6–8 урын" },
};

/**
 * Чего не хватает — человеческими словами.
 *
 * Сервер шлёт коды, а не готовые фразы: как это звучит по-башкирски, решает клиент.
 * «Недоступно» без причины — худший из возможных ответов: человек не понимает,
 * чинить ему машину или писать нам.
 */
const MISSING_TEXT: Record<string, { ru: string; ba: string }> = {
  too_old: { ru: "машина старше нужного", ba: "машина кәрәгенән иҫкерәк" },
  year_unknown: { ru: "не указан год выпуска", ba: "сығарылған йыл күрһәтелмәгән" },
  no_ac: { ru: "нужен рабочий кондиционер", ba: "эшләүсе кондиционер кәрәк" },
  clean_salon: { ru: "салон целый, без чехлов-накидок", ba: "салон бөтөн, чехолһыҙ" },
  body: { ru: "кузов без вмятин и ржавчины", ba: "кузов бөгөлмәгән, тутыҡмаған" },
  few_seats: { ru: "не хватает мест", ba: "урын етмәй" },
  not_sedan: { ru: "нужен седан", ba: "седан кәрәк" },
  color_business: { ru: "нужен чёрный или белый кузов", ba: "ҡара йәки аҡ кузов кәрәк" },
  no_leather: { ru: "нужен кожаный или светлый салон", ba: "күн йәки яҡты салон кәрәк" },
  not_verified_premium: {
    ru: "нужен осмотр машины — напиши нам",
    ba: "машинаны ҡарау кәрәк — беҙгә яҙ",
  },
  too_many_seats: {
    ru: "больше 8 мест — нужна лицензия на автобус",
    ba: "8-ҙән күп урын — автобус лицензияһы кәрәк",
  },
};

/** Опции салона. Коды совпадают с backend/app/car_class.py. */
const OPTION_TEXT: Record<string, { ru: string; ba: string; emoji: string }> = {
  seat_0_1: { ru: "Люлька 0–1", ba: "Бәпес арбаһы 0–1", emoji: "👶" },
  seat_1_4: { ru: "Кресло 1–4", ba: "Ултырғыс 1–4", emoji: "🧒" },
  seat_4_7: { ru: "Кресло 4–7", ba: "Ултырғыс 4–7", emoji: "🧒" },
  booster: { ru: "Бустер 7–12", ba: "Бустер 7–12", emoji: "💺" },
  stroller: { ru: "Коляска", ba: "Балалар арбаһы", emoji: "🍼" },
  wheelchair: { ru: "Инвалидная коляска", ba: "Инвалид коляскаһы", emoji: "♿" },
  guide_dog: { ru: "Собака-проводник", ba: "Юл күрһәтеүсе эт", emoji: "🦮" },
  pets: { ru: "С животным", ba: "Хайуан менән", emoji: "🐾" },
  big_luggage: { ru: "Большой багаж", ba: "Ҙур багаж", emoji: "🧳" },
  charger: { ru: "Зарядка в машине", ba: "Машинала зарядка", emoji: "🔌" },
};

/** Одна строка класса: включён / доступен, чего не хватает, сколько набралось в районе. */
function ClassRow({
  row,
  busy,
  onToggle,
}: {
  row: CarClassRow;
  busy: boolean;
  onToggle: () => void;
}) {
  const { appText } = useLang();
  const t = CLASS_TITLES[row.car_class] ?? {
    ru: row.car_class,
    ba: row.car_class,
    subRu: "",
    subBa: "",
  };
  const on = row.enabled && row.available;

  /** Что написать под названием. Порядок важнее полноты: сначала то, что человек может сделать. */
  function subtitle() {
    if (!row.available) {
      const codes = row.missing.slice(0, 2);
      if (codes.length === 0) return appText("пока недоступен", "әлегә юҡ");
      return codes
        .map((c) => {
          const m = MISSING_TEXT[c];
          return m ? appText(m.ru, m.ba) : c;
        })
        .join(" · ");
    }
    // Первому в районе — статус, а не ноль: «набралось 0 из 3» демотивирует.
    if (row.first) return appText("Ты будешь первым здесь", "Һин бында беренсе булаһың");
    if (!row.open) {
      return appText(
        `Нас ${row.drivers_have} из ${row.drivers_need} — позови знакомого, и класс откроется`,
        `Беҙ ${row.drivers_have}/${row.drivers_need} — танышыңды саҡыр, класс асыла`
      );
    }
    if (on) return appText("Заказы приходят", "Заказдар килә");
    return appText("Выключен — заказы не приходят", "Һүндерелгән — заказдар килмәй");
  }

  return (
    <button
      type="button"
      className={"class-row" + (on ? " class-row--on" : "") + (row.available ? "" : " class-row--off")}
      onClick={onToggle}
      disabled={busy || !row.available}
      aria-pressed={on}
    >
      <span className="class-row__text">
        <span className="class-row__title">{appText(t.ru, t.ba)}</span>
        <span className="class-row__sub">{subtitle()}</span>
        {row.available && row.open && !on && (
          <span className="class-row__hint">{appText(t.subRu, t.subBa)}</span>
        )}
      </span>
      {row.available && (
        <span className="class-row__mark" aria-hidden>
          {on ? <IconCheck size={20} /> : "○"}
        </span>
      )}
    </button>
  );
}

export default function TaxiClassesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [boot, setBoot] = useState<Boot>("loading");
  const [data, setData] = useState<DriverClasses | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchDriverClasses(signal)
      .then((d) => {
        setData(d);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  /** Общий шов сохранения: сервер возвращает пересчитанную витрину, ей и верим. */
  async function save(patch: {
    car_classes_enabled?: string[];
    car_options?: string[];
  }) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      setData(await saveDriverClasses(patch));
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не сохранилось. Проверь сеть.", "Һаҡланманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  function toggleClass(row: CarClassRow) {
    if (!data || !row.available) return;
    const next = new Set(data.classes.filter((c) => c.enabled).map((c) => c.car_class));
    if (row.enabled) next.delete(row.car_class);
    else next.add(row.car_class);
    void save({ car_classes_enabled: [...next] });
  }

  function toggleOption(code: string) {
    if (!data) return;
    const next = new Set(data.options.map(String));
    if (next.has(code)) next.delete(code);
    else next.add(code);
    void save({ car_options: [...next] });
  }

  if (boot === "loading") {
    return (
      <>
        <SubHeader title={appText("Что я вожу", "Нимә йөрөтәм")} onBack={() => navigate(-1)} />
        <LoadingList count={3} />
      </>
    );
  }

  if (boot === "error" || !data) {
    return (
      <>
        <SubHeader title={appText("Что я вожу", "Нимә йөрөтәм")} onBack={() => navigate(-1)} />
        <ErrorState onRetry={() => load()} />
      </>
    );
  }

  const car = data.car;

  return (
    <>
      <SubHeader title={appText("Что я вожу", "Нимә йөрөтәм")} onBack={() => navigate(-1)} />

      {/* Классы */}
      <h2 className="section-title">{appText("Классы машины", "Машина кластары")}</h2>
      <p className="taxi-note" style={{ marginTop: 0 }}>
        {appText(
          "Класс машина заслуживает, а не выбирает: характеристики ставит модератор при допуске. Ты включаешь то, что уже доступно.",
          "Классты машина яулай, һайламай: һыҙаттарҙы модератор ҡуя. Һин асыҡ булғанын ғына тоташтыраһың."
        )}
      </p>
      <div className="class-list">
        {data.classes.map((c) => (
          <ClassRow key={c.car_class} row={c} busy={busy} onToggle={() => toggleClass(c)} />
        ))}
      </div>

      {/* Опции салона */}
      <h2 className="section-title">{appText("Что есть в салоне", "Салонда нимә бар")}</h2>
      <p className="taxi-note" style={{ marginTop: 0 }}>
        {appText(
          "Это не класс: кресло и коляску возит машина любого класса. Отметь честно — заказ с креслом придёт только тому, у кого оно есть.",
          "Был класс түгел: ултырғысты һәм арбаны теләһә ниндәй класс машина йөрөтә. Дөрөҫ билдәлә — ултырғыслы заказ уныһы барға ғына килә."
        )}
      </p>
      <div className="chips">
        {data.all_options.map((code) => {
          const o = OPTION_TEXT[String(code)];
          const on = data.options.map(String).includes(String(code));
          return (
            <button
              key={String(code)}
              type="button"
              className={"chip" + (on ? " chip--on" : "")}
              disabled={busy}
              onClick={() => toggleOption(String(code))}
              aria-pressed={on}
            >
              {o ? `${o.emoji} ${appText(o.ru, o.ba)}` : String(code)}
            </button>
          );
        })}
      </div>

      {/* Что модератор знает о машине. Водитель это не правит — иначе классы обходятся полем. */}
      <h2 className="section-title">{appText("Моя машина", "Минең машинам")}</h2>
      <div className="info-list">
        <div className="info-row">
          <span className="info-row__k">{appText("Год выпуска", "Сығарылған йыл")}</span>
          <span className="info-row__v">{car.year ?? appText("не указан", "күрһәтелмәгән")}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Мест для пассажиров", "Юлаусы урыны")}</span>
          <span className="info-row__v">{car.seats ?? "—"}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Кондиционер", "Кондиционер")}</span>
          <span className="info-row__v">{car.ac ? appText("есть", "бар") : appText("нет", "юҡ")}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Цвет", "Төҫө")}</span>
          <span className="info-row__v">{car.color || appText("не указан", "күрһәтелмәгән")}</span>
        </div>
      </div>
      <p className="taxi-note">
        {appText(
          "Что-то указано неверно? Напиши в поддержку — характеристики меняет модератор.",
          "Нимәлер дөрөҫ түгелме? Ярҙамға яҙ — һыҙаттарҙы модератор үҙгәртә."
        )}
      </p>

      {/* Район набора: «не хватает одного» человек читает как повод позвать знакомого. */}
      {data.place && (
        <p className="taxi-note">
          <IconCar size={16} />{" "}
          {appText(`Набор классов считается по району: ${data.place}`, `Кластар йыйылышы район буйынса: ${data.place}`)}
        </p>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
