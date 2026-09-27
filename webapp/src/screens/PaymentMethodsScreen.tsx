import { useEffect, useState } from "react";
import { captureOwner } from "../utils/ownedStorage";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { setPaymentMethod, type PaymentMethod } from "../api/instant";
import {
  PAY_METHODS_OPEN,
  PAY_METHODS_SOON,
  rememberedPayMethod,
  rememberPayMethod,
} from "../components/PayMethodPicker";
import { IconCheck, IconReceipt, IconWallet } from "../components/Icons";
import { track } from "../analytics";
import { SubHeader } from "./ConsentsScreen";
import { useActiveTaxiOrder } from "../navSignals";

const SUBTITLES: Record<PaymentMethod, [string, string]> = {
  cash: ["Отдашь деньги водителю в конце поездки", "Сәфәр аҙағында аҡсаны йөрөтөүсегә бирәһең"],
  sbp: ["Переведёшь на телефон водителя", "Йөрөтөүсенең телефонына күсерәһең"],
  negotiate: ["Обсудишь с водителем", "Йөрөтөүсе менән һөйләшерһең"],
};

const SOON_SUBTITLES: Record<string, [string, string]> = {
  card: ["Спишем с карты автоматически", "Картанан автоматик рәүештә алабыҙ"],
  corporate: ["Поездки за счёт компании", "Компания иҫәбенә сәфәрҙәр"],
};

