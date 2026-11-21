import { useLang } from "../i18n/lang";
import { kopExactLabel } from "../utils/format";
import type { useRideshareCardCheckout } from "../utils/useRideshareCardCheckout";

export default function RideshareCardCheckout({ checkout, insideSummary }: {
  checkout: ReturnType<typeof useRideshareCardCheckout>;
  insideSummary: boolean;
}) {
  const { appText } = useLang();
  const { offer, view } = checkout;
  const visibleError = view.error !== null && (view.error !== "read" || view.pending !== null);
  if (!view.selected && !view.pending && !offer && !checkout.canOpenBank && !visibleError) return null;

  const chooseLabel = offer?.action === "retry"
    ? appText("Повторить оплату картой", "Карта менән түләүҙе ҡабатларға")
    : offer?.action === "resume"
      ? appText("Продолжить оплату", "Түләүҙе дауам итергә")
      : appText("Оплатить картой", "Карта менән түләргә");
  const failure = view.error === "storage"
    ? appText("Не удалось проверить сохранённую оплату.", "Һаҡланған түләүҙе тикшереп булманы.")
    : view.error === "link"
      ? appText("Ссылка банка недоступна. Обнови статус.", "Банк һылтанмаһы асыҡ түгел. Торошто яңырт.")
      : view.error === "confirm"
        ? appText("Условия изменились или подтверждение не отправилось. Обнови статус.",
          "Шарттар үҙгәрҙе йәки раҫлау ебәрелмәне. Торошто яңырт.")
        : appText("Не получилось проверить оплату. Повтори проверку.", "Түләүҙе тикшереп булманы. Тикшереүҙе ҡабатла.");
  return (
    <div className={insideSummary ? undefined : "act-card"} aria-busy={view.busy}>
      {view.selected && (
        <>
          <p className="act-card__text">
            {appText("Сумма к оплате", "Түләү суммаһы")} · {kopExactLabel(view.selected.confirmation.amount_kop)}
          </p>
          <button type="button" className="btn-primary" onClick={checkout.confirm} disabled={view.busy}>
            {appText("Подтвердить оплату", "Түләүҙе раҫларға")}
          </button>
          <button type="button" className="btn-soft" onClick={checkout.dismiss} disabled={view.busy}>
            {appText("Отмена", "Баш тартыу")}
          </button>
        </>
      )}
      {offer && !view.selected && (
        <button type="button" className="btn-primary" onClick={checkout.choose} disabled={view.busy}>{chooseLabel}</button>
      )}
      {view.pending && (
        <>
          <p className="act-card__text" role="status">
            {view.confirmationState === "applied"
              ? appText("Проверяем состояние принятой оплаты.", "Ҡабул ителгән түләүҙең торошон тикшерәбеҙ.")
              : appText("Проверяем, принято ли подтверждение.", "Раҫлауҙың ҡабул ителеүен тикшерәбеҙ.")}
          </p>
          {checkout.canRepeat && (
            <button type="button" className="btn-soft" onClick={checkout.repeat} disabled={view.busy}>
              {appText("Повторить отправку", "Ебәреүҙе ҡабатларға")}
            </button>
          )}
          {view.confirmationState !== "applied" && view.confirmationState !== "closed_no_effect" && (
            <button type="button" className="btn-soft" onClick={checkout.close} disabled={view.busy}>
              {appText("Отменить подтверждение", "Раҫлауҙы кире алырға")}
            </button>
          )}
        </>
      )}
      {checkout.canOpenBank && (
        <button type="button" className="btn-primary" onClick={checkout.openBank} disabled={view.busy}>
          {appText("Перейти в банк", "Банкка күсергә")}
        </button>
      )}
      {visibleError && <p className="act-card__text" role="alert">{failure}</p>}
      {!insideSummary && (view.pending || visibleError) && (
        <button type="button" className="btn-soft" onClick={checkout.refresh} disabled={view.busy}>
          {appText("Проверить оплату", "Түләүҙе тикшерергә")}
        </button>
      )}
    </div>
  );
}
