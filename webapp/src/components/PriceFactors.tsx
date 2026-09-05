// ================================================================
//  «Из чего цена» — расшифровка счёта перед заказом такси.
//  Зеркало Android (InstantOrderScreen.kt, блок priceFactors)
//  + backend instant_service._price_factors.
//
//  Что это. Сервер объясняет КАЖДЫЙ сигнал, который повлиял на сумму:
//  маршрут построен по дорогам или оценён линейкой, учтены ли пробки,
//  высокий ли спрос, далеко ли ехать машине, зимняя дорога, детское
//  кресло. Тексты приходят готовыми на двух языках — пороги и формулы
//  живут в одном месте, и клиент их не пересказывает.
//
//  Почему это важнее, чем кажется. Человек не спорит с ценой, которую
//  понимает. Одно число без объяснения читается как «сколько захотели»
//  — и первая же поездка дороже привычной становится последней.
//  В вебе этой расшифровки не было: цифра и всё (сверка с Android,
//  2026-08-30).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";

/** Один сигнал цены. `kind`: base | duration | multiplier | money. */
export interface PriceFactor {
  code: string;
  kind?: string;
  k?: number;
  active?: boolean;
  amount_rub?: number;
  title_ru?: string;
  title_ba?: string;
  description_ru?: string;
  description_ba?: string;
}

export default function PriceFactors({ factors }: { factors?: PriceFactor[] }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [open, setOpen] = useState(false);

  const list = (factors ?? []).filter((f) => f.active !== false);
  if (list.length === 0) return null;

  if (!open) {
    return (
      <button type="button" className="link-btn link-btn--quiet" onClick={() => setOpen(true)}>
        {appText("Из чего эта цена", "Был хаҡ нимәнән")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">{appText("Из чего эта цена", "Был хаҡ нимәнән")}</div>
      <ul className="price-factors">
        {list.map((f, i) => {
          const title = (ru ? f.title_ru : f.title_ba) || f.title_ru || f.code;
          const desc = (ru ? f.description_ru : f.description_ba) || f.description_ru || "";
          // Деньги показываем суммой, коэффициент — множителем. Смешивать нельзя:
          // «×1,2» и «+80 ₽» это разные вещи, и человек считает их по-разному.
          const amount =
            f.kind === "money" && f.amount_rub
              ? ru
                ? `${f.amount_rub} ₽`
                : `${f.amount_rub} һум`
              : f.kind === "multiplier" && f.k && f.k !== 1
                ? `×${f.k.toFixed(2).replace(/0$/, "").replace(".", ",")}`
                : "";
          return (
            <li key={`${f.code}-${i}`} className="price-factors__row">
              <span className="price-factors__dot" aria-hidden />
              <span className="price-factors__text">
                <span className="price-factors__title">
                  {title}
                  {amount && <b className="price-factors__amount">{amount}</b>}
                </span>
                {desc && <span className="price-factors__desc">{desc}</span>}
              </span>
            </li>
          );
        })}
      </ul>
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
        {appText("Понятно", "Аңлашылды")}
      </button>
    </div>
  );
}
