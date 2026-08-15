// ================================================================
//  «Безопасность» — хаб → /safety. Зеркало Android SafetyScreen
//  (SecondaryScreens.kt).
//
//  Смысл экрана: когда человеку страшно или неспокойно, он не должен
//  вспоминать, в каком меню лежит SOS, чёрный список и жалоба. Всё,
//  что защищает, собрано на одном экране, и SOS — первым.
//
//  «Только проверенные» берём из общих фильтров (filterPrefs.
//  onlyTrusted), а не заводим второй флаг: иначе в двух местах
//  приложения одна настройка жила бы своей жизнью.
// ================================================================
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { loadFilters, saveFilters } from "../filterPrefs";
import { SubHeader } from "./ConsentsScreen";
import {
  IconBlock,
  IconCheck,
  IconChevron,
  IconFlag,
  IconLock,
  IconShield,
  IconUsers,
} from "../components/Icons";

export default function SafetyScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [onlyTrusted, setOnlyTrusted] = useState(() => loadFilters().onlyTrusted);

  function toggleTrusted(next: boolean) {
    setOnlyTrusted(next);
    saveFilters({ ...loadFilters(), onlyTrusted: next });
  }

  const rows: { to: string; icon: JSX.Element; title: string; sub: string }[] = [
    {
      to: "/trusted",
      icon: <IconUsers size={22} />,
      title: appText("Поделиться поездкой с близким", "Сәфәрҙе яҡын кешегә ебәреү"),
      sub: appText("Отправь данные о поездке близкому человеку.", "Сәфәр мәғлүмәтен яҡын кешегә ебәр."),
    },
    {
      to: "/blocklist",
      icon: <IconBlock size={22} />,
      title: appText("Чёрный список", "Ҡара исемлек"),
      sub: appText("Те, с кем ты не хочешь ездить.", "Сәфәр итмәҫкә теләгән ҡулланыусылар."),
    },
    {
      to: "/report",
      icon: <IconFlag size={22} />,
      title: appText("Пожаловаться на пользователя", "Ҡулланыусыға ялыу"),
      sub: appText("Сообщи о нарушении правил или безопасности.", "Ҡағиҙә йәки хәүефһеҙлек боҙолоуын хәбәр ит."),
    },
    {
      to: "/fairness",
      icon: <IconShield size={22} />,
      title: appText("Центр справедливости", "Ғәҙеллек үҙәге"),
      sub: appText("Твоё положение и разборы споров.", "Хәлең һәм бәхәстәрҙе ҡарау."),
    },
    {
      to: "/rules",
      icon: <IconCheck size={22} />,
      title: appText("Правила поездок", "Сәфәр ҡағиҙәләре"),
      sub: appText("Почитай правила сервиса Юлдаш.", "Юлдаш ҡағиҙәләре менән таныш."),
    },
  ];

  return (
    <>
      <SubHeader
        title={appText("Безопасность", "Хәүефһеҙлек")}
        subtitle={appText(
          "Твои данные и поездки под защитой",
          "Һинең мәғлүмәт һәм сәфәрҙәр һаҡланған"
        )}
        onBack={() => navigate(-1)}
      />

      {/* SOS — первым и крупно: в тревоге искать по меню некогда */}
      <button
        type="button"
        className="act-card act-card--warn"
        style={{ width: "100%", textAlign: "left" }}
        onClick={() => navigate("/sos")}
      >
        <div className="act-card__title">
          <IconShield size={18} /> {appText("Нужна помощь?", "Ярҙам кәрәкме?")}
        </div>
        <p className="act-card__text" style={{ marginBottom: 0 }}>
          {appText(
            "Свяжись с экстренными службами и поддержкой Юлдаш.",
            "Ашығыс хеҙмәттәр һәм Юлдаш ярҙамы менән бәйлән."
          )}
        </p>
      </button>

      {/* Телефон скрыт — не настройка, а обещание сервиса */}
      <div className="list" style={{ marginTop: 12 }}>
        <div className="list-row">
          <span className="list-row__icon">
            <IconLock size={22} />
          </span>
          <div className="list-row__main">
            <div className="list-row__title">
              {appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерелгән")}
            </div>
            <div className="list-row__sub">
              {appText(
                "Твой номер откроется попутчику только после подтверждения поездки — так устроен Юлдаш.",
                "Номерың юлдашҡа тик сәфәр раҫланғас ҡына асыла — Юлдаш шулай эшләй."
              )}
            </div>
          </div>
        </div>

        <label className="list-row list-row--check">
          <span className="list-row__icon">
            <IconCheck size={22} />
          </span>
          <div className="list-row__main">
            <div className="list-row__title">
              {appText("Только проверенные участники", "Тик раҫланған ҡатнашыусылар")}
            </div>
            <div className="list-row__sub">
              {appText(
                "Показывать и принимать поездки только от проверенных пользователей.",
                "Тик раҫланған ҡулланыусыларҙың сәфәрҙәрен күрһәтергә."
              )}
            </div>
          </div>
          <input
            type="checkbox"
            className="checkbox"
            checked={onlyTrusted}
            onChange={(e) => toggleTrusted(e.target.checked)}
            aria-label={appText("Только проверенные участники", "Тик раҫланған ҡатнашыусылар")}
          />
        </label>

        {rows.map((r) => (
          <Link key={r.to} className="list-row list-row--link" to={r.to}>
            <span className="list-row__icon">{r.icon}</span>
            <div className="list-row__main">
              <div className="list-row__title">{r.title}</div>
              <div className="list-row__sub">{r.sub}</div>
            </div>
            <span className="list-row__chev">
              <IconChevron size={18} />
            </span>
          </Link>
        ))}
      </div>

      <div className="act-card act-card--mint">
        <div className="act-card__title">
          <IconShield size={18} /> {appText("Безопасность в Юлдаше", "Юлдашта хәүефһеҙлек")}
        </div>
        <p className="act-card__text" style={{ marginBottom: 0 }}>
          {appText(
            "Проверяем участников, скрываем телефон и даём быстрый SOS.",
            "Ҡатнашыусыларҙы тикшерәбеҙ, телефонды йәшерәбеҙ һәм тиҙ SOS бирәбеҙ."
          )}
        </p>
      </div>
    </>
  );
}
