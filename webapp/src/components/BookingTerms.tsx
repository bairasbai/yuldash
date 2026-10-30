import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { IconChevron } from "./Icons";

/** Публичные пороги RS-07А. Отсутствие ответа не заменяется выдуманными числами. */
export interface BookingTermsPolicy {
  late_cancel_before_min?: number | null;
  no_show_wait_min?: number | null;
}

const minutes = (value: unknown): number | null =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;

/**
 * RS-07А, только независимый информационный блок ДО брони (PR #211 @54802962).
 * Хозяин шторки передаст /safety/policy после реализации серверной части.
 * Здесь нет API, часов телефона, личных данных, отметки «прочитал» или оплаты.
 * Подключение к RideSheet/отмене и предупреждение ScamTip ждут своих зависимостей.
 * BA — черновик; перенести в docs/tasks.md после освобождения общего файла.
 */
export default function BookingTerms({ policy }: { policy?: BookingTermsPolicy | null }) {
  const { appText } = useLang();
  const [expanded, setExpanded] = useState(false);
  const [reduceMotion, setReduceMotion] = useState(true);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReduceMotion(query.matches);
    update();
    query.addEventListener?.("change", update);
    return () => query.removeEventListener?.("change", update);
  }, []);

  const late = minutes(policy?.late_cancel_before_min);
  const wait = minutes(policy?.no_show_wait_min);
  const motion = reduceMotion ? { animation: "none", transition: "none" } : undefined;

  return (
    <section className="act-card" style={motion} aria-label={appText("Условия до брони", "Бронлау алдынан шарттар")}>
      <h3 className="act-card__title">{appText("Как платить", "Нисек түләргә")}</h3>
      <p className="act-card__text">
        {appText(
          "Наличными или переводом — как договоритесь с водителем. Через Юлдаш деньги не идут.",
          "Наличный менән йәки күсереп — йөрөтөүсе менән нисек килешәһегеҙ. Аҡса Юлдаш аша үтмәй."
        )}
      </p>
      <h3 className="act-card__title">{appText("Если планы поменяются", "Пландар үҙгәрһә")}</h3>
      <p className="act-card__text">
        {late !== null
          ? appText(
              `Отменить можно бесплатно. Позже чем за ${late} мин до выезда отмена попадёт в «Надёжность» — её видят другие.`,
              `Бушлай кире алырға була. Ҡуҙғалырға ${late} минуттан аҙыраҡ ҡалғас кире алыу «Ышаныслылыҡ»ҡа инәсәк — уны башҡалар күрә.`
            )
          : appText(
              "Отменить можно бесплатно. Незадолго до выезда отмена попадёт в «Надёжность» — её видят другие.",
              "Бушлай кире алырға була. Ҡуҙғалыр алдынан кире алыу «Ышаныслылыҡ»ҡа инәсәк — уны башҡалар күрә."
            )}
      </p>

      <div className={"faq-item" + (expanded ? " faq-item--open" : "")} style={motion}>
        <button type="button" className="faq-q" aria-expanded={expanded} onClick={() => setExpanded((open) => !open)}>
          <span>{expanded ? appText("Свернуть", "Йыйырға") : appText("Подробнее", "Тулыраҡ")}</span>
          <span className="faq-q__chev" aria-hidden style={motion}><IconChevron size={20} /></span>
        </button>
        {expanded && (
          <div className="faq-a" style={motion}>
            <p>{appText("Отменил заранее — место вернётся, водитель получит уведомление.", "Алдан кире алдың — урын бушаясаҡ, йөрөтөүсегә хәбәр киләсәк.")}</p>
            <p>{appText("Незадолго до выезда или когда водитель выехал — бесплатно, но снизит «Надёжность».", "Ҡуҙғалыр алдынан йәки йөрөтөүсе юлға сыҡҡас — бушлай, әммә «Ышаныслылыҡ» кәмейәсәк.")}</p>
            <p>{appText("Отменил водитель — придёт уведомление, покажем другие поездки.", "Йөрөтөүсе кире алды — хәбәр киләсәк, башҡа сәфәрҙәрҙе күрһәтербеҙ.")}</p>
            <p>
              {wait !== null
                ? appText(
                    `Не пришёл — водитель может отметить это не раньше чем через ${wait} мин после самого позднего из: выезд, «Подъезжаю», расчётный приезд на остановку. В «Надёжность» — только если разбор подтвердит.`,
                    `Килмәнең — йөрөтөүсе быны иң һуңғы ваҡиғанан ${wait} минут үткәс кенә билдәләй ала: ҡуҙғалыу, «Яҡынлашам», туҡталышҡа иҫәпләнгән килеп етеү. «Ышаныслылыҡ»ҡа — тик тикшереү раҫлаһа ғына.`
                  )
                : appText(
                    "Не пришёл — водитель может отметить это после ожидания от самого позднего из: выезд, «Подъезжаю», расчётный приезд на остановку. В «Надёжность» — только если разбор подтвердит.",
                    "Килмәнең — йөрөтөүсе быны иң һуңғы ваҡиғанан һуң көтөп билдәләй ала: ҡуҙғалыу, «Яҡынлашам», туҡталышҡа иҫәпләнгән килеп етеү. «Ышаныслылыҡ»ҡа — тик тикшереү раҫлаһа ғына."
                  )}
            </p>
            <p>{appText("Водитель не приехал — сообщи о проблеме.", "Йөрөтөүсе килмәне — проблема тураһында хәбәр ит.")}</p>
            <p>{appText("Уже в машине — отменить нельзя; «Сообщить о проблеме» или SOS.", "Машиналаһың — кире алырға ярамай; «Проблема тураһында хәбәр итеү» йәки SOS.")}</p>
          </div>
        )}
      </div>
    </section>
  );
}
