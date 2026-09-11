// ================================================================
//  Настройки → /settings (RequireAuth). Управление приложением:
//   • Язык РУС/БАШ — переиспользует механизм useLang (localStorage);
//   • Тема светлая/тёмная/системная — точка правды src/theme.ts;
//   • Размер текста — точка правды src/fontScale.ts (тот же хук, без дублей);
//   • Уведомления / звуки — локальные флаги (src/uiPrefs.ts);
//   • Ссылки: Приватность / Правила / Чёрный список / Согласия / Как оплатить / Пожаловаться;
//   • Удалить аккаунт (POST /me/delete, двойное подтверждение) → чистка сессии → старт;
//   • Выход.
//  Всё двуязычно, токены Canon, safe-area, тач-цели ≥48px.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { useTheme, type ThemeMode } from "../theme";
import { useFontScale, type FontScale } from "../fontScale";
import { getUiPref, setUiPref, type UiPrefKey } from "../uiPrefs";
import PushToggle from "../components/PushToggle";
import { deleteAccount } from "../api/auth";
import { ApiError } from "../api/client";
import { SubHeader } from "./ConsentsScreen";
import {
  IconChevron,
  IconShield,
  IconBlock,
  IconFlag,
  IconReceipt,
  IconWallet,
  IconLogout,
  IconTrash,
  IconSettings,
} from "../components/Icons";
import { YuSun, YuMoon } from "../components/BrandIcons";

type Lang = "ru" | "ba";
type DeleteState = "idle" | "confirm" | "deleting";

