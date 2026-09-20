// ================================================================
//  «Открыть разбор» — зеркало FileIncidentDialog (FairnessScreens.kt): тип спора
//  из перечня incidentTypesRide, описание, «Открыть разбор». Выбрал тип — список
//  сворачивается в одну строку. Карточка встраивается в экран (чек, отмена).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createIncident } from "../api/incidents";
import { IconCheck, IconFlag } from "./Icons";

/** Типы спора по поездке — перечень incidentTypesRide (FairnessScreens.kt). */
export const INCIDENT_TYPES: { key: string; ru: string; ba: string }[] = [
  { key: "rude", ru: "Нагрубили", ba: "Ҡупал һөйләште" },
  { key: "unsafe", ru: "Опасная езда", ba: "Хәүефле йөрөтөү" },
  { key: "non_payment", ru: "Не заплатили", ba: "Түләмәнеләр" },
  { key: "overcharge", ru: "Взяли больше договорённого", ba: "Килешкәндән артыҡ алдылар" },
  { key: "route_detour", ru: "Повезли не той дорогой", ba: "Икенсе юлдан алып барҙылар" },
  { key: "passenger_no_show", ru: "Пассажир не вышел", ba: "Юлаусы сыҡманы" },
  { key: "driver_no_show", ru: "Водитель не приехал", ba: "Йөрөтөүсе килмәне" },
  { key: "harassment", ru: "Приставания, угрозы", ba: "Бәйләнеү, янау" },
  { key: "rules_violation", ru: "Нарушение правил", ba: "Ҡағиҙәләрҙе боҙоу" },
];

export default function FileIncidentCard({
  respondentId,
  respondentName,
  orderId,
  onCancel,
  onFiled,
  onError,
}: {
  respondentId: number;
  respondentName: string;
  orderId: number;
  onCancel: () => void;
  onFiled: () => void;
  /** Ошибку показывает экран — там же, где остальные сообщения. */
  onError: (text: string) => void;
}) {
  const { appText } = useLang();
  const [type, setType] = useState("");
  const [typesOpen, setTypesOpen] = useState(true);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);

  async function file() {
    if (busy || !type || !text.trim()) return;
    setBusy(true);
    try {
      await createIncident({ respondent_id: respondentId, type, description: text.trim(), order_id: orderId });
      onFiled();
    } catch (e) {
      onError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось открыть разбор. Проверь сеть.", "Ҡарауҙы асып булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  const who = respondentName.trim() || appText("участника поездки", "сәфәрҙә ҡатнашыусыны");
  return (
    <div className="settings-confirm settings-confirm--card settings-confirm--plain">
      <strong>{appText("Открыть разбор", "Ҡарауҙы асыу")}</strong>
      <span>
        {appText(
          `Мы позовём ${who} объясниться и решим по-соседски. Решение объясним вам обоим.`,
          `${who} кешене аңлатырға саҡырабыҙ һәм күршеләрсә хәл итәбеҙ. Ҡарарҙы икегеҙгә лә аңлатабыҙ.`
        )}
      </span>
      <strong className="acard__title">{appText("Что случилось?", "Нимә булды?")}</strong>
      <div className="inc-resolve__choices" role="radiogroup">
        {(typesOpen ? INCIDENT_TYPES : INCIDENT_TYPES.filter((t) => t.key === type)).map((t) => (
          <button
            key={t.key}
            type="button"
            role="radio"
            aria-checked={type === t.key}
            className={"choice-row choice-row--sm" + (type === t.key ? " is-on" : "")}
            onClick={() => {
              if (typesOpen) {
                setType(t.key);
                setTypesOpen(false);
              } else setTypesOpen(true);
            }}
          >
            {type === t.key ? <IconCheck size={18} /> : <IconFlag size={18} />} {appText(t.ru, t.ba)}
          </button>
        ))}
        {!typesOpen && <small className="acard__date">{appText("Нажми, чтобы выбрать другое", "Башҡаһын һайлар өсөн баҫ")}</small>}
      </div>
      <label className="field">
        <span className="field__label">{appText("Как было", "Нисек булды")}</span>
        <textarea className="field__input field__area" rows={3} maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <div className="settings-confirm__row">
        <button type="button" className="btn-ghost settings-confirm__muted" onClick={onCancel} disabled={busy}>
          {appText("Отмена", "Кире алыу")}
        </button>
        <button type="button" className="btn-ghost inc-resolve__save" onClick={() => void file()} disabled={busy || !type || !text.trim()}>
          {busy ? appText("…", "…") : appText("Открыть разбор", "Ҡарауҙы асыу")}
        </button>
      </div>
    </div>
  );
}
