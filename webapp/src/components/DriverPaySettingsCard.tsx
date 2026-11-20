import { useEffect, useRef, useState } from "react";
import { isAccountSession } from "../api/client";
import {
  DRIVER_PAY_METHODS, getDriverPaySettings, setDriverPaySettings,
  type DriverPayMethod, type DriverPaySettings,
} from "../api/driverPaySettings";
import { useLang } from "../i18n/lang";
import { payMethodLabel } from "../utils/format";

type Status = "loading" | "load-error" | "save-unknown" | "ready" | "review" | "saved";

export function DriverPaySettingsCard({ userId, generation }: { userId: number; generation: string }) {
  const { appText, lang } = useLang();
  const [settings, setSettings] = useState<DriverPaySettings>();
  const [draft, setDraft] = useState<DriverPayMethod[]>([]);
  const [status, setStatus] = useState<Status>("loading");
  const [saving, setSaving] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const pending = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const owner = { userId, generation };
    getDriverPaySettings(owner, controller.signal).then((value) => {
      if (controller.signal.aborted || !isAccountSession(owner)) return;
      setSettings(value);
      setDraft(value.methods);
      setStatus("ready");
    }).catch(() => {
      if (controller.signal.aborted || !isAccountSession(owner)) return;
      setStatus((previous) => previous === "save-unknown" ? previous : "load-error");
    });
    return () => {
      controller.abort();
      pending.current?.abort();
    };
  }, [userId, generation, refresh]);

  async function save() {
    if (!settings || pending.current || (status !== "ready" && status !== "review" && status !== "saved")) return;
    const owner = { userId, generation };
    const controller = new AbortController();
    const active = () => !controller.signal.aborted && isAccountSession(owner);
    const chosen = DRIVER_PAY_METHODS.filter((method) => draft.includes(method));
    pending.current = controller;
    setSaving(true);
    try {
      let value: DriverPaySettings;
      try {
        value = await setDriverPaySettings(owner, chosen, settings.updated_at, controller.signal);
      } catch {
        if (!active()) return;
        value = await getDriverPaySettings(owner, controller.signal);
      }
      if (!active()) return;
      setSettings(value);
      setDraft(value.methods);
      const matches = chosen.length === value.methods.length && chosen.every((method) => value.methods.includes(method));
      setStatus(matches ? "saved" : "review");
    } catch {
      if (!active()) return;
      setSettings(undefined);
      setStatus("save-unknown");
    } finally {
      if (pending.current === controller) pending.current = null;
      if (active()) setSaving(false);
    }
  }

  const error = status === "load-error" || status === "save-unknown";
  const changed = settings && (
    draft.length !== settings.methods.length || draft.some((method) => !settings.methods.includes(method))
  );

  return (
    <section className="act-card" aria-label={appText("Способы оплаты попутки", "Попуткала түләү ысулдары")}>
      <h3 className="act-card__title">{appText("Как ты принимаешь оплату", "Түләүҙе нисек ҡабул итәһең")}</h3>
      {status === "loading" && <p role="status">{appText("Загружаем…", "Йөкләйбеҙ…")}</p>}
      {error && (
        <>
          <p role="alert">{status === "save-unknown"
            ? appText("Сохранение не подтвердилось. Проверь текущие настройки.", "Һаҡлау раҫланманы. Әлеге көйләүҙәрҙе тикшер.")
            : appText("Не удалось загрузить настройки.", "Көйләүҙәрҙе йөкләп булманы.")}</p>
          <button type="button" className="btn-ghost" onClick={() => { setRefresh((value) => value + 1); }}>
            {appText("Проверить настройки", "Көйләүҙәрҙе тикшерергә")}
          </button>
        </>
      )}
      {settings && (
        <>
          <p className="act-card__text">{appText(
            "Пассажир платит тебе напрямую. Если оставить оба пункта пустыми, вы сможете договориться.",
            "Юлсы туранан-тура һиңә түләй. Ике пунктты ла буш ҡалдырһаң, үҙегеҙ килешә алаһығыҙ.",
          )}</p>
          <fieldset disabled={saving} style={{ border: 0, padding: 0, margin: 0 }}>
            {DRIVER_PAY_METHODS.map((method) => (
              <label key={method} className="info-row">
                <input type="checkbox" className="checkbox" checked={draft.includes(method)} onChange={(event) => {
                  const checked = event.currentTarget.checked;
                  setDraft((previous) => checked ? [...previous, method] : previous.filter((item) => item !== method));
                  if (status === "saved") setStatus("ready");
                }} />
                <span>{payMethodLabel(method, lang !== "ba")}</span>
              </label>
            ))}
          </fieldset>
          {status === "review" && (
            <p role="status">{appText("Проверь текущий выбор и сохрани снова.", "Әлеге һайлауҙы тикшер һәм ҡабат һаҡла.")}</p>
          )}
          {status === "saved" && <p role="status">{appText("Способы оплаты сохранены.", "Түләү ысулдары һаҡланды.")}</p>}
          <button type="button" className="btn-primary" disabled={!changed || saving} onClick={() => void save()}>
            {saving ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить", "Һаҡларға")}
          </button>
        </>
      )}
    </section>
  );
}
