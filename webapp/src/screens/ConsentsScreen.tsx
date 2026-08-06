import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { flags, type ConsentKind, type Consents } from "../flags";
import { IconChevron } from "../components/Icons";

/**
 * Согласия по 152-ФЗ (оферта / политика конфиденциальности / геолокация).
 * У бэкенда пока нет эндпоинта согласий — отметку честно храним на устройстве (flags),
 * без выдуманного API. Когда появится /me/consents — заменим один слой (api).
 */
export default function ConsentsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [state, setState] = useState<Consents>(() => flags.consents());

  const toggle = (kind: ConsentKind) =>
    setState((prev) => flags.setConsent(kind, !prev[kind]));

  const items: {
    kind: ConsentKind;
    title: string;
    body: string;
  }[] = [
    {
      kind: "offer",
      title: appText("Оферта (условия сервиса)", "Оферта (хеҙмәт шарттары)"),
      body: appText(
        "Правила пользования Юлдашем: как устроены поездки, оплата и ответственность.",
        "Юлдашты ҡулланыу ҡағиҙәләре: сәфәр, түләү һәм яуаплылыҡ нисек ойошторолған."
      ),
    },
    {
      kind: "privacy",
      title: appText("Политика конфиденциальности", "Ҡупшылыҡ сәйәсәте"),
      body: appText(
        "Какие данные мы храним, зачем и как защищаем. Данные — по минимуму.",
        "Ниндәй мәғлүмәт һаҡлайбыҙ, ни өсөн һәм нисек яҡлайбыҙ. Мәғлүмәт — минимум."
      ),
    },
    {
      kind: "geo",
      title: appText("Геолокация", "Геолокация"),
      body: appText(
        "Разрешение на геопозицию — только для поездки и только когда нужно.",
        "Геопозицияға рөхсәт — тик сәфәр өсөн һәм тик кәрәк саҡта."
      ),
    },
  ];

  const allDone = items.every((i) => state[i.kind]);

  return (
    <>
      <SubHeader
        title={appText("Согласия", "Ризалыҡтар")}
        subtitle={appText("152-ФЗ · твои данные под защитой", "152-ФЗ · мәғлүмәтең яҡлауҙа")}
        onBack={() => navigate(-1)}
      />

      <div className="list">
        {items.map((it) => (
          <label key={it.kind} className="list-row list-row--check">
            <div className="list-row__main">
              <div className="list-row__title">{it.title}</div>
              <div className="list-row__sub">{it.body}</div>
            </div>
            <input
              type="checkbox"
              className="checkbox"
              checked={state[it.kind]}
              onChange={() => toggle(it.kind)}
              aria-label={it.title}
            />
          </label>
        ))}
      </div>

      <div className={"consents__status" + (allDone ? " ok" : "")}>
        {allDone
          ? appText("Спасибо! Все согласия отмечены.", "Рәхмәт! Барлыҡ ризалыҡтар билдәләнгән.")
          : appText("Отметь согласия, чтобы пользоваться всеми возможностями.", "Бар мөмкинлектәр өсөн ризалыҡтарҙы билдәлә.")}
      </div>
    </>
  );
}

/** Компактная шапка вложенного экрана с кнопкой «назад». */
export function SubHeader({
  title,
  subtitle,
  onBack,
}: {
  title: string;
  subtitle?: string;
  onBack: () => void;
}) {
  return (
    <header className="screen-header">
      <div className="screen-header__row">
        <button type="button" className="subheader__back" onClick={onBack} aria-label="←">
          <span style={{ display: "inline-flex", transform: "rotate(180deg)" }}>
            <IconChevron size={22} />
          </span>
        </button>
        <div style={{ flex: 1 }}>
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </div>
      </div>
    </header>
  );
}
