// ================================================================
//  «Скоро в Юлдаше» — что видит человек, когда направление ещё не включено
//  (запуск волнами, 13.10.2026; флаги — features.ts / featureRules.ts).
//
//  Не ошибка и не пустой экран: спокойная карточка, которая говорит правду («скоро»),
//  объясняет, что работает уже сейчас (попутки), и даёт выход — найти попутку. У доставки
//  есть вторая дверь: стать курьером заранее, набор открыт и до запуска.
//
//  Два вида, как в Android (ComingSoon.kt):
//   • inline — внутри вкладки режима на главной, шапка уже есть у хаба;
//   • screen — отдельный экран с шапкой и «назад» (вход из профиля, уведомления, старой ссылки).
//
//  Тексты те же, что в Android, слово в слово.
// ================================================================
import type { CSSProperties, ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { useAuth } from "../auth/AuthProvider";
import type { SoonKind } from "../featureRules";
import { SubHeader } from "../screens/ConsentsScreen";
import { IconMegaphone, IconTicket } from "./Icons";
import { YuModeCourier } from "./BrandIcons";

export type ComingSoonKind = SoonKind;

type Pair = readonly [ru: string, ba: string];

/** Название направления: шапка экрана и метка над заголовком. */
const NAME: Record<ComingSoonKind, Pair> = {
  delivery: ["Доставка", "Илтеү"],
  courier: ["Режим курьера", "Курьер режимы"],
  ads: ["Реклама", "Реклама"],
  business: ["Скидки по пути", "Юл буйынса ташламалар"],
};

const BODY: Record<ComingSoonKind, Pair> = {
  delivery: [
    "Посылки и курьеры скоро появятся в Юлдаше. Пока Юлдаш — это попутки: найди машину по пути или предложи свою.",
    "Бандеролдәр һәм курьерҙар тиҙҙән Юлдашта булыр. Әлегә Юлдаш — юлдаш менән сәфәр: юл ыңғайындағы машина тап йәки үҙ сәфәреңде тәҡдим ит.",
  ],
  // Посылки «по пути» уже работают, не работает только курьерский режим.
  courier: [
    "Работа курьером скоро откроется. А пока можно отвезти посылку по пути.",
    "Курьер эше тиҙҙән асыла. Ә әлегә бандеролде юл ыңғайында илтергә була.",
  ],
  ads: [
    "Реклама в Юлдаше скоро откроется. Готовим честную маркировку, как требует закон.",
    "Юлдашта реклама тиҙҙән асыла. Закон талап иткәнсә, намыҫлы билдәләмә әҙерләйбеҙ.",
  ],
  business: [
    "«Скидки по пути» скоро откроются: местные кафе, магазины и мастерские со скидками для попутчиков.",
    "«Юл буйынса ташламалар» тиҙҙән асыла: ерле кафелар, кибеттәр һәм оҫтаханалар юлдаштарға ташлама бирәсәк.",
  ],
};

function KindIcon({ kind }: { kind: ComingSoonKind }) {
  if (kind === "ads") return <IconMegaphone size={40} />;
  if (kind === "business") return <IconTicket size={40} />;
  return <YuModeCourier size={40} />;
}

const step = (i: number) => ({ "--i": i }) as CSSProperties;

interface Props {
  kind: ComingSoonKind;
  /** inline — без шапки (внутри вкладки); screen — с шапкой и «назад». */
  variant?: "inline" | "screen";
  /** Куда ведёт главная кнопка. По умолчанию — на карту попуток. */
  onFindRide?: () => void;
}

export default function ComingSoon({ kind, variant = "screen", onFindRide }: Props) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const name = appText(...NAME[kind]);
  const title = appText("Скоро в Юлдаше", "Тиҙҙән Юлдашта");
  const body = appText(...BODY[kind]);

  // Курьерский режим при работающих посылках: ведём к посылкам, там «по пути» уже есть.
  const primary =
    kind === "courier"
      ? { label: appText("К посылкам", "Бандеролдәргә"), go: () => navigate("/parcels") }
      : { label: appText("Найти попутку", "Юлдаш табыу"), go: onFindRide ?? (() => navigate("/map")) };
  // У доставки вторая дверь: набор курьеров открыт и до запуска.
  const secondary: { label: string; go: () => void } | null =
    kind === "delivery" || kind === "courier"
      ? { label: appText("Стать курьером заранее", "Алдан курьер булыу"), go: () => navigate("/courier-onboarding") }
      : null;

  const accent = kind === "delivery" || kind === "courier" ? "coming-soon--courier" : "coming-soon--green";
  const content: ReactNode = (
    <section className={`coming-soon ${accent}`} aria-labelledby={`coming-soon-${kind}`}>
      <span className="coming-soon__badge" aria-hidden>
        <KindIcon kind={kind} />
      </span>
      <div className="coming-soon__text appear" style={step(1)}>
        <span className="coming-soon__name">{name}</span>
        <h2 className="coming-soon__title" id={`coming-soon-${kind}`}>
          {title}
        </h2>
        <p className="coming-soon__body">{body}</p>
      </div>
      <div className="coming-soon__actions appear" style={step(2)}>
        <button type="button" className="btn-primary" onClick={primary.go}>
          {primary.label}
        </button>
        {kind === "business" && <>
          <button type="button" className="btn-soft" onClick={() => navigate(isAuthed ? "/coupons" : "/login")}>{appText("Мои купоны", "Минең купондар")}</button>
          <button type="button" className="btn-soft" onClick={() => navigate(isAuthed ? "/partner" : "/login")}>{appText("Погасить код клиента", "Клиент кодын һүндереү")}</button>
        </>}
        {secondary && (
          <button type="button" className="btn-soft" onClick={secondary.go}>
            {secondary.label}
          </button>
        )}
      </div>
    </section>
  );

  if (variant === "inline") return content;
  return (
    <>
      <SubHeader title={name} onBack={() => navigate(-1)} />
      {content}
    </>
  );
}
