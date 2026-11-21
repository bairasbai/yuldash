import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import {
  confirmDriverBankRecipient, readDriverBankRecipient, readDriverBankRecipientReceipt, revokeDriverBankRecipient,
  startDriverBankRecipient, type DriverBankRecipient, type DriverBankRecipientSetup, type RecipientRequestOwner,
} from "../api/driverBankRecipient";
import { useLang } from "../i18n/lang";
import {
  clearRecipientSetup, currentRecipientSetupOwner, keepRecipientSetup, readRecipientSetup, recipientSetupOwnerActive,
} from "../utils/driverBankRecipientSetup";

type SetupOwner = NonNullable<ReturnType<typeof currentRecipientSetupOwner>>;
type Notice = "load-error" | "action-unknown" | "ready" | "confirmed" | "revoked";

export function DriverBankRecipientCard({ userId, generation }: RecipientRequestOwner) {
  const { appText } = useLang();
  const [recipient, setRecipient] = useState<DriverBankRecipient>();
  const [setup, setSetup] = useState<DriverBankRecipientSetup | null>(null);
  const [notice, setNotice] = useState<Notice>("ready");
  const [revokeVersion, setRevokeVersion] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const request = useRef<AbortController | null>(null);

  const run = useCallback(async (work: (owner: SetupOwner, signal: AbortSignal) => Promise<void>,
    error: Notice = "action-unknown") => {
    if (request.current) return;
    const owner = currentRecipientSetupOwner({ userId, generation });
    if (!owner) {
      setNotice("load-error");
      setBusy(false);
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    try {
      await work(owner, controller.signal);
    } catch {
      if (!controller.signal.aborted && recipientSetupOwnerActive(owner)) setNotice(error);
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted && recipientSetupOwnerActive(owner)) setBusy(false);
    }
  }, [userId, generation]);

  const sync = useCallback(async (owner: SetupOwner, signal: AbortSignal, fallback: Notice = "ready",
    rejectedSetup = false, confirmedVersion: string | null = null, revokedVersion: string | null = null) => {
    if (signal.aborted || !recipientSetupOwnerActive(owner)) return;
    const stored = readRecipientSetup(owner);
    let receiptVersion: string | null = null;
    if (stored) {
      try {
        receiptVersion = (await readDriverBankRecipientReceipt(owner, stored, signal))?.version ?? null;
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error;
        rejectedSetup = true;
      }
    }
    const current = await readDriverBankRecipient(owner, signal);
    if (signal.aborted || !recipientSetupOwnerActive(owner)) return;
    const pending = stored && !rejectedSetup && receiptVersion === null && stored.version === current.version
      && Date.parse(stored.expires_at) > Date.now() ? stored : null;
    if (stored && !pending) clearRecipientSetup(owner, stored);
    setRecipient(current);
    setSetup(pending);
    const confirmed = receiptVersion ?? confirmedVersion;
    if (confirmed !== null && confirmed === current.version) setNotice("confirmed");
    else if (revokedVersion !== null && revokedVersion === current.version && !current.can_revoke) setNotice("revoked");
    else setNotice(fallback);
  }, []);

  const refresh = useCallback(() => { void run(sync, "load-error"); }, [run, sync]);

  useEffect(() => {
    refresh();
    window.addEventListener("focus", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      request.current?.abort();
      request.current = null;
    };
  }, [refresh]);

  function begin() {
    if (!recipient || recipient.state === "disabled") return;
    setNotice("ready");
    void run(async (owner, signal) => {
      const next = await startDriverBankRecipient(owner, recipient.version, signal);
      if (signal.aborted || !recipientSetupOwnerActive(owner)) return;
      if (next.version !== recipient.version) throw new Error("Recipient version changed");
      keepRecipientSetup(owner, next);
      setSetup(next);
    });
  }

  function confirm() {
    if (!setup || recipient?.state === "disabled") return;
    void run(async (owner, signal) => {
      const saved = readRecipientSetup(owner);
      if (saved?.setup_id !== setup.setup_id || Date.parse(saved.expires_at) <= Date.now()) {
        await sync(owner, signal);
        return;
      }
      keepRecipientSetup(owner, saved);
      let version: string;
      try {
        version = (await confirmDriverBankRecipient(owner, saved, signal)).version;
      } catch (error) {
        const rejected = error instanceof ApiError && (error.status === 409 || error.status === 404);
        await sync(owner, signal, "action-unknown", rejected);
        return;
      }
      await sync(owner, signal, "ready", false, version);
    });
  }

  function revoke() {
    if (revokeVersion === null || recipient?.version !== revokeVersion) return;
    const version = revokeVersion;
    setRevokeVersion(null);
    void run(async (owner, signal) => {
      let revoked: DriverBankRecipient;
      try {
        revoked = await revokeDriverBankRecipient(owner, version, signal);
      } catch {
        await sync(owner, signal, "action-unknown");
        return;
      }
      await sync(owner, signal, "ready", false, null, revoked.version);
    });
  }

  if (recipient?.state === "disabled" && !recipient.can_revoke && notice === "ready" && !setup) return null;
  const title = appText("Реквизит для выплат", "Түләүҙәр өсөн реквизит");
  const failed = notice === "load-error" || notice === "action-unknown";
  const enabled = !!recipient && recipient.state !== "disabled";

  return (
    <section className="act-card" aria-label={title}>
      <h3 className="act-card__title">{title}</h3>
      {busy && <p role="status">{appText("Проверяем реквизит…", "Реквизитты тикшерәбеҙ…")}</p>}
      {failed && <>
        <p role="alert">{notice === "load-error"
          ? appText("Не удалось загрузить реквизит.", "Реквизитты йөкләп булманы.")
          : appText("Не получили подтверждение. Проверь статус.", "Раҫлау килмәне. Торошон тикшер.")}</p>
        <button type="button" className="btn-ghost" onClick={refresh} disabled={busy}>
          {appText("Проверить статус", "Торошон тикшерергә")}
        </button>
      </>}
      {recipient?.state === "configured" &&
        <p className="act-card__text">{appText("Реквизит подключён.", "Реквизит тоташҡан.")}</p>}
      {recipient?.state === "requires_reconnect" &&
        <p className="act-card__text">{appText("Подключи реквизит заново.", "Реквизитты яңынан тоташтыр.")}</p>}
      {recipient?.state === "disabled" && (recipient.can_revoke || setup) &&
        <p className="act-card__text">{appText("Подключение на паузе.", "Тоташтырыу туҡтатылған.")}</p>}
      {notice === "confirmed" &&
        <p role="status">{appText("Подключение подтверждено.", "Тоташтырыу раҫланды.")}</p>}
      {notice === "revoked" &&
        <p role="status">{appText("Реквизит отключён.", "Реквизит һүндерелде.")}</p>}
      {enabled && !setup && !revokeVersion &&
        <button type="button" className="btn-primary" disabled={busy || failed} onClick={begin}>
          {recipient.can_revoke
            ? appText("Заменить тестовый реквизит", "Һынау реквизитын алмаштырырға")
            : appText("Подключить тестовый реквизит", "Һынау реквизитын тоташтырырға")}
        </button>}
      {enabled && setup && !revokeVersion && <>
        <p className="act-card__text">{appText(
          "Подтверди использование этого тестового реквизита для выплат попутки.",
          "Попутка түләүҙәре өсөн ошо һынау реквизитын ҡулланыуҙы раҫла.",
        )}</p>
        <button type="button" className="btn-primary" disabled={busy || failed} onClick={confirm}>
          {appText("Подтвердить реквизит", "Реквизитты раҫларға")}
        </button>
      </>}
      {recipient?.can_revoke && recipient.version && !revokeVersion &&
        <button type="button" className="btn-ghost" disabled={busy || failed}
          onClick={() => setRevokeVersion(recipient.version)}>
          {appText("Отключить реквизит", "Реквизитты һүндерергә")}
        </button>}
      {revokeVersion !== null && <>
        <p className="act-card__text">{appText("Отключить текущий реквизит для будущих выплат?",
          "Киләһе түләүҙәр өсөн әлеге реквизитты һүндерергәме?")}</p>
        <button type="button" className="btn-primary" disabled={busy || failed || recipient?.version !== revokeVersion}
          onClick={revoke}>{appText("Подтвердить отключение", "Һүндереүҙе раҫларға")}</button>
        <button type="button" className="btn-ghost" onClick={() => setRevokeVersion(null)}>
          {appText("Назад", "Артҡа")}
        </button>
      </>}
    </section>
  );
}