export default function SettingsScreen() {
  const { appText, lang, setLang } = useLang();
  const { logout, user } = useAuth();
  const navigate = useNavigate();

  const [theme, setThemeMode] = useTheme();
  const [scale, setScale] = useFontScale();

  // Локальные тумблеры (уведомления/звуки) — из localStorage.
  const [notifOn, setNotifOn] = useState(() => getUiPref("notifications"));
  const [soundOn, setSoundOn] = useState(() => getUiPref("sounds"));
  const toggle = (key: UiPrefKey, cur: boolean, set: (v: boolean) => void) => {
    const next = !cur;
    setUiPref(key, next);
    set(next);
  };

  const [del, setDel] = useState<DeleteState>("idle");
  const [delError, setDelError] = useState<string | null>(null);

  const langs: { key: Lang; label: string }[] = [
    { key: "ru", label: "Русский" },
    { key: "ba", label: "Башҡортса" },
  ];

  const themes: { key: ThemeMode; icon: JSX.Element; label: string }[] = [
    { key: "system", icon: <IconSettings size={20} />, label: appText("Как в системе", "Системалағыса") },
    { key: "light", icon: <YuSun size={20} />, label: appText("Светлая", "Яҡты") },
    { key: "dark", icon: <YuMoon size={20} />, label: appText("Тёмная", "Ҡараңғы") },
  ];

  const sizes: { key: FontScale; sample: string; label: string }[] = [
    { key: "normal", sample: "A", label: appText("Обычный", "Ғәҙәти") },
    { key: "large", sample: "A", label: appText("Крупный", "Ҙур") },
    { key: "xlarge", sample: "A", label: appText("Очень крупный", "Бик ҙур") },
  ];

  const links: { key: string; to: string; icon: JSX.Element; title: string; sub: string }[] = [
    {
      key: "my-data",
      to: "/my-data",
      icon: <IconShield size={22} />,
      title: appText("Мои данные", "Минең мәғлүмәттәр"),
      sub: appText("Что хранится и когда удалится", "Нимә һаҡлана һәм ҡасан юйыла"),
    },
    {
      key: "blocklist",
      to: "/blocklist",
      icon: <IconBlock size={22} />,
      title: appText("Чёрный список", "Ҡара исемлек"),
      sub: appText("Кого ты заблокировал", "Кемде блокланың"),
    },
    {
      key: "report",
      to: "/report",
      icon: <IconFlag size={22} />,
      title: appText("Пожаловаться", "Зарланырға"),
      sub: appText("На попутчика или поездку", "Юлдашҡа йәки сәфәргә"),
    },
    {
      key: "consents",
      to: "/consents",
      icon: <IconShield size={22} />,
      title: appText("Согласия", "Ризалыҡтар"),
      sub: appText("Оферта, приватность, гео (152-ФЗ)", "Оферта, ҡупшылыҡ, гео (152-ФЗ)"),
    },
    {
      key: "privacy",
      to: "/privacy",
      icon: <IconShield size={22} />,
      title: appText("Политика конфиденциальности", "Ҡупшылыҡ сәйәсәте"),
      sub: appText("Как мы храним и защищаем данные", "Мәғлүмәтте нисек һаҡлайбыҙ"),
    },
    {
      key: "rules",
      to: "/rules",
      icon: <IconReceipt size={22} />,
      title: appText("Правила сервиса", "Хеҙмәт ҡағиҙәләре"),
      sub: appText("Пользовательское соглашение", "Ҡулланыусы килешеүе"),
    },
    {
      key: "payment-methods",
      to: "/payment-methods",
      icon: <IconWallet size={22} />,
      title: appText("Способы оплаты", "Түләү ысулдары"),
      sub: appText("Выбор для будущих поездок", "Киләһе сәфәрҙәр өсөн һайлау"),
    },
    {
      key: "payment-info",
      to: "/payment-info",
      icon: <IconReceipt size={22} />,
      title: appText("Как оплатить", "Нисек түләргә"),
      sub: appText("Способы оплаты — честно и просто", "Түләү ысулдары — намыҫлы"),
    },
    {
      key: "pricing",
      to: "/pricing",
      icon: <IconWallet size={22} />,
      title: appText("Честно о цене", "Хаҡ тураһында асыҡтан"),
      sub: appText("Попутка, тариф такси и комиссия", "Юлдаш, такси тарифы, комиссия"),
    },
  ];

  async function confirmDelete() {
    setDel("deleting");
    setDelError(null);
    try {
      await deleteAccount();
      // Аккаунт удалён на сервере → чистим локальную сессию и уводим на старт.
      await logout();
      navigate("/splash", { replace: true });
    } catch (e) {
      const notReady = e instanceof ApiError && (e.status === 404 || e.status === 405);
      setDelError(
        notReady
          ? appText(
              "Удаление аккаунта скоро заработает. Пока напиши нам в поддержку — удалим вручную.",
              "Аккаунт бөтөрөү тиҙҙән эшләй. Хәҙергә ярҙамға яҙ — ҡулдан бөтөрәбеҙ."
            )
          : appText(
              "Не получилось удалить аккаунт. Проверь связь и попробуй ещё раз.",
              "Аккаунтты бөтөрөп булманы. Бәйләнеште тикшереп, ҡабат ҡара."
            )
      );
      setDel("confirm");
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Настройки", "Көйләүҙәр")}
        subtitle={appText("Язык, тема, размер текста", "Тел, тема, текст ҙурлығы")}
        onBack={() => navigate(-1)}
      />

      {/* Язык */}
      <div className="fontscale">
        <div className="fontscale__label">{appText("Язык", "Тел")}</div>
        <div className="seg">
          {langs.map((l) => (
            <button
              key={l.key}
              type="button"
              className={"seg__item" + (lang === l.key ? " is-active" : "")}
              onClick={() => setLang(l.key)}
              aria-pressed={lang === l.key}
            >
              {l.label}
            </button>
          ))}
        </div>
      </div>

      {/* Тема */}
      <div className="fontscale">
        <div className="fontscale__label">{appText("Тема оформления", "Биҙәлеш темаһы")}</div>
        <div className="seg">
          {themes.map((th) => (
            <button
              key={th.key}
              type="button"
              className={"seg__item" + (theme === th.key ? " is-active" : "")}
              onClick={() => setThemeMode(th.key)}
              aria-pressed={theme === th.key}
            >
              <span aria-hidden>{th.icon}</span>
              {th.label}
            </button>
          ))}
        </div>
      </div>

      {/* Размер текста — тот же хук, что и в Простом режиме */}
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
              <span className={"fontscale__a fontscale__a--" + s.key} aria-hidden>
                {s.sample}
              </span>
              {s.label}
            </button>
          ))}
        </div>
      </div>

      {/* Уведомления / звуки */}
      <h2 className="section-title">{appText("Уведомления", "Хәбәрҙәр")}</h2>
      {/* Web Push — честная подписка с мягкой деградацией (см. PushToggle) */}
      <PushToggle />
      <div className="list">
        <button
          type="button"
          className="list-row list-row--link"
          onClick={() => toggle("notifications", notifOn, setNotifOn)}
          aria-pressed={notifOn}
        >
          <div className="list-row__main">
            <div className="list-row__title">{appText("Присылать уведомления", "Хәбәрҙәр ебәрергә")}</div>
            <div className="list-row__sub">
              {appText("Отклики, сообщения и новости", "Яуаптар, хәбәрҙәр һәм яңылыҡтар")}
            </div>
          </div>
          <span className={"switch" + (notifOn ? " on" : "")} />
        </button>
        <button
          type="button"
          className="list-row list-row--link"
          onClick={() => toggle("sounds", soundOn, setSoundOn)}
          aria-pressed={soundOn}
        >
          <div className="list-row__main">
            <div className="list-row__title">{appText("Звук и вибрация", "Тауыш һәм вибрация")}</div>
            <div className="list-row__sub">
              {appText("Сигнал при новом событии", "Яңы ваҡиғала сигнал")}
            </div>
          </div>
          <span className={"switch" + (soundOn ? " on" : "")} />
        </button>
      </div>

      {/* Кабинет админа — только для role == "admin" */}
      {user?.role === "admin" && (
        <>
          <h2 className="section-title">{appText("Администрирование", "Идара итеү")}</h2>
          <div className="list">
            <button
              type="button"
              className="list-row list-row--link"
              onClick={() => navigate("/admin")}
            >
              <span className="list-row__icon"><IconShield size={22} /></span>
              <div className="list-row__main">
                <div className="list-row__title">{appText("Кабинет админа", "Админ кабинеты")}</div>
                <div className="list-row__sub">
                  {appText("Модерация и управление", "Тикшереү һәм идара итеү")}
                </div>
              </div>
              <span className="list-row__chev">
                <IconChevron size={20} />
              </span>
            </button>
          </div>
        </>
      )}

      {/* Правовое и безопасность */}
      <h2 className="section-title">{appText("Безопасность и правила", "Именлек һәм ҡағиҙәләр")}</h2>
      <div className="list">
        {links.map((r) => (
          <button
            key={r.key}
            type="button"
            className="list-row list-row--link"
            onClick={() => navigate(r.to)}
          >
            <span className="list-row__icon">{r.icon}</span>
            <div className="list-row__main">
              <div className="list-row__title">{r.title}</div>
              <div className="list-row__sub">{r.sub}</div>
            </div>
            <span className="list-row__chev">
              <IconChevron size={20} />
            </span>
          </button>
        ))}
      </div>

      {/* Выход */}
      <button type="button" className="logout-btn" onClick={() => void logout()}>
        <IconLogout size={20} />
        {appText("Выйти", "Сығырға")}
      </button>

      {/* Опасная зона — удаление аккаунта */}
      <div className="danger-zone">
        <div className="danger-zone__title">{appText("Удалить аккаунт", "Аккаунтты бөтөрөргә")}</div>
        <p className="danger-zone__text">
          {appText(
            "Удаление необратимо: пропадут профиль, поездки, отзывы и все данные. Восстановить не получится.",
            "Бөтөрөү кире ҡайтарылмай: профиль, сәфәрҙәр, фекерҙәр һәм бар мәғлүмәт юғала. Кире ҡайтарып булмай."
          )}
        </p>

        {del === "idle" && (
          <button type="button" className="btn-danger" onClick={() => setDel("confirm")}>
            <IconTrash size={19} />
            {appText("Удалить аккаунт", "Аккаунтты бөтөрөргә")}
          </button>
        )}

        {(del === "confirm" || del === "deleting") && (
          <div className="danger-zone__confirm">
            <div className="danger-zone__confirm-q">
              {appText("Точно удалить? Это навсегда.", "Ысынлап бөтөрәбеҙме? Был мәңгегә.")}
            </div>
            {delError && <div className="danger-zone__err">{delError}</div>}
            <div className="danger-zone__actions">
              <button
                type="button"
                className="btn-soft"
                disabled={del === "deleting"}
                onClick={() => {
                  setDel("idle");
                  setDelError(null);
                }}
              >
                {appText("Оставить", "Ҡалдырырға")}
              </button>
              <button
                type="button"
                className="btn-danger"
                style={{ marginTop: 0 }}
                disabled={del === "deleting"}
                onClick={() => void confirmDelete()}
              >
                {del === "deleting"
                  ? appText("Удаляем…", "Бөтөрәбеҙ…")
                  : appText("Да, удалить навсегда", "Эйе, мәңгегә бөтөр")}
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
