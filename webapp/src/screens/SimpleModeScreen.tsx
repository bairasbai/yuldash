// ================================================================
//  «Простой режим» — хаб доступности крупными карточками для тех,
//  кому сложно с обычным интерфейсом (пожилые, слабое зрение).
//  Публичный. Плюс глобальный переключатель размера шрифта
//  (точка правды — src/fontScale.ts, тот же хук в Настройках).
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { useFontScale, type FontScale } from "../fontScale";
import { SubHeader } from "./ConsentsScreen";
import {
  YuRoute,
  YuModeTaxi,
  YuChat,
  YuSupport,
} from "../components/BrandIcons";
import { IconMic, IconHeart, IconPhone, IconUsers } from "../components/Icons";

interface Tile {
  to: string;
  icon: JSX.Element;
  title: string;
  sub: string;
  tone?: "sos";
}

export default function SimpleModeScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [scale, setScale] = useFontScale();

  const tiles: Tile[] = [
    {
      to: "/rides",
      icon: <YuRoute size={40} />,
      title: appText("Найти поездку", "Сәфәр табырға"),
      sub: appText("Попутки рядом", "Яҡындағы юлдаштар"),
    },
    {
      to: "/taxi",
      icon: <YuModeTaxi size={40} />,
      title: appText("Вызвать машину", "Машина саҡырырға"),
      sub: appText("Быстрый заказ", "Тиҙ заказ"),
    },
    {
      to: "/voice",
      icon: <IconMic size={38} />,
      title: appText("Сказать голосом", "Тауыш менән әйтергә"),
      sub: appText("Голосовая заявка", "Тауышлы заявка"),
    },
    {
      to: "/family-order",
      icon: <IconHeart size={38} />,
      title: appText("За близкого", "Яҡын өсөн"),
      sub: appText("Заказать другому", "Башҡаға заказ"),
    },
    {
      to: "/callback",
      icon: <IconPhone size={38} />,
      title: appText("Перезвоните мне", "Миңә шылтыратығыҙ"),
      sub: appText("Мы поможем сами", "Беҙ ярҙам итәбеҙ"),
    },
    {
      to: "/trusted",
      icon: <IconUsers size={38} />,
      title: appText("Доверенные", "Ышаныслылар"),
      sub: appText("Кому сообщить", "Кемгә хәбәр итергә"),
    },
    {
      to: "/chat",
      icon: <YuChat size={40} />,
      title: appText("Чат", "Чат"),
      sub: appText("Написать своим", "Үҙеңдекеләргә яҙырға"),
    },
    {
      to: "/sos",
      icon: <YuSupport size={40} />,
      title: appText("Помощь", "Ярҙам"),
      sub: appText("Экстренный вызов", "Ашығыс саҡырыу"),
      tone: "sos",
    },
  ];

  const sizes: { key: FontScale; label: string; sample: string }[] = [
    { key: "normal", label: appText("Обычный", "Ғәҙәти"), sample: "A" },
    { key: "large", label: appText("Крупный", "Ҙур"), sample: "A" },
    { key: "xlarge", label: appText("Очень крупный", "Бик ҙур"), sample: "A" },
  ];

  return (
    <>
      <SubHeader
        title={appText("Простой режим", "Ябай режим")}
        subtitle={appText("Крупно и по делу", "Ҙур һәм асыҡ")}
        onBack={() => navigate(-1)}
      />

      {/* Размер шрифта — глобально, точка правды в fontScale.ts */}
      <div className="fontscale">
        <div className="fontscale__label">{appText("Размер текста", "Текст ҙурлығы")}</div>
        <div className="seg fontscale__seg">
          {sizes.map((s) => (
            <button
              key={s.key}
              type="button"
              className={"seg__item" + (scale === s.key ? " is-active" : "")}
              onClick={() => setScale(s.key)}
              aria-pressed={scale === s.key}
            >
              <span className={"fontscale__a fontscale__a--" + s.key} aria-hidden>{s.sample}</span>
              {s.label}
            </button>
          ))}
        </div>
      </div>

      <div className="hub-grid">
        {tiles.map((tile) => (
          <button
            key={tile.to}
            type="button"
            className={"hub-card" + (tile.tone === "sos" ? " hub-card--sos" : "")}
            onClick={() => navigate(tile.to)}
          >
            <span className="hub-card__emoji" aria-hidden>{tile.icon}</span>
            <span className="hub-card__title">{tile.title}</span>
            <span className="hub-card__sub">{tile.sub}</span>
          </button>
        ))}
      </div>
    </>
  );
}
