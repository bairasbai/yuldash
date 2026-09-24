import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, apiPost, getSessionGeneration } from "../api/client";
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
import { createLoginFlow, filterLoginCode, nextNeedPhone, type LoginError, type LoginFlowDeps } from "../utils/loginFlow";

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
  const [error, setError] = useState<LoginError | null>(null);
  const [smsOpen, setSmsOpen] = useState(false);
  // SMS-форма (заморожена флагом VITE_SMS_LOGIN_ENABLED — как в приложении).
  const [phone, setPhone] = useState("");
  const [smsStep, setSmsStep] = useState<"phone" | "code">("phone");
  const [smsCode, setSmsCode] = useState("");
  const [smsName, setSmsName] = useState("");
  const [smsError, setSmsError] = useState<LoginError | null>(null);

  const live = useRef({ login, navigate, from });
  live.current = { login, navigate, from };
  const flow = useRef<ReturnType<typeof createLoginFlow> | null>(null);
  if (!flow.current) {
    const deps: LoginFlowDeps = {
      getGeneration: getSessionGeneration,
      botAvailable: () => TELEGRAM_BOT.length > 0,
      tgStart, tgVerify, smsRequest: requestSmsCode, smsVerify: verifySmsCode,
      login: (access, refresh, user) => live.current.login(access, refresh, user),
      updateName: (updatedName) => apiPost("/me/update", { name: updatedName }),
      getStatus: (e) => e instanceof ApiError ? e.status : 0,
      telegramStartUrl, telegramChatUrl,
      openTelegram: (url) => window.open(url, "_blank", "noopener,noreferrer"),
      track,
      navigate: () => live.current.navigate(live.current.from, { replace: true }),
      onBusy: setLoading,
      onError: (kind) => {
        setError(kind);
        setSmsError(kind);
        setNeedPhone((current) => nextNeedPhone(current, kind));
      },
      onTgStarted: (id) => { setRequestId(id); setCode(""); setNeedPhone(false); setStep("code"); },
      onSmsStarted: () => { setSmsStep("code"); setSmsCode(""); },
    };
    flow.current = createLoginFlow(deps);
  }
  useEffect(() => {
    flow.current?.revive();
    return () => flow.current?.dispose();
  }, []);
  const onStart = () => flow.current?.start();
  const onVerify = () => flow.current?.verify(requestId, code, name);
  const smsRequest = () => flow.current?.smsRequest(phone);
  const smsVerify = () => flow.current?.smsVerify(phone, smsCode, smsName);
  const errorText = (kind: LoginError | null) => {
    switch (kind) {
      case "botMissing": return appText("Вход через Telegram скоро", "Telegram аша инеү тиҙҙән");
      case "start": return appText("Не получилось связаться с сервером. Проверь интернет и повтори.", "Сервер менән бәйләнеш булманы. Интернетты тикшер ҙә ҡабатла.");
      case "enterTgCode": return appText("Введи код из Telegram", "Telegram кодын индер");
      case "badTgCode": return appText("Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп, ҡабат индер.");
      case "phoneRequired": return appText("Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».", "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.");
      case "notYet": return appText("Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.", "Код Telegram'дан килә — бер-ике секунд көт тә «Инеү» баҫ.");
      case "expired": return appText("Код истёк. Получи новый — открой Telegram ещё раз.", "Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас.");
      case "tooMany": return appText("Слишком много попыток. Получи новый код.", "Бик күп омтылыш. Яңы код ал.");
      case "save": return appText("Не удалось сохранить вход на телефоне. Получи новый код и попробуй ещё раз.", "Телефонда инеүҙе һаҡлап булманы. Яңы код ал да тағы инеп ҡара.");
      case "enterPhone": return appText("Введи номер телефона", "Телефон номерын индер");
      case "sendFail": return appText("Не получилось отправить код. Проверь интернет и повтори.", "Код ебәреп булманы. Интернетты тикшер ҙә ҡабатла.");
      case "smsTooMany": return appText("Слишком часто. Подожди минуту.", "Артыҡ йыш. Бер минут көт.");
      case "enterSmsCode": return appText("Введи код из SMS", "SMS кодын индер");
      case "badSmsCode": return appText("Неверный код", "Код дөрөҫ түгел");
      case "verify": return appText("Не удалось проверить код. Попробуй ещё раз.", "Кодты тикшереп булманы. Тағы бер тапҡыр ҡара.");
      default: return null;
    }
  };

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
            {error && !smsOpen && (
              <div className="login-error" role="alert">
                <div className="login-error__row">
                  <IconWarn size={20} />
                  <span>{errorText(error)}</span>
                </div>
                <small>
                  {appText(
                    "Нет интернета или код ещё не пришёл? Проверь связь и нажми «Повторить».",
                    "Интернет юҡмы, әллә код килеп еткәне юҡмы? Бәйләнеште тикшер ҙә «Ҡабатла» баҫ."
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
                <button type="button" className="btn-soft login-phone" onClick={() => { flow.current?.invalidate(); setSmsOpen((v) => !v); setSmsError(null); }}>
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
                          onChange={(e) => { flow.current?.invalidate(); setPhone(e.target.value); setSmsCode(""); setSmsError(null); }}
                          placeholder={appText("Номер телефона", "Телефон номеры")}
                          aria-label={appText("Номер телефона", "Телефон номеры")}
                        />
                        {smsError && <div className="auth__error">{errorText(smsError)}</div>}
                        <button type="button" className="btn-primary login-primary" onClick={smsRequest} disabled={loading}>
                          {loading ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Получить код", "Код алыу")}
                        </button>
                      </>
                    ) : (
                      <>
                        <input
                          className="login-input"
                          autoComplete="name"
                          maxLength={120}
                          value={smsName}
                          onChange={(e) => setSmsName(e.target.value.slice(0, 120))}
                          placeholder={appText("Твоё имя (необязательно)", "Исемең (мотлаҡ түгел)")}
                          aria-label={appText("Имя", "Исем")}
                        />
                        {/* one-time-code — айфон сам предложит код прямо над клавиатурой.
                            Без maxLength: браузер резал бы вставку «123 456» до очистки (как в Android — сначала цифры, потом 6). */}
                        <input
                          className="login-input login-input--code"
                          inputMode="numeric"
                          autoComplete="one-time-code"
                          value={smsCode}
                          onChange={(e) => { setSmsCode(filterLoginCode(e.target.value)); setSmsError(null); }}
                          placeholder={appText("Код из SMS", "SMS коды")}
                          aria-label={appText("Код из SMS", "SMS коды")}
                        />
                        <button type="button" className="btn-ghost" onClick={() => { flow.current?.invalidate(); setSmsStep("phone"); setSmsCode(""); setSmsError(null); }}>
                          {appText("Изменить номер", "Номерҙы үҙгәртеү")}
                        </button>
                        {smsError && <div className="auth__error">{errorText(smsError)}</div>}
                        <button type="button" className="btn-primary login-primary" onClick={smsVerify} disabled={loading}>
                          {loading ? appText("Входим…", "Инәбеҙ…") : appText("Войти", "Инеү")}
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
              <a href="https://yulbash.ru/terms/">{appText("Условия", "Шарттарҙы")}</a>
              {" · "}
              <a href="https://yulbash.ru/privacy/">{appText("Политику конфиденциальности", "Конфиденциаллек сәйәсәтен")}</a>
            </p>
          </>
        ) : (
          <>
            <p className="login-card__lead">{appText("Заходи через Telegram — быстро и безопасно", "Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡуллан")}</p>
            <p className="login-card__lead">{appText("Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.", "Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер.")}</p>
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
              {/* Без maxLength: вставка «123 456» / «123-456» сначала очищается до цифр, потом режется до 6. */}
              <input
                className="login-input login-input--code"
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder={appText("Код из Telegram", "Telegram коды")}
                value={code}
                onChange={(e) => {
                  setCode(filterLoginCode(e.target.value));
                  setError(null);
                }}
                aria-label={appText("Код из Telegram", "Telegram коды")}
              />
            </label>
            {error && (
              <div className="login-error" role="alert">
                <div className="login-error__row">
                  <IconWarn size={20} />
                  <span>{errorText(error)}</span>
                </div>
                <small>
                  {appText(
                    "Нет интернета или код ещё не пришёл? Проверь связь и нажми «Повторить».",
                    "Интернет юҡмы, әллә код килеп еткәне юҡмы? Бәйләнеште тикшер ҙә «Ҡабатла» баҫ."
                  )}
                </small>
                <button type="button" className="btn-soft" onClick={error === "save" ? onStart : onVerify} disabled={loading}>
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
              onClick={() => flow.current?.openTelegramAgain(needPhone)}
              disabled={loading}
            >
              {needPhone
                ? appText("Открыть Telegram и поделиться номером", "Telegram'ды асып, номер менән бүлешергә")
                : appText("Открыть Telegram ещё раз", "Telegram'ды тағы асырға")}
            </button>
            <button type="button" className="btn-ghost login-link login-link--muted" onClick={() => { flow.current?.invalidate(); setStep("choose"); setCode(""); setError(null); setNeedPhone(false); }}>
              {appText("Назад", "Артҡа")}
            </button>
          </>
        )}
      </section>
    </div>
  );
}
