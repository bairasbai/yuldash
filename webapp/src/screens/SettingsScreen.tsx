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
import { SettingsGroup, SettingsNavRow, SettingSwitchRow } from "../components/cabinetUi";
import {
  IconBell,
  IconChevron,
  IconFilter,
  IconGlobe,
  IconInfo,
  IconMap,
  IconShield,
  IconBlock,
  IconFlag,
  IconReceipt,
  IconTextSize,
  IconWallet,
  IconLogout,
  IconTrash,
  IconSettings,
} from "../components/Icons";
import { YuSun, YuMoon } from "../components/BrandIcons";

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
  const [picker, setPicker] = useState<"theme" | "size" | null>(null); // ThemePickerDialog / FontScaleDialog
  const [logoutAsk, setLogoutAsk] = useState(false);

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

  const themeLabel = themes.find((t) => t.key === theme)?.label ?? "";
  const sizeLabel = sizes.find((x) => x.key === scale)?.label ?? "";
  const version = import.meta.env.VITE_APP_VERSION ?? "web";

  return (
    <div className="cabinet settings">
      {/* Заголовок как в приложении: 34/40 Bold CanonGreen + подпись 16 muted; без верхней панели. */}
      <div className="settings__title">
        <h1>{appText("Настройки", "Көйләүҙәр")}</h1>
        <p>{appText("Настрой приложение под себя", "Ҡушымтаны үҙеңә көйлә")}</p>
      </div>

      <SettingsGroup>
        <SettingSwitchRow
          icon={<IconBell size={24} />}
          title={appText("Уведомления", "Хәбәрҙәр")}
          subtitle={appText("Получать важные обновления и напоминания", "Мөһим иҫкәртеүҙәр алыу")}
          checked={notifOn}
          onChange={() => toggle("notifications", notifOn, setNotifOn)}
        />
        <SettingsNavRow
          icon={<IconGlobe size={24} />}
          title={appText("Язык", "Тел")}
          subtitle={lang === "ba" ? "Башҡортса" : "Русский"}
          onClick={() => setLang(lang === "ba" ? "ru" : "ba")}
        />
        <SettingsNavRow
          icon={<IconMap size={24} />}
          title={appText("Тема", "Тема")}
          subtitle={themeLabel}
          onClick={() => setPicker(picker === "theme" ? null : "theme")}
        />
        {picker === "theme" && (
          <div className="settings-group__body">
            <div className="seg">
              {themes.map((th) => (
                <button
                  key={th.key}
                  type="button"
                  className={"seg__item" + (theme === th.key ? " is-active" : "")}
                  onClick={() => { setThemeMode(th.key); setPicker(null); }}
                  aria-pressed={theme === th.key}
                >
                  <span aria-hidden>{th.icon}</span>
                  {th.label}
                </button>
              ))}
            </div>
          </div>
        )}
        <SettingsNavRow
          icon={<IconTextSize size={24} />}
          title={appText("Размер текста", "Текст ҙурлығы")}
          subtitle={sizeLabel}
          onClick={() => setPicker(picker === "size" ? null : "size")}
        />
        {picker === "size" && (
          <div className="settings-group__body">
            <p className="dl-hint">{appText("Увеличь текст во всём приложении — так удобнее читать.", "Бөтә ҡушымтала текстты ҙурайт — уҡырға уңайлыраҡ.")}</p>
            <div className="seg fontscale__seg">
              {sizes.map((x) => (
                <button
                  key={x.key}
                  type="button"
                  className={"seg__item" + (scale === x.key ? " is-active" : "")}
                  onClick={() => { setScale(x.key); setPicker(null); }}
                  aria-pressed={scale === x.key}
                >
                  <span className={"fontscale__a fontscale__a--" + x.key} aria-hidden>{x.sample}</span>
                  {x.label}
                </button>
              ))}
            </div>
          </div>
        )}
      </SettingsGroup>

      {/* Web Push — честная подписка с мягкой деградацией (см. PushToggle) */}
      <PushToggle />

      <SettingsGroup>
        <SettingsNavRow
          icon={<IconShield size={24} />}
          title={appText("Приватность", "Махсуслыҡ")}
          subtitle={appText("Управление безопасностью и данными", "Хәүефһеҙлек һәм мәғлүмәт")}
          onClick={() => navigate("/my-data")}
        />
        <SettingsNavRow
          icon={<IconReceipt size={24} />}
          title={appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт")}
          subtitle={appText("Оферта, политика, геолокация — 152-ФЗ", "Оферта, сәйәсәт, геолокация — 152-ФЗ")}
          onClick={() => navigate("/consents")}
        />
        <SettingSwitchRow
          icon={<IconBell size={24} />}
          title={appText("Звуки", "Тауыштар")}
          subtitle={appText("Звуковые уведомления и эффекты", "Тауышлы хәбәрҙәр")}
          checked={soundOn}
          onChange={() => toggle("sounds", soundOn, setSoundOn)}
        />
      </SettingsGroup>

      <SettingsGroup>
        <SettingsNavRow
          icon={<IconFilter size={24} />}
          title={appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар")}
          subtitle={appText("Условия поиска поездок", "Сәфәр эҙләү шарттары")}
          onClick={() => navigate("/filters")}
        />
        <SettingsNavRow
          icon={<IconWallet size={24} />}
          title={appText("Оплата поездок", "Сәфәр түләүе")}
          subtitle={appText("Как оплачивать поездки в Юлдаш", "Юлдашта сәфәр өсөн нисек түләргә")}
          onClick={() => navigate("/payment-info")}
        />
      </SettingsGroup>

      {/* Кабинет админа — только для role == "admin" */}
      {user?.role === "admin" && (
        <SettingsGroup>
          <SettingsNavRow
            icon={<IconShield size={24} />}
            title={appText("Кабинет админа", "Админ кабинеты")}
            subtitle={appText("Заявки, отклики, реклама — единый центр", "Заявкалар, яуаптар, реклама — берҙәм үҙәк")}
            onClick={() => navigate("/admin")}
          />
        </SettingsGroup>
      )}

      {/* Есть только в вебе: правила, жалоба, чёрный список, способы и честность оплаты. */}
      <SettingsGroup>
        {links.map((r) => (
          <SettingsNavRow key={r.key} icon={r.icon} title={r.title} subtitle={r.sub} onClick={() => navigate(r.to)} />
        ))}
      </SettingsGroup>

      <SettingsGroup>
        <SettingsNavRow
          icon={<IconInfo size={24} />}
          title={appText("О приложении", "Ҡушымта тураһында")}
          subtitle={appText(`Версия ${version}`, `Нөсхә ${version}`)}
        />
      </SettingsGroup>

      <SettingsGroup>
        <SettingsNavRow
          icon={<IconLogout size={24} />}
          title={appText("Выйти из аккаунта", "Иҫәптән сығыу")}
          subtitle={appText("Завершить сеанс на этом устройстве", "Был ҡоролмала сеансты тамамлау")}
          onClick={() => setLogoutAsk((v) => !v)}
        />
        {logoutAsk && (
          <div className="settings-group__body settings-confirm">
            <strong>{appText("Выйти из аккаунта?", "Иҫәптән сығаһыңмы?")}</strong>
            <span>{appText("Нужно будет снова войти через Telegram.", "Telegram аша яңынан инергә кәрәк буласаҡ.")}</span>
            <div className="settings-confirm__row">
              <button type="button" className="btn-ghost settings-confirm__danger" onClick={() => void logout()}>
                {appText("Выйти", "Сығыу")}
              </button>
              <button type="button" className="btn-ghost settings-confirm__muted" onClick={() => setLogoutAsk(false)}>
                {appText("Отмена", "Баш тартыу")}
              </button>
            </div>
          </div>
        )}
      </SettingsGroup>

      {/* Удалить аккаунт — DangerActionCard: красная рамка, красная плитка с корзиной. */}
      <button type="button" className="danger-card" onClick={() => setDel(del === "idle" ? "confirm" : "idle")}>
        <span className="danger-card__icon" aria-hidden><IconTrash size={24} /></span>
        <span className="danger-card__text">
          <strong>{appText("Удалить аккаунт", "Аккаунтты бөтөрөргә")}</strong>
          <small>
            {appText(
              "Профиль, поездки и чаты удалятся без возврата.",
              "Профиль, сәфәрҙәр һәм чаттар кире ҡайтарылмайынса юйыла."
            )}
          </small>
        </span>
        <span className="settings-row__chev" aria-hidden><IconChevron size={20} /></span>
      </button>
      {del !== "idle" && (
        <div className="settings-confirm settings-confirm--card">
          <strong>{appText("Точно удалить? Это навсегда.", "Ысынлап бөтөрәбеҙме? Был мәңгегә.")}</strong>
          {delError && <div className="auth__error">{delError}</div>}
          <div className="settings-confirm__row">
            <button type="button" className="btn-soft" onClick={() => { setDel("idle"); setDelError(null); }} disabled={del === "deleting"}>
              {appText("Оставить", "Ҡалдырырға")}
            </button>
            <button type="button" className="btn-danger" onClick={() => void confirmDelete()} disabled={del === "deleting"}>
              {del === "deleting" ? appText("Удаляем…", "Бөтөрәбеҙ…") : appText("Да, удалить навсегда", "Эйе, мәңгегә бөтөр")}
            </button>
          </div>
        </div>
      )}

      <p className="settings__copy">Юлдаш © 2026</p>
    </div>
  );
}
