import { useEffect, useRef, useState } from "react";
import { ApiError, getSessionGeneration } from "../api/client";
import { confirmRequestTraveler, getRequestTravelerOffer, type RequestTravelerOffer, type RequestTravelerSelection } from "../api/requests";
import { useLang } from "../i18n/lang";
import { priceLabel } from "../utils/format";

export default function RequestTravelerForm({ responseId, generation, onAccepted, onClose }: {
  responseId: number;
  generation: string;
  onAccepted: (bookingId: number) => void;
  onClose: () => void;
}) {
  const { appText, lang } = useLang();
  const [offer, setOffer] = useState<RequestTravelerOffer | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [choice, setChoice] = useState<"self" | "other" | "">("");
  const [name, setName] = useState("");
  const [minor, setMinor] = useState(false);
  const [guardian, setGuardian] = useState("");
  const [phone, setPhone] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const submitting = useRef(false);
  const original = useRef<{ key: string; body: RequestTravelerSelection } | null>(null);
  const accepted = useRef(onAccepted); accepted.current = onAccepted;
  const current = (signal: AbortSignal) => !signal.aborted && generation === getSessionGeneration();
  const message = (failure: unknown) => failure instanceof ApiError && failure.message
    ? failure.message
    : appText("Не получилось. Повтори попытку.", "Булманы. Ҡабатлап ҡара.");

  async function load() {
    controller.current?.abort();
    const ac = new AbortController(); controller.current = ac;
    setLoading(true);
    setOffer(null);
    setError("");
    try {
      const result = await getRequestTravelerOffer(responseId, generation, ac.signal);
      if (!current(ac.signal)) return;
      if (result.state === "accepted" && result.booking_id) {
        accepted.current(result.booking_id);
        return;
      }
      if (!result.version) throw new Error("Missing offer version");
      if (offer?.version !== result.version) {
        original.current = null;
        setUncertain(false);
        setName(""); setMinor(false); setGuardian(""); setPhone("");
      }
      setOffer(result);
      if (offer?.version !== result.version) setChoice(result.for_another ? "other" : "");
    } catch (failure) {
      if (current(ac.signal)) setError(message(failure));
    } finally {
      if (current(ac.signal)) setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    return () => { controller.current?.abort(); original.current = null; };
  }, [responseId, generation]);

  const ready = offer?.version && (offer.last_offer_by !== "passenger" || offer.driver_consented) && choice &&
    (choice === "self" || (/\p{L}/u.test(name) && !/[\p{N}\p{C}]/u.test(name))) &&
    (!minor || (!offer.no_minors && guardian.trim() && phone.trim()));

  async function submit() {
    if (submitting.current || loading || !offer?.version || (!original.current && !ready) || generation !== getSessionGeneration()) return;
    const ac = new AbortController(); controller.current = ac;
    if (!original.current) {
      original.current = {
        key: crypto.randomUUID(),
        body: {
          version: offer.version,
          for_another: choice === "other",
          ...(choice === "other" ? { traveler_name: name.trim().replace(/\s+/gu, " ") } : {}),
          minor_passenger: minor,
          ...(minor ? { minor_guardian_name: guardian.trim(), minor_guardian_phone: phone.trim() } : {}),
        },
      };
    }
    const attempt = original.current;
    submitting.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await confirmRequestTraveler(responseId, attempt.body, attempt.key, generation, ac.signal);
      if (current(ac.signal)) accepted.current(result.booking_id);
    } catch (failure) {
      if (!current(ac.signal)) return;
      const unknown = !(failure instanceof ApiError) || failure.status === 408 || failure.status >= 500;
      if (!unknown) original.current = null;
      setUncertain(unknown);
      setError(message(failure));
    } finally {
      submitting.current = false;
      if (current(ac.signal)) setBusy(false);
    }
  }

  return (
    <section className="list" aria-label={appText("Подтверждение пассажира", "Юлсыны раҫлау")}>
      {loading && <p role="status">{appText("Проверяем условия…", "Шарттарҙы тикшерәбеҙ…")}</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && offer && <>
        <p>{offer.from_city} → {offer.to_city} · {priceLabel(offer.price ?? 0, lang !== "ba")}</p>
        <p>{appText("Мест", "Урындар")} {offer.seats}
          {offer.depart_at && <> · {new Date(offer.depart_at.endsWith("Z") ? offer.depart_at : offer.depart_at + "Z").toLocaleString(lang === "ba" ? "ba-RU" : "ru-RU")}</>}
        </p>
        {offer.last_offer_by === "passenger" && !offer.driver_consented && (
          <p role="status">{appText("Ждём согласия водителя на встречную цену.", "Йөрөтөүсенең ҡаршы хаҡҡа ризалығын көтәбеҙ.")}</p>
        )}
        <fieldset disabled={busy || uncertain}>
          <legend>{appText("Кто едет?", "Кем бара?")}</legend>
          {!offer.for_another && <label><input type="radio" className="checkbox" name="request-traveler" checked={choice === "self"} onChange={() => { setChoice("self"); setName(""); }} /> {appText("Еду я", "Мин барам")}</label>}
          <label><input type="radio" className="checkbox" name="request-traveler" checked={choice === "other"} onChange={() => setChoice("other")} /> {appText("Едет другой человек", "Башҡа кеше бара")}</label>
          {choice === "other" && <label className="field">
            <span>{appText("Имя пассажира", "Юлсының исеме")}</span>
            <input className="input" value={name} maxLength={120} onChange={(event) => setName(event.target.value)} />
          </label>}
          {!offer.no_minors && <label><input type="checkbox" className="checkbox" checked={minor} onChange={(event) => {
            setMinor(event.target.checked);
            if (!event.target.checked) { setGuardian(""); setPhone(""); }
          }} /> {appText("Пассажир младше 18", "Юлсы 18 йәштән кесе")}</label>}
          {minor && !offer.no_minors && <>
            <label className="field"><span>{appText("Имя взрослого", "Оло кешенең исеме")}</span>
              <input className="input" value={guardian} maxLength={120} onChange={(event) => setGuardian(event.target.value)} /></label>
            <label className="field"><span>{appText("Телефон взрослого", "Оло кешенең телефоны")}</span>
              <input className="input" type="tel" value={phone} maxLength={32} onChange={(event) => setPhone(event.target.value)} /></label>
          </>}
        </fieldset>
        <button type="button" className="btn-primary" onClick={() => void submit()} disabled={busy || !ready}>
          {uncertain ? appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау") : appText("Подтвердить пассажира", "Юлсыны раҫлау")}
        </button>
      </>}
      <div className="act-card__actions">
        <button type="button" className="btn-soft" disabled={busy || loading} onClick={() => void load()}>{appText("Проверить отклик", "Яуапты тикшереү")}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onClose}>{appText("Закрыть", "Ябыу")}</button>
      </div>
    </section>
  );
}
