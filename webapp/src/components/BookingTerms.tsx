import { useEffect, useState } from "react";
import { isAccountSession } from "../api/client";
import { fetchSafetyPolicy, type SafetyPolicy } from "../api/safety";
import { useLang } from "../i18n/lang";
import type { BookingCommandOwner } from "../utils/bookingCommandStore";
import { SCAM_TIP_SEEN_KEY } from "../utils/privacy";
import { IconChevron } from "./Icons";

function policyMinutes(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

export function BookingTerms() {
  const { appText } = useLang();
  const [policy, setPolicy] = useState<SafetyPolicy | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void fetchSafetyPolicy(controller.signal).then((value) => {
      if (!controller.signal.aborted) setPolicy(value);
    }).catch(() => { /* Условия сразу показаны без чисел. */ });
    return () => controller.abort();
  }, []);

  const late = policyMinutes(policy?.late_cancel_before_min);
  const noShow = policyMinutes(policy?.no_show_wait_min);

  return (
    <section className="act-card" aria-label={appText("Условия поездки", "Сәфәр шарттары")}>
      <h3 className="act-card__title">{appText("Как платить", "Нисек түләргә")}</h3>
      <p className="act-card__text">{appText(
        "Договорись с водителем о наличных или переводе. Оплата напрямую водителю.",
        "Йөрөтөүсе менән ҡулаҡса йәки күсереү тураһында килеш. Түләү туранан-тура йөрөтөүсеһенә.",
      )}</p>
      <h3 className="act-card__title">{appText("Если планы поменяются", "Пландар үҙгәрһә")}</h3>
      <p className="act-card__text">{appText(
        "До посадки бронь можно отменить бесплатно. Поздняя отмена влияет на показатель «Надёжность» в профиле.",
        "Ултырғанға тиклем бронде бушлай кире алырға була. Һуң кире алыу профилдәге «Ышаныслылыҡ» күрһәткесенә тәьҫир итә.",
      )}</p>
      <div className={expanded ? "faq-item faq-item--open" : "faq-item"}>
        <button type="button" className="faq-q" aria-expanded={expanded}
          onClick={() => setExpanded((open) => !open)}>
          <span>{expanded ? appText("Свернуть", "Йыйырға") : appText("Подробнее об отмене", "Кире алыу тураһында тулыраҡ")}</span>
          <span className="faq-q__chev" aria-hidden><IconChevron size={20} /></span>
        </button>
        {expanded && <div className="faq-a">
          <p>{late === null
            ? appText(
              "Поздней может считаться отмена незадолго до выезда. Отмена после отметки водителя «Выехал» или «Подъезжаю» тоже считается поздней.",
              "Сығыу алдынан кире алыу һуң тип һаналырға мөмкин. Йөрөтөүсе «Юлға сыҡтым» йәки «Яҡынлашам» тип билдәләгәндән һуң кире алыу ҙа һуң тип һанала.",
            )
            : appText(
              `Отмена считается поздней за ${late} мин до выезда и позже, а также после отметки водителя «Выехал» или «Подъезжаю».`,
              `Сығыуға ${late} минут йәки унан әҙерәк ҡалһа, йә йөрөтөүсе «Юлға сыҡтым» йәки «Яҡынлашам» тип билдәләһә, кире алыу һуң тип һанала.`,
            )}</p>
          <p>{noShow === null
            ? appText(
              "Водитель может отметить неявку после ожидания. Время ожидания считают с учётом выезда, подъезда и выбранной остановки.",
              "Йөрөтөүсе көткәндән һуң килмәүҙе билдәләй ала. Көтөү ваҡытын иҫәпләгәндә сығыу, яҡынлашыу һәм һайланған туҡталыш иҫәпкә алына.",
            )
            : appText(
              `Водитель может отметить неявку через ${noShow} мин после самого позднего из событий: выезд, «Подъезжаю» или расчётное прибытие на остановку.`,
              `Йөрөтөүсе иң һуңғы ваҡиғанан ${noShow} минут үткәс килмәүҙе билдәләй ала: юлға сығыу, «Яҡынлашам» йәки туҡталышҡа иҫәпләнгән килеп етеү ваҡыты.`,
            )}</p>
          <p>{appText(
            "Неявка влияет на «Надёжность» после подтверждения по итогам разбора. Если возникла проблема, сообщи о ней в приложении.",
            "Килмәү тикшереү һөҙөмтәһендә раҫланһа, «Ышаныслылыҡ»ҡа тәьҫир итә. Проблема килеп сыҡһа, уны ҡушымтала хәбәр ит.",
          )}</p>
        </div>}
      </div>
    </section>
  );
}

export function BookingScamTip({ owner }: { owner: BookingCommandOwner | null }) {
  const { appText } = useLang();
  const marker = owner ? JSON.stringify([owner.userId, owner.generation]) : null;
  const [seen, setSeen] = useState(() => {
    if (!owner || !isAccountSession(owner)) return false;
    try { return localStorage.getItem(SCAM_TIP_SEEN_KEY) === marker; }
    catch { return false; }
  });

  function acknowledge() {
    if (owner) {
      if (!isAccountSession(owner) || marker === null) return;
      try { localStorage.setItem(SCAM_TIP_SEEN_KEY, marker); }
      catch { return; }
    }
    setSeen(true);
  }

  if (seen) return null;
  return (
    <aside className="act-card act-card--warn" aria-label={appText("Береги себя и деньги", "Үҙеңде һәм аҡсаңды һаҡла")}>
      <h3 className="act-card__title">{appText("Береги себя и деньги", "Үҙеңде һәм аҡсаңды һаҡла")}</h3>
      <p className="act-card__text">{appText(
        "Не отправляй предоплату на чужой номер. Никому не сообщай коды из СМС.",
        "Сит номерға алдан түләмә. СМС кодтарын бер кемгә лә әйтмә.",
      )}</p>
      <button type="button" className="btn-ghost" style={{ minHeight: 48 }} onClick={acknowledge}>
        {appText("Понятно", "Аңлашылды")}
      </button>
    </aside>
  );
}
