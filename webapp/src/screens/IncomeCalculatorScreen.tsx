// ================================================================
//  Калькулятор дохода автора → /admin/income (RequireAdmin).
//  Чистый расчёт на клиенте (зеркало android IncomeCalculatorScreen.kt) —
//  ползунки: поездок/день, бизнес-партнёры, цена подписки, Boost, маршруты,
//  вкл/выкл такси (комиссия) → месячная выручка и «чистыми» вживую.
//  Коэффициенты — стартовые (Boost ~70 ₽, расходы ~15%), меняются здесь.
//  Все цифры — ОЦЕНКА. Двуязычно, токены Canon, тач-цели ≥48px.
// ================================================================
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";

/** «45000» → «45 000 ₽». */
function rub(v: number): string {
  return `${Math.round(v).toLocaleString("ru-RU")} ₽`;
}

// Коэффициенты расчёта (стартовая гипотеза; правь здесь). Boost — фикс/подъём, расходы — % от выручки.
const BOOST_PRICE = 70;
const COSTS_PCT = 15;

export default function IncomeCalculatorScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  // Дефолты = «реальный» сценарий Сибай–Магнитогорск.
  const [ridesPerDay, setRidesPerDay] = useState(80);
  const [partners, setPartners] = useState(30);
  const [subPrice, setSubPrice] = useState(1500);
  const [boostsPerDay, setBoostsPerDay] = useState(15);
  const [routes, setRoutes] = useState(1);
  const [taxiOn, setTaxiOn] = useState(false);
  const [avgCheck, setAvgCheck] = useState(500);
  const [commissionPct, setCommissionPct] = useState(8);

  const calc = useMemo(() => {
    const couponsIncome = partners * subPrice; // купоны/реклама бизнеса
    const boostIncome = boostsPerDay * BOOST_PRICE * 30; // Boost водителей
    const taxiIncome = taxiOn ? ridesPerDay * avgCheck * (commissionPct / 100) * 30 : 0; // комиссия такси
    const perRoute = couponsIncome + boostIncome + taxiIncome;
    const gross = perRoute * routes;
    const net = gross * (1 - COSTS_PCT / 100);
    return { couponsIncome, boostIncome, taxiIncome, gross, net };
  }, [ridesPerDay, partners, subPrice, boostsPerDay, routes, taxiOn, avgCheck, commissionPct]);

  return (
    <>
      <SubHeader
        title={appText("Калькулятор дохода", "Килем калькуляторы")}
        subtitle={appText("Прогноз выручки автора", "Автор килеме фаразы")}
        onBack={() => navigate(-1)}
      />

      {/* Результат — большая карточка */}
      <div className="income-hero">
        <span className="income-hero__cap">{appText("Чистыми тебе в месяц", "Айына таҙа килем")}</span>
        <b className="income-hero__net">{rub(calc.net)}</b>
        <span className="income-hero__note">
          {appText(
            `Выручка ${rub(calc.gross)} − расходы ~${COSTS_PCT}% (серверы, налог, платёжка)`,
            `Килем ${rub(calc.gross)} − сығымдар ~${COSTS_PCT}% (серверҙар, һалым, түләү)`
          )}
        </span>
      </div>

      {/* Из чего складывается */}
      <div className="admin-card" style={{ marginTop: 12 }}>
        <div className="admin-card__title" style={{ fontSize: "var(--font-body)", marginBottom: 8 }}>
          {appText("Из чего доход", "Килем нимәнән")}
        </div>
        <Breakdown label={appText("Купоны и реклама бизнеса", "Купондар һәм бизнес рекламаһы")} value={rub(calc.couponsIncome * routes)} />
        <Breakdown label={appText("Boost водителей", "Йөрөтөүселәр Boost'ы")} value={rub(calc.boostIncome * routes)} />
        {taxiOn && <Breakdown label={appText("Комиссия такси", "Такси комиссияһы")} value={rub(calc.taxiIncome * routes)} />}
      </div>

      {/* Ползунки */}
      <div className="calc-list">
        <CalcSlider label={appText("Поездок в день (по маршруту)", "Көнөнә сәфәр (маршрут буйынса)")} value={ridesPerDay} min={0} max={300} onChange={setRidesPerDay} display={String(ridesPerDay)} />
        <CalcSlider label={appText("Бизнес-партнёров", "Бизнес-партнёрҙар")} value={partners} min={0} max={100} onChange={setPartners} display={String(partners)} />
        <CalcSlider label={appText("Цена подписки партнёра, ₽/мес", "Партнёр яҙылыуы, ₽/ай")} value={subPrice} min={500} max={3000} step={50} onChange={setSubPrice} display={`${subPrice} ₽`} />
        <CalcSlider label={appText("Boost в день (подъёмов)", "Көнөнә Boost (күтәреү)")} value={boostsPerDay} min={0} max={50} onChange={setBoostsPerDay} display={String(boostsPerDay)} />
        <CalcSlider label={appText("Маршрутов / городов", "Маршрут / ҡала")} value={routes} min={1} max={54} onChange={setRoutes} display={String(routes)} />
      </div>

      {/* Такси (опционально) */}
      <div className="admin-card" style={{ marginTop: 12 }}>
        <label className="admin-check" style={{ marginTop: 0, justifyContent: "space-between" }}>
          <span style={{ fontWeight: 700, fontSize: "var(--font-body)" }}>{appText("Включить такси (комиссия)", "Таксины ҡабыҙыу (комиссия)")}</span>
          <input type="checkbox" checked={taxiOn} onChange={(e) => setTaxiOn(e.target.checked)} aria-label={appText("Включить такси", "Таксины ҡабыҙыу")} />
        </label>
        {taxiOn ? (
          <div className="calc-list" style={{ marginTop: 10 }}>
            <CalcSlider label={appText("Средний чек места, ₽", "Урын уртаса хаҡы, ₽")} value={avgCheck} min={200} max={1500} step={10} onChange={setAvgCheck} display={`${avgCheck} ₽`} />
            <CalcSlider label={appText("Комиссия, %", "Комиссия, %")} value={commissionPct} min={0} max={10} onChange={setCommissionPct} display={`${commissionPct} %`} />
          </div>
        ) : (
          <p className="admin-card__sub" style={{ marginTop: 8 }}>
            {appText(
              "Попутка бесплатна для людей — доход только с бизнеса. Такси добавляет комиссию.",
              "Юлдаш кешеләргә бушлай — килем тик бизнестан. Такси комиссия өҫтәй."
            )}
          </p>
        )}
      </div>

      {/* Дисклеймер */}
      <p className="calc-disclaimer">
        {appText(
          "Это оценка, а не обещание: доход зависит от того, сколько людей и бизнесов подключится. Цены — стартовые, поменяешь в коде/админке.",
          "Был баһалау, вәғәҙә түгел: килем күпме кеше һәм бизнес ҡушылыуына бәйле. Хаҡтар — башланғыс, кодта/админкала үҙгәртәһең."
        )}
      </p>
    </>
  );
}

function Breakdown({ label, value }: { label: string; value: string }) {
  return (
    <div className="income-breakdown">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

function CalcSlider({
  label,
  value,
  min,
  max,
  step = 1,
  display,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  display: string;
  onChange: (v: number) => void;
}) {
  return (
    <div className="calc-slider">
      <div className="calc-slider__head">
        <span className="calc-slider__label">{label}</span>
        <span className="calc-slider__value">{display}</span>
      </div>
      <input
        type="range"
        className="calc-range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
      />
    </div>
  );
}
