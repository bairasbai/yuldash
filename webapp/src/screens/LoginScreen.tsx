import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, apiPost } from "../api/client";
import {
  TELEGRAM_BOT,
  tgStart,
  tgVerify,
  telegramChatUrl,
  telegramStartUrl,
  requestSmsCode,
  verifySmsCode,
  SMS_LOGIN_ENABLED,
} from "../api/auth";
import { IconBlock, IconLock, IconPhone, IconProfile, IconShield, IconTelegram, IconWarn } from "../components/Icons";
import { track } from "../analytics";

type Step = "choose" | "code";

/**
 * Вход по коду через Telegram-бота (зеркало android/LoginScreen.kt).
 * Поток: /auth/tg/start → открыть t.me/<bot>?start=<id> → бот пришлёт код →
 * ввод кода → /auth/tg/verify → токены. SMS-вход отключён (нет юрлица) — спокойная заглушка.
 */
export default function LoginScreen() {
  const { appText, lang, setLang } = useLang();
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/map";

  const [step, setStep] = useState<Step>("choose");
  const [requestId, setRequestId] = useState("");
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [loading, setLoading] = useState(false);
  const [needPhone, setNeedPhone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [smsOpen, setSmsOpen] = useState(false);
  // SMS-форма (заморожена флагом VITE_SMS_LOGIN_ENABLED — как в приложении).
  const [phone, setPhone] = useState("");
  const [smsStep, setSmsStep] = useState<"phone" | "code">("phone");
  const [smsCode, setSmsCode] = useState("");
  const [smsName, setSmsName] = useState("");
  const [smsBusy, setSmsBusy] = useState(false);
  const [smsError, setSmsError] = useState<string | null>(null);

  const botMissing = TELEGRAM_BOT.length === 0;

  /** Шаг 1: просим сервер отправить код в SMS. */
  async function smsRequest() {
    const p = phone.trim();
    if (smsBusy || p.length < 6) return;
    setSmsBusy(true);
    setSmsError(null);
    track("login_start", { method: "sms" });
    try {
      await requestSmsCode(p);
      setSmsStep("code");
      setSmsCode("");
    } catch (e) {
      setSmsError(
        e instanceof ApiError && e.status === 429
          ? appText("Слишком часто. Подожди минуту.", "Артыҡ йыш. Бер минут көт.")
          : e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось отправить код.", "Код ебәреп булманы.")
      );
    } finally {
      setSmsBusy(false);
    }
  }

  /** Шаг 2: проверяем код и входим. */
  async function smsVerify() {
    const p = phone.trim();
    const c = smsCode.trim();
    if (smsBusy || c.length < 4) return;
    setSmsBusy(true);
    setSmsError(null);
    try {
      const res = await verifySmsCode(p, c, smsName.trim());
      login(res.access_token, res.refresh_token, res.user);
      navigate(from, { replace: true });
    } catch (e) {
      setSmsError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
      );
    } finally {
      setSmsBusy(false);
    }
  }

  function openTelegram(url: string) {
    window.open(url, "_blank", "noopener,noreferrer");
  }

  async function onStart() {
    if (loading) return;
    if (botMissing) {
      setError(appText("Вход через Telegram скоро", "Telegram аша инеү тиҙҙән"));
      return;
    }
    setLoading(true);
    setError(null);
    track("login_start", { method: "telegram" });
    try {
      const { request_id } = await tgStart();
      setRequestId(request_id);
      setCode("");
      setNeedPhone(false);
      setStep("code");
      openTelegram(telegramStartUrl(request_id));
    } catch {
      setError(appText("Не удалось начать вход. Повтори.", "Инеүҙе башлап булманы. Ҡабатла."));
    } finally {
      setLoading(false);
    }
  }

  async function onVerify() {
    if (loading) return;
    if (code.trim().length !== 6) {
      setError(appText("Введите код из Telegram", "Telegram кодын индерегеҙ"));
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await tgVerify(requestId, code.trim());
      // Сначала сохраняем сессию (токен в localStorage) — тогда /me/update пойдёт с авторизацией.
      login(res.access_token, res.refresh_token, res.user);
      track("login_success", { method: "telegram" });
      // Имя как в приложении: иначе веб-входы не попадают в общую воронку регистрации.
      track("login", { method: "telegram" });
      // Имя как в приложении: иначе веб-входы не попадают в общую воронку регистрации.
      track("login", { method: "telegram" });
      // Необязательное имя при первом входе — обновим профиль (реальный /me/update).
      const nm = name.trim();
      if (nm) {
        try {
          await apiPost("/me/update", { name: nm });
        } catch {
          /* имя не критично — вошли всё равно */
        }
      }
      navigate(from, { replace: true });
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      if (status === 403) {
        setNeedPhone(true);
        setError(
          appText(
            "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».",
            "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ."
          )
        );
      } else if (status === 409) {
        setNeedPhone(false);
        setError(
          appText(
            "Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.",
            "Код Telegram'дан килә — бер-ике секунд көт тә «Инеү» баҫ."
          )
        );
      } else if (status === 410) {
        setNeedPhone(false);
        setError(appText("Код истёк. Получи новый — открой Telegram ещё раз.", "Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас."));
      } else if (status === 429) {
        setNeedPhone(false);
        setError(appText("Слишком много попыток. Получи новый код.", "Бик күп омтылыш. Яңы код ал."));
      } else {
        setNeedPhone(false);
        setError(appText("Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп, ҡабат индер."));
      }
    } finally {
      setLoading(false);
    }
  }

  const langChip = (code: "ru" | "ba", label: string) => (
    <button
      type="button"
      className={"login-lang__chip" + (lang === code ? " is-active" : "")}
      onClick={() => setLang(code)}
      aria-label={appText(`Сменить язык: ${label}`, `Телде алмаштырыу: ${label}`)}
    >
      {label}
    </button>
  );

  return (
    <div className="login">
      {/* BrandHero: фото Салавата Юлаева под зелёно-тёмным градиентом, логотип-плитка, переключатель
          языка, «Юлдаш» крупно, слоган и три «фичи». Карточка входа наезжает снизу на 120. */}
      <section className="login-hero">
        <img className="login-hero__photo" src="/login_salavat_yulaev_hero.webp" alt="" aria-hidden />
        <span className="login-hero__shade" aria-hidden />
        <span className="login-hero__logo" aria-hidden>
          <img src="/yuldash_logo.webp" alt="" />
        </span>
        <div className="login-lang" role="group" aria-label={appText("Язык", "Тел")}>
          {langChip("ru", "РУС")}
          {langChip("ba", "БАШ")}
        </div>
        <div className="login-hero__brand">
          <h1 className="login-hero__word">Юлдаш</h1>
          <p className="login-hero__slogan">{appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу")}</p>
        </div>
        <div className="login-features">
          <div className="login-feature">
            <span className="login-feature__tile" aria-hidden><IconLock size={24} /></span>
            <span className="login-feature__text">
              <strong>{appText("Вход без пароля", "Парольһеҙ инеү")}</strong>
              <small>{appText("Только код — ни паролей, ни анкет", "Бары код — пароль да, анкета ла юҡ")}</small>
            </span>
          </div>
          <div className="login-feature">
            <span className="login-feature__tile" aria-hidden><IconBlock size={24} /></span>
            <span className="login-feature__text">
              <strong>{appText("Никакого спама", "Спам юҡ")}</strong>
              <small>{appText("Не звоним и не шлём SMS", "Шылтыратмайбыҙ, SMS ебәрмәйбеҙ")}</small>
            </span>
          </div>
          <div className="login-feature">
            <span className="login-feature__tile" aria-hidden><IconShield size={24} /></span>
            <span className="login-feature__text">
              <strong>{appText("Данные под защитой", "Мәғлүмәт һаҡлауҙа")}</strong>
              <small>{appText("Шифруем и не передаём третьим", "Шифрлайбыҙ, өсөнсө яҡҡа бирмәйбеҙ")}</small>
            </span>
          </div>
        </div>
      </section>

      <section className="login-card">
        <h2 className="login-card__title">{appText("Войти в Юлдаш", "Юлдашҡа инеү")}</h2>
        {step === "choose" ? (
          <>
            <p className="login-card__lead">{appText("Заходи через Telegram — быстро и безопасно", "Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡуллан")}</p>

            <button type="button" className="login-tg" onClick={onStart} disabled={loading}>
              <IconTelegram size={28} />
              {appText("Войти через Telegram", "Telegram аша инеү")}
            </button>
            {loading && <p className="login-hint">{appText("Открываем Telegram…", "Telegram'ды асабыҙ…")}</p>}

            {/* Ошибка входа: что случилось + что делать + «Повторить». Вход — единственная дверь,
                и «упало молча» здесь дороже всего. */}
            {error && (
              <div className="login-error" role="alert">
                <div className="login-error__row">
                  <IconWarn size={20} />
                  <span>{error}</span>
                </div>
                <small>
                  {appText(
                    "Проверь интернет. Если код от бота ещё не дошёл — подожди пару секунд.",
                    "Интернетты тикшер. Бот коды әле килмәһә — бер-ике секунд көт."
                  )}
                </small>
                <button type="button" className="btn-soft" onClick={onStart} disabled={loading}>
                  {appText("Повторить", "Ҡабатлау")}
                </button>
              </div>
            )}

            {SMS_LOGIN_ENABLED && (
              <>
                <div className="login-divider" aria-hidden>
                  <span />
                  <em>{appText("или", "йәки")}</em>
                  <span />
                </div>
                <button type="button" className="btn-soft login-phone" onClick={() => setSmsOpen((v) => !v)}>
                  <IconPhone size={24} /> {appText("Войти по номеру телефона", "Телефон номеры аша инеү")}
                </button>
                {smsOpen && (
                  <div className="login-sms">
                    <p className="login-card__lead">
                      {smsStep === "phone"
                        ? appText("Номер будет скрыт до подтверждения брони.", "Телефон номеры бронь раҫланғанға тиклем йәшерелә.")
                        : appText(`Код отправлен на ${phone}`, `Код ${phone} номерыңа ебәрелде`)}
                    </p>
                    {smsStep === "phone" ? (
                      <>
                        <input
                          className="login-input"
                          type="tel"
                          inputMode="tel"
                          autoComplete="tel"
                          value={phone}
                          onChange={(e) => setPhone(e.target.value)}
                          placeholder={appText("Номер телефона", "Телефон номеры")}
                          aria-label={appText("Номер телефона", "Телефон номеры")}
                        />
                        {smsError && <div className="auth__error">{smsError}</div>}
                        <button type="button" className="btn-primary login-primary" onClick={smsRequest} disabled={smsBusy || phone.trim().length < 6}>
                          {smsBusy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Получить код", "Код алыу")}
                        </button>
                      </>
                    ) : (
                      <>
                        <input
                          className="login-input"
                          autoComplete="name"
                          value={smsName}
                          onChange={(e) => setSmsName(e.target.value)}
                          placeholder={appText("Твоё имя (необязательно)", "Исемең (мотлаҡ түгел)")}
                          aria-label={appText("Имя", "Исем")}
                        />
                        {/* one-time-code — айфон сам предложит код прямо над клавиатурой. */}
                        <input
                          className="login-input login-input--code"
                          inputMode="numeric"
                          autoComplete="one-time-code"
                          value={smsCode}
                          onChange={(e) => setSmsCode(e.target.value.replace(/\D/g, ""))}
                          placeholder={appText("Код из SMS", "SMS коды")}
                          aria-label={appText("Код из SMS", "SMS коды")}
                        />
                        <button type="button" className="btn-ghost" onClick={() => { setSmsStep("phone"); setSmsError(null); }}>
                          {appText("Изменить номер", "Номерҙы үҙгәртеү")}
                        </button>
                        {smsError && <div className="auth__error">{smsError}</div>}
                        <button type="button" className="btn-primary login-primary" onClick={smsVerify} disabled={smsBusy || smsCode.trim().length < 4}>
                          {smsBusy ? appText("Входим…", "Инәбеҙ…") : appText("Войти", "Инеү")}
                        </button>
                      </>
                    )}
                  </div>
                )}
              </>
            )}

            {/* LoginConsent: 18+ и ссылки на условия и политику. */}
            <p className="login-consent">
              {appText("Входя, ты подтверждаешь, что тебе есть 18 лет, и принимаешь", "Инеп, һин 18 йәшең тулғанын раҫлайһың һәм ҡабул итәһең:")}
              <br />
              <a href="/consents" onClick={(e) => { e.preventDefault(); navigate("/consents"); }}>{appText("Условия", "Шарттарҙы")}</a>
              {" "}{appText("и", "һәм")}{" "}
              <a href="/privacy" onClick={(e) => { e.preventDefault(); navigate("/privacy"); }}>{appText("Политику", "Сәйәсәтте")}</a>
            </p>
          </>
        ) : (
          <>
            <p className="login-card__lead">
              {appText("Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.", "Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер.")}
            </p>
            {needPhone && (
              <div className="login-need-phone">
                <IconShield size={24} />
                <span>
                  {appText(
                    "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».",
                    "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ."
                  )}
                </span>
              </div>
            )}
            <label className="login-input-wrap">
              <IconProfile size={22} />
              <input
                className="login-input"
                type="text"
                maxLength={120}
                placeholder={appText("Твоё имя (необязательно)", "Исемең (мотлаҡ түгел)")}
                value={name}
                onChange={(e) => setName(e.target.value)}
                aria-label={appText("Имя", "Исем")}
              />
            </label>
            <label className="login-input-wrap">
              <IconLock size={22} />
              <input
                className="login-input login-input--code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                placeholder={appText("Код из Telegram", "Telegram коды")}
                value={code}
                onChange={(e) => {
                  setCode(e.target.value.replace(/\D/g, "").slice(0, 6));
                  setError(null);
                }}
                aria-label={appText("Код из Telegram", "Telegram коды")}
              />
            </label>
            {error && (
              <div className="login-error" role="alert">
                <div className="login-error__row">
                  <IconWarn size={20} />
                  <span>{error}</span>
                </div>
                <small>
                  {appText(
                    "Проверь интернет. Если код от бота ещё не дошёл — подожди пару секунд.",
                    "Интернетты тикшер. Бот коды әле килмәһә — бер-ике секунд көт."
                  )}
                </small>
                <button type="button" className="btn-soft" onClick={onVerify} disabled={loading}>
                  {appText("Повторить", "Ҡабатлау")}
                </button>
              </div>
            )}
            <button type="button" className="btn-primary login-primary" onClick={onVerify} disabled={loading}>
              {appText("Войти", "Инеү")}
            </button>
            {loading && <p className="login-hint">{appText("Проверяем код…", "Кодты тикшерәбеҙ…")}</p>}
            <button
              type="button"
              className="btn-ghost login-link"
              onClick={() => openTelegram(needPhone ? telegramChatUrl() : telegramStartUrl(requestId))}
            >
              {needPhone
                ? appText("Открыть Telegram и поделиться номером", "Telegram'ды асып, номер менән бүлешергә")
                : appText("Открыть Telegram ещё раз", "Telegram'ды тағы асырға")}
            </button>
            <button type="button" className="btn-ghost login-link login-link--muted" onClick={() => setStep("choose")}>
              {appText("Назад", "Артҡа")}
            </button>
          </>
        )}
      </section>
    </div>
  );
}
