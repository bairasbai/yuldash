import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  fetchOwnerSwitches, LOGIN_SWITCHES, saveOwnerSwitch,
  type LoginSwitch, type OwnerSwitches,
} from "../api/ownerSwitches";
import AlertDialog from "../components/AlertDialog";
import { ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";
import { useLang } from "../i18n/lang";
import { subscribeSessionChanges } from "../utils/sessionCredentials";
import { SubHeader } from "./ConsentsScreen";

type LoadState =
  | { phase: "loading" }
  | { phase: "error"; message?: string }
  | { phase: "ready"; rows: OwnerSwitches };
type Confirmation = { key: LoginSwitch; value: false; affected: number };

export default function AdminSwitchesScreen() {
  const generation = useSyncExternalStore(subscribeSessionChanges, getSessionGeneration);
  return <SwitchesForSession key={generation} generation={generation} />;
}

function SwitchesForSession({ generation }: { generation: string }) {
  const { appText, lang } = useLang();
  const navigate = useNavigate();
  const [state, setState] = useState<LoadState>({ phase: "loading" });
  const [revision, reload] = useState(0);
  const [busy, setBusy] = useState<LoginSwitch | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [saveError, setSaveError] = useState("");
  const activeWrite = useRef<AbortController | null>(null);
  const confirmationHost = useRef<HTMLDivElement>(null);
  const smsControl = useRef<HTMLButtonElement>(null);
  const restoreSmsFocus = useRef(false);

  useEffect(() => {
    const source = new AbortController();
    setState({ phase: "loading" });
    fetchOwnerSwitches(generation, source.signal)
      .then(({ switches }) => {
        if (!source.signal.aborted) setState({ phase: "ready", rows: switches ?? {} });
      })
      .catch((error: unknown) => {
        if (!source.signal.aborted) {
          setState({ phase: "error", message: error instanceof ApiError ? error.message : undefined });
        }
      });
    return () => source.abort();
  }, [generation, revision]);

  useEffect(() => () => activeWrite.current?.abort(), []);

  useEffect(() => {
    if (!confirmation) return;
    const host = confirmationHost.current;
    const options = host?.querySelectorAll<HTMLButtonElement>("button:not([disabled])");
    const first = options?.[0];
    const last = options?.[options.length - 1];
    if (!host || !first || !last) return;
    restoreSmsFocus.current = true;
    first.focus();
    function trapTab(event: KeyboardEvent) {
      if (event.key !== "Tab") return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
    host.addEventListener("keydown", trapTab);
    return () => host.removeEventListener("keydown", trapTab);
  }, [confirmation]);

  useEffect(() => {
    if (restoreSmsFocus.current && !confirmation && busy === null && smsControl.current?.disabled === false) {
      smsControl.current.focus();
      restoreSmsFocus.current = false;
    }
  }, [confirmation, busy, state.phase]);

  async function save(key: LoginSwitch, value: boolean, confirmed = false) {
    if (activeWrite.current) return;
    const source = new AbortController();
    activeWrite.current = source;
    setBusy(key);
    setConfirmation(null);
    setSaveError("");
    try {
      const next = await saveOwnerSwitch(key, value, confirmed, generation, source.signal);
      if (!source.signal.aborted) {
        setState((prev) => prev.phase === "ready" ? { phase: "ready", rows: { ...prev.rows, [key]: next } } : prev);
      }
    } catch (error: unknown) {
      if (source.signal.aborted) return;
      if (key === "login.sms" && !value && !confirmed && error instanceof ApiError &&
          error.status === 409 && error.code === "switch_needs_confirm" && error.affected) {
        setConfirmation({ key, value, affected: error.affected });
      } else {
        setSaveError(error instanceof ApiError ? error.message :
          appText("Не удалось сохранить. Проверяем текущее состояние…", "Һаҡлап булманы. Әлеге торошто тикшерәбеҙ…"));
        reload((prev) => prev + 1);
      }
    } finally {
      activeWrite.current = null;
      if (!source.signal.aborted) setBusy(null);
    }
  }

  const labels: Record<LoginSwitch, string> = {
    "login.sms": "SMS", "login.telegram": "Telegram", "login.vk": "VK ID", "login.whatsapp": "WhatsApp",
  };
  const blockerLabels: Record<string, string> = {
    SMS_PROVIDER: appText("SMS-провайдер не настроен", "SMS-провайдер көйләнмәгән"),
    SMS_RU_API_ID: appText("Не задан ключ SMS.ru", "SMS.ru асҡысы ҡуйылмаған"),
    SMSDAR_ID: appText("Не задан идентификатор SMSDAR", "SMSDAR идентификаторы ҡуйылмаған"),
    SMSDAR_PASSWORD: appText("Не задан пароль SMSDAR", "SMSDAR серһүҙе ҡуйылмаған"),
    TELEGRAM_BOT_TOKEN: appText("Не задан ключ бота Telegram", "Telegram ботының асҡысы ҡуйылмаған"),
    TELEGRAM_WEBHOOK_SECRET: appText("Не настроена проверка сообщений от бота", "Бот хәбәрҙәрен тикшереү көйләнмәгән"),
    sms_atomic_budget_pending: appText("Лимит платных SMS ещё не готов", "Түләүле SMS лимиты әле әҙер түгел"),
    vk_id_integration_pending: appText("Вход через VK ID ещё не подключён", "VK ID аша инеү әле тоташтырылмаған"),
    whatsapp_integration_pending: appText("Вход через WhatsApp ещё не подключён", "WhatsApp аша инеү әле тоташтырылмаған"),
  };
  const onOff = (value: boolean) => value
    ? appText("Включено", "Ҡабыҙылған")
    : appText("Выключено", "Һүндерелгән");
  const dateFormat = new Intl.DateTimeFormat(lang === "ba" ? "ba-RU" : "ru-RU", {
    dateStyle: "medium", timeStyle: "short",
  });
  const formatDate = (value: string) => dateFormat.format(new Date(value));
  const options = state.phase === "ready" ? LOGIN_SWITCHES.filter((key) => state.rows[key]) : [];

  return (
    <>
      <SubHeader title={appText("Выключатели", "Күсергестәр")} onBack={() => navigate("/admin")} />
      <div className="alist">
        <h2 className="acard__title">{appText("Способы входа", "Инеү ысулдары")}</h2>
        {saveError && <p className="auth__error" role="alert">{saveError}</p>}
        {state.phase === "loading" && <div role="status"><ListedLoading /></div>}
        {state.phase === "error" && <ListedError message={state.message} onRetry={() => reload((prev) => prev + 1)} />}
        {state.phase === "ready" && options.length === 0 && (
          <>
            <ListedEmpty title={appText("Настроек пока нет", "Көйләүҙәр әлегә юҡ")}
              subtitle={appText("Сервер не вернул способы входа.", "Сервер инеү ысулдарын күрһәтмәне.")} />
            <button type="button" className="abtn" onClick={() => reload((prev) => prev + 1)}>
              {appText("Обновить", "Яңыртыу")}
            </button>
          </>
        )}
        {state.phase === "ready" && options.map((key) => {
          const row = state.rows[key];
          if (!row) return null;
          const wanted = row.stored ?? row.effective;
          const locked = key === "login.telegram";
          return (
            <section key={key} className="acard">
              <div className="acard__row acard__row--between">
                <div className="acard__grow">
                  <h3 className="acard__title">{labels[key]}</h3>
                  <p id={`${key}-status`} className="acard__sub">
                    {row.blockers.length ? appText("Сейчас недоступно", "Әле эшләмәй") : onOff(row.effective)}
                    {row.stored === null
                      ? ` · ${appText("По умолчанию", "Ғәҙәттә")}`
                      : row.stored !== row.effective
                        ? ` · ${appText("Сохранено", "Һаҡланған")} — ${onOff(row.stored)}` : ""}
                  </p>
                </div>
                <button type="button" className="acity__switch" role="switch" aria-label={labels[key]}
                  ref={key === "login.sms" ? smsControl : undefined}
                  aria-checked={wanted} aria-describedby={`${key}-status`}
                  disabled={busy !== null || confirmation !== null || locked || (!wanted && row.blockers.length > 0)}
                  onClick={() => void save(key, !wanted)}>
                  {busy === key ? <span className="spinner spinner--sm" aria-hidden /> :
                    <span className={`switch${wanted ? " on" : ""}`} aria-hidden />}
                </button>
              </div>
              {locked && <p className="acard__note">
                {appText("Нужен для входа администратора.", "Администратор инеү өсөн кәрәк.")}
              </p>}
              {row.blockers.map((code) => <p key={code} className="acard__note">
                {blockerLabels[code] ?? appText("Подключение ещё не готово.", "Тоташтырыу әле әҙер түгел.")}
              </p>)}
              {row.updated_at && <p className="acard__date">
                <time dateTime={row.updated_at}>{formatDate(row.updated_at)}</time>
                {row.updated_by_name && ` · ${row.updated_by_name}`}
              </p>}
              {row.history.length > 0 && (
                <details>
                  <summary className="abtn">{appText("История изменений", "Үҙгәрештәр тарихы")}</summary>
                  <ul>
                    {row.history.map((change, index) => (
                      <li key={`${change.created_at}-${index}`} className="acard__note">
                        {onOff(change.new_value)} · <time dateTime={change.created_at}>{formatDate(change.created_at)}</time>
                        {change.admin_name && ` · ${change.admin_name}`}
                        {change.note && <p>{change.note}</p>}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </section>
          );
        })}
      </div>
      {confirmation && (
        <div ref={confirmationHost}>
          <AlertDialog
            title={appText("Отключить SMS?", "SMS-ты һүндерергәме?")}
            descriptionId="owner-sms-switch-warning"
            text={appText(
              `Без способа входа останутся аккаунты пользователей — ${confirmation.affected}.`,
              `Инеү ысулы ҡалмаясаҡ ҡулланыусы аккаунттары — ${confirmation.affected}.`,
            )}
            dismiss={{ label: appText("Отмена", "Кире алыу"), onClick: () => setConfirmation(null) }}
            confirm={{ label: appText("Отключить SMS", "SMS-ты һүндереү"), tone: "danger",
              onClick: () => void save(confirmation.key, confirmation.value, true) }}
            onClose={() => setConfirmation(null)}
          />
        </div>
      )}
    </>
  );
}
