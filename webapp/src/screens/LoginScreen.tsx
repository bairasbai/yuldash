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
import BrandMark from "../components/BrandMark";
import { IconChevron, IconTelegram } from "../components/Icons";
import { track } from "../analytics";

type Step = "choose" | "code";

/**
 * Вход по коду через Telegram-бота (зеркало android/LoginScreen.kt).
 * Поток: /auth/tg/start → открыть t.me/<bot>?start=<id> → бот пришлёт код →
 * ввод кода → /auth/tg/verify → токены. SMS-вход отключён (нет юрлица) — спокойная заглушка.
 */
export default function LoginScreen() {
  const { appText } = useLang();
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

  return (
    <div className="auth">
      <header className="auth__top">
        <button
          type="button"
          className="auth__back"
          onClick={() => (step === "code" ? setStep("choose") : navigate("/map"))}
          aria-label={appText("Назад", "Артҡа")}
        >
          <span style={{ display: "inline-flex", transform: "rotate(180deg)" }}>
            <IconChevron size={22} />
          </span>
        </button>
      </header>

      <img
        className="auth__hero"
        src="/login_salavat_yulaev_hero.webp"
        alt=""
        aria-hidden
      />

      <div className="auth__brand">
        <BrandMark size={72} />
        <div className="auth__word">Юлдаш</div>
        <p className="auth__slogan">
          {appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу")}
        </p>
      </div>

      {step === "choose" ? (
        <div className="auth__card">
          <h1>{appText("Вход в Юлдаш", "Юлдашҡа инеү")}</h1>
          <p className="auth__lead">
            {appText(
              "Входим через Telegram — быстро и без пароля. Бот пришлёт короткий код.",
              "Telegram аша инәбеҙ — тиҙ, парольһеҙ. Бот ҡыҫҡа код ебәрер."
            )}
          </p>

          <button type="button" className="btn-tg" onClick={onStart} disabled={loading}>
            <IconTelegram size={22} />
            {loading
              ? appText("Открываем…", "Асабыҙ…")
              : appText("Войти через Telegram", "Telegram аша инеү")}
          </button>

          {error && <div className="auth__error">{error}</div>}

          <button type="button" className="auth__sms-toggle" onClick={() => setSmsOpen((v) => !v)}>
            {appText("Вход по SMS", "SMS аша инеү")}
          </button>
          {smsOpen &&
            (SMS_LOGIN_ENABLED ? (
              <div className="auth__code">
                {smsStep === "phone" ? (
                  <>
                    <input
                      className="field__input"
                      type="tel"
                      inputMode="tel"
                      autoComplete="tel"
                      value={phone}
                      onChange={(e) => setPhone(e.target.value)}
                      placeholder={appText("+7 999 000-00-00", "+7 999 000-00-00")}
                      aria-label={appText("Номер телефона", "Телефон номеры")}
                    />
                    {smsError && <div className="auth__error">{smsError}</div>}
                    <button
                      type="button"
                      className="btn-primary submit-btn"
                      onClick={smsRequest}
                      disabled={smsBusy || phone.trim().length < 6}
                    >
                      {smsBusy
                        ? appText("Отправляем…", "Ебәрәбеҙ…")
                        : appText("Получить код", "Код алыу")}
                    </button>
                  </>
                ) : (
                  <>
                    {/* one-time-code — айфон сам предложит код прямо над клавиатурой.
                        Без этого человек запоминает цифры, уходит в сообщения
                        и возвращается вписывать их руками. */}
                    <input
                      className="field__input"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      value={smsCode}
                      onChange={(e) => setSmsCode(e.target.value.replace(/\D/g, ""))}
                      placeholder={appText("Код из SMS", "SMS коды")}
                      aria-label={appText("Код из SMS", "SMS коды")}
                    />
                    <input
                      className="field__input"
                      style={{ marginTop: 8 }}
                      autoComplete="name"
                      value={smsName}
                      onChange={(e) => setSmsName(e.target.value)}
                      placeholder={appText("Как тебя зовут", "Исемең")}
                      aria-label={appText("Имя", "Исем")}
                    />
                    {smsError && <div className="auth__error">{smsError}</div>}
                    <button
                      type="button"
                      className="btn-primary submit-btn"
                      onClick={smsVerify}
                      disabled={smsBusy || smsCode.trim().length < 4}
                    >
                      {smsBusy ? appText("Входим…", "Инәбеҙ…") : appText("Войти", "Инеү")}
                    </button>
                    <button
                      type="button"
                      className="link-btn"
                      onClick={() => {
                        setSmsStep("phone");
                        setSmsError(null);
                      }}
                    >
                      {appText("Изменить номер", "Номерҙы үҙгәртеү")}
                    </button>
                  </>
                )}
              </div>
            ) : (
              <div className="auth__note">
                {appText(
                  "Вход по SMS пока недоступен. Мы включим его позже — сейчас входим через Telegram.",
                  "SMS аша инеү әлегә юҡ. Һуңынан ҡабатыр — хәҙергә Telegram аша инәбеҙ."
                )}
              </div>
            ))}

          <p className="auth__legal">
            {appText(
              "Продолжая, ты принимаешь оферту и политику конфиденциальности.",
              "Дауам итеп, оферта һәм ҡупшылыҡ сәйәсәтен ҡабул итәһең."
            )}
          </p>
        </div>
      ) : (
        <div className="auth__card">
          <h1>{appText("Код из Telegram", "Telegram коды")}</h1>
          <p className="auth__lead">
            {appText(
              "Открой чат с ботом, получи код и введи его сюда.",
              "Бот менән чатты ас, кодты ал һәм бында индер."
            )}
          </p>

          <input
            className="auth__code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            placeholder="••••••"
            value={code}
            onChange={(e) => {
              setCode(e.target.value.replace(/\D/g, "").slice(0, 6));
              setError(null);
            }}
            aria-label={appText("Код из Telegram", "Telegram коды")}
          />

          <input
            className="auth__name"
            type="text"
            maxLength={120}
            placeholder={appText("Как тебя зовут? (необязательно)", "Исемең? (мотлаҡ түгел)")}
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label={appText("Имя", "Исем")}
          />

          {error && <div className="auth__error">{error}</div>}

          <button type="button" className="btn-primary auth__submit" onClick={onVerify} disabled={loading}>
            {loading ? appText("Входим…", "Инәбеҙ…") : appText("Войти", "Инеү")}
          </button>

          <button
            type="button"
            className="btn-ghost"
            onClick={() =>
              openTelegram(needPhone ? telegramChatUrl() : telegramStartUrl(requestId))
            }
          >
            {needPhone
              ? appText("Открыть Telegram и поделиться номером", "Telegram'ды асып, номер бүлешергә")
              : appText("Открыть Telegram ещё раз", "Telegram'ды тағы асырға")}
          </button>
        </div>
      )}
    </div>
  );
}
