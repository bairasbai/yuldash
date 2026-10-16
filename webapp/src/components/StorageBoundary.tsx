import { Component, useState, type ReactNode } from "react";
import { activateStorageFence, probeStorage, reportStorageFailure, storageFenced, subscribeStorageFailure } from "../utils/storageHealth";
import { recoverStorage } from "../utils/storageRecovery";
import { resetOutboxMemory } from "../utils/outbox";
import { IconShield } from "./Icons";

function StorageUnavailable({ retry }: { retry: (language?: "ru" | "ba") => Promise<void> }) {
  const [lang, setLang] = useState<"ru" | "ba">("ru");
  const [languageChosen, setLanguageChosen] = useState(false);
  const [busy, setBusy] = useState(false);
  const appText = (ru: string, ba: string) => lang === "ba" ? ba : ru;
  const check = async () => {
    setBusy(true);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([retry(languageChosen ? lang : undefined), new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("Recovery still pending")), 10000); })]);
    } catch { /* The gate and any still-running cleanup lock stay closed. */ }
    finally { clearTimeout(timer); setBusy(false); }
  };
  return <main lang={lang} className="app-shell storage-unavailable" data-storage-unavailable="true">
    <div className="login-lang" role="group" aria-label={appText("Язык", "Тел")}>
      <button type="button" className="login-lang__chip" aria-pressed={lang === "ru"} onClick={() => { setLang("ru"); setLanguageChosen(true); }}>РУС</button>
      <button type="button" className="login-lang__chip" aria-pressed={lang === "ba"} onClick={() => { setLang("ba"); setLanguageChosen(true); }}>БАШ</button>
    </div>
    <div className="state" role="alert">
      <div className="state__icon"><IconShield size={32} /></div>
      <h2>{appText("Не удалось открыть хранилище", "Һаҡлағысты асып булманы")}</h2>
      <p>{appText("Для безопасности личные данные скрыты. Проверь настройки браузера и повтори — затем войди заново.", "Хәүефһеҙлек өсөн шәхси мәғлүмәттәр йәшерелде. Браузер көйләүҙәрен тикшер ҙә ҡабатла — аҙаҡ яңынан ин.")}</p>
      <button type="button" className="btn-primary" disabled={busy} onClick={() => void check()}>{busy ? appText("Проверяем…", "Тикшерәбеҙ…") : appText("Повторить", "Ҡабатлау")}</button>
    </div>
  </main>;
}

export default class StorageBoundary extends Component<{ children: ReactNode }, { blocked: boolean; error: unknown; version: number }> {
  private unsubscribe?: () => void;
  constructor(props: { children: ReactNode }) {
    super(props); activateStorageFence();
    this.state = { blocked: !probeStorage(), error: null, version: 0 };
  }
  static getDerivedStateFromError(error: unknown) { return { error, blocked: true }; }
  componentDidCatch() { reportStorageFailure(); resetOutboxMemory(); }
  private failed = () => { resetOutboxMemory(); this.setState({ blocked: true }); };
  private check = () => { if (!probeStorage(false)) this.failed(); };
  private asyncError = (event: ErrorEvent | PromiseRejectionEvent) => {
    if (storageFenced() || !probeStorage(false)) { this.failed(); event.preventDefault(); }
  };
  componentDidMount() {
    this.unsubscribe = subscribeStorageFailure(this.failed);
    window.addEventListener("error", this.asyncError); window.addEventListener("unhandledrejection", this.asyncError);
    window.addEventListener("focus", this.check); document.addEventListener("visibilitychange", this.check);
    document.addEventListener("pointerdown", this.check, true); document.addEventListener("keydown", this.check, true);
  }
  componentWillUnmount() {
    this.unsubscribe?.(); window.removeEventListener("error", this.asyncError); window.removeEventListener("unhandledrejection", this.asyncError);
    window.removeEventListener("focus", this.check); document.removeEventListener("visibilitychange", this.check);
    document.removeEventListener("pointerdown", this.check, true); document.removeEventListener("keydown", this.check, true);
  }
  private retry = async (language?: "ru" | "ba") => { await recoverStorage(language); this.setState(state => ({ blocked: false, error: null, version: state.version + 1 })); };
  render() {
    // Keep ordinary application failures visible to normal diagnostics, not a false storage warning.
    if (this.state.error && !storageFenced() && probeStorage(false)) throw this.state.error;
    return this.state.blocked || storageFenced() ? <StorageUnavailable retry={this.retry} /> : <div key={this.state.version} style={{ display: "contents" }}>{this.props.children}</div>;
  }
}