export default function PaymentMethodsScreen() {
  const [personalOwner] = useState(captureOwner);
  const { appText } = useLang();
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const activeOrder = useActiveTaxiOrder();
  const requestedOrderId = Number(search.get("orderId"));
  const queryOrderId = Number.isSafeInteger(requestedOrderId) && requestedOrderId > 0 ? requestedOrderId : 0;
  const orderId = queryOrderId || activeOrder?.id || 0;
  const activeMethod = activeOrder?.payment_method;
  const [current, setCurrent] = useState<PaymentMethod>(() =>
    activeMethod === "cash" || activeMethod === "sbp" || activeMethod === "negotiate"
      ? activeMethod
      : rememberedPayMethod()
  );
  const [busy, setBusy] = useState<PaymentMethod | null>(null);
  const [failed, setFailed] = useState<PaymentMethod | null>(null);
  const [wanted, setWanted] = useState<string | null>(null);

  useEffect(() => {
    if (activeOrder?.id !== orderId) return;
    if (activeMethod === "cash" || activeMethod === "sbp" || activeMethod === "negotiate") {
      setCurrent(activeMethod);
    }
  }, [activeMethod, activeOrder?.id, orderId]);

  async function choose(method: PaymentMethod) {
    if (busy) return;
    setFailed(null);
    if (!orderId) {
      rememberPayMethod(method, personalOwner);
      setCurrent(method);
      return;
    }
    setBusy(method);
    try {
      await setPaymentMethod(orderId, method);
      rememberPayMethod(method, personalOwner);
      setCurrent(method);
    } catch {
      setFailed(method);
    } finally {
      setBusy(null);
    }
  }

  function requestSoon(code: string) {
    setWanted(code);
    track(code === "card" ? "pay_wanted_card" : "pay_wanted_corporate");
  }

  const failedLabel = PAY_METHODS_OPEN.find((method) => method.code === failed);

  return (
    <>
      <SubHeader title={appText("Способы оплаты", "Түләү ысулдары")} onBack={() => navigate(-1)} />

      <div className="pay-methods-hero" aria-hidden>
        <span className="pay-methods-hero__coin" />
        <span className="pay-methods-hero__wallet"><IconWallet size={56} /></span>
        <span className="pay-methods-hero__card"><IconReceipt size={34} /></span>
      </div>

      <section className="pay-methods-section">
        <h2>{appText("Как рассчитаетесь", "Нисек иҫәпләшәһегеҙ")}</h2>
        <div className="pay-methods-card">
          {PAY_METHODS_OPEN.map((method) => {
            const selected = current === method.code;
            const loading = busy === method.code;
            const subtitle = SUBTITLES[method.code];
            return (
              <button
                key={method.code}
                type="button"
                className={"pay-method-row" + (selected ? " is-selected" : "")}
                disabled={busy !== null}
                onClick={() => choose(method.code)}
                aria-pressed={selected}
              >
                <span className="pay-method-row__icon" aria-hidden><IconWallet size={28} /></span>
                <span className="pay-method-row__main">
                  <strong>{appText(method.ru, method.ba)}</strong>
                  <small>{appText(subtitle[0], subtitle[1])}</small>
                </span>
                <span className={"pay-method-row__check" + (selected && !loading ? " is-checked" : "")}>
                  {loading ? <span className="mini-spinner" /> : selected ? <IconCheck size={18} /> : null}
                </span>
              </button>
            );
          })}
        </div>
      </section>

      {failed && failedLabel && (
        <section className="pay-method-failed" role="alert">
          <div>
            <strong>{appText("Водитель об этом не знает", "Йөрөтөүсе быны белмәй")}</strong>
            <p>
              {appText(
                `«${failedLabel.ru}» не дошло до сервера. Он ждёт прежнего расчёта.`,
                `«${failedLabel.ba}» серверға барып етмәне. Ул элекке иҫәпләшеүҙе көтә.`
              )}
            </p>
          </div>
          <button type="button" className="btn-soft" onClick={() => choose(failed)}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </section>
      )}

      {current === "sbp" && (
        <p className="pay-method-note">
          {orderId
            ? appText(
                "Номер водителя — в карточке поездки, кнопка «Позвонить».",
                "Йөрөтөүсенең номеры — сәфәр карточкаһында, «Шылтыратырға» төймәһе."
              )
            : appText(
                "Номер для перевода появится, когда водитель примет заказ.",
                "Күсереү өсөн номер йөрөтөүсе заказды алғас күренәсәк."
              )}
        </p>
      )}

      <section className="pay-methods-section">
        <h2>{appText("Скоро", "Тиҙҙән")}</h2>
        <div className="pay-methods-card">
          {PAY_METHODS_SOON.map((method) => {
            const subtitle = SOON_SUBTITLES[method.code];
            return (
              <button
                key={method.code}
                type="button"
                className="pay-method-row pay-method-row--soon"
                onClick={() => requestSoon(method.code)}
              >
                <span className="pay-method-row__icon" aria-hidden><IconReceipt size={28} /></span>
                <span className="pay-method-row__main">
                  <strong>{appText(method.ru, method.ba)}</strong>
                  <small>{appText(subtitle[0], subtitle[1])}</small>
                </span>
                <span className="badge badge--muted">{appText("скоро", "тиҙҙән")}</span>
              </button>
            );
          })}
        </div>
      </section>

      {wanted && (
        <p className="pay-method-note pay-method-note--success" role="status">
          {appText(
            "Записали. Сообщим, как только заработает — таких просьб мы считаем.",
            "Яҙып ҡуйҙыҡ. Эшләй башлаһа, хәбәр итәбеҙ — бындай һорауҙарҙы иҫәпләйбеҙ."
          )}
        </p>
      )}

      <section className="pay-method-info">
        <h2>{appText("Почему платим напрямую", "Ниңә тура түләйбеҙ")}</h2>
        <p>
          {appText(
            "Юлдаш не берёт деньги за поездку и не удерживает комиссию с этой суммы: пассажир рассчитывается с водителем сам. Приложение лишь записывает, о чём договорились, — чтобы на высадке не спорить.",
            "Юлдаш сәфәр өсөн аҡса алмай һәм был сумманан комиссия тотмай: пассажир йөрөтөүсе менән үҙе иҫәпләшә. Ҡушымта тик нимә тураһында килешеүҙе яҙып ҡуя — төшкәндә бәхәсләшмәҫ өсөн."
          )}
        </p>
      </section>
    </>
  );
}
