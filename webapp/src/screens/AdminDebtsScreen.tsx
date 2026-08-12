// ================================================================
//  Долги по комиссии → /admin/debts (RequireAdmin).
//  GET /admin/debts + confirm / reject / forgive.
//
//  Водитель заявил «оплатил» — админ подтверждает, что деньги пришли.
//  Третья кнопка, «Списать», появилась не для удобства: без неё
//  водитель оставался должен комиссию за поездку, где пассажир ему
//  не заплатил. Технически можно было «подтвердить» несуществующую
//  оплату, но это враньё в отчётах — здесь долг гасится честно и
//  с причиной.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  confirmDebt,
  fetchAdminDebts,
  forgiveDebt,
  rejectDebt,
  type AdminDebt,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconPhone, IconWallet } from "../components/Icons";
import { formatWhen, rubLabel } from "../utils/format";

type State = "loading" | "error" | "ready";

export default function AdminDebtsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminDebt[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [forgiveFor, setForgiveFor] = useState<number | null>(null);
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminDebts(signal)
      .then((list) => {
        setRows(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setRows([]);
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function act(id: number, fn: () => Promise<unknown>, ok: string) {
    if (busyId) return;
    setBusyId(id);
    setNote("");
    try {
      await fn();
      setForgiveFor(null);
      setReason("");
      setNote(ok);
      load();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть.", "Булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusyId(null);
    }
  }

  const total = rows.reduce((sum, r) => sum + r.amount_kop, 0);

  return (
    <>
      <SubHeader
        title={appText("Долги по комиссии", "Комиссия бурыстары")}
        subtitle={appText("Водители заявили оплату", "Водителдәр түләүҙе белдерҙе")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && rows.length === 0 && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Ожидающих оплат нет", "Көтөлгән түләүҙәр юҡ")}</h2>
          <p>
            {appText(
              "Как только водитель отметит оплату комиссии — она появится здесь.",
              "Водитель комиссия түләүен билдәләү менән — бында күренәсәк."
            )}
          </p>
        </div>
      )}

      {state === "ready" && rows.length > 0 && (
        <>
          <div className="money-total">
            <div className="money-total__label">{appText("Ждёт подтверждения", "Раҫлауҙы көтә")}</div>
            <div className="money-total__value">{rubLabel(total)}</div>
            <div className="money-total__rows">
              <div className="info-row">
                <span className="info-row__k">{appText("Водителей", "Водитель")}</span>
                <span className="info-row__v">{rows.length}</span>
              </div>
            </div>
          </div>

          <div className="admin-cards">
            {rows.map((d) => (
              <div key={d.debt_id} className="admin-card">
                <div className="admin-card__head">
                  <div className="admin-card__title">
                    <IconWallet size={16} /> {d.driver_name || appText("Водитель", "Водитель")}
                  </div>
                  <span className="badge badge--gold">{rubLabel(d.amount_kop)}</span>
                </div>

                <div className="admin-card__sub">
                  {d.declared_at && (
                    <>
                      {appText("Отметил оплату: ", "Түләүҙе билдәләне: ")}
                      {formatWhen(d.declared_at, ru)}
                    </>
                  )}
                  {d.weeks.length > 0 && (
                    <>
                      <br />
                      {appText("Недели: ", "Аҙналар: ")}
                      {d.weeks.join(", ")}
                    </>
                  )}
                </div>

                {d.driver_phone && (
                  <a className="admin-card__phone" href={`tel:${d.driver_phone}`}>
                    <IconPhone size={18} /> {d.driver_phone}
                  </a>
                )}

                {forgiveFor === d.debt_id ? (
                  <>
                    <label className="field" style={{ marginTop: 10 }}>
                      <span className="field__label">
                        {appText("Почему списываем (увидит водитель)", "Ниңә алып ташлайбыҙ (водитель күрәсәк)")}
                      </span>
                      <input
                        className="field__input"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        maxLength={300}
                        placeholder={appText(
                          "«Пассажир не заплатил, поездка сорвалась»",
                          "«Юлаусы түләмәне, сәфәр өҙөлдө»"
                        )}
                      />
                    </label>
                    <div className="act-card__actions" style={{ marginTop: 10 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() =>
                          act(
                            d.debt_id,
                            () => forgiveDebt(d.debt_id, reason.trim()),
                            appText("Долг списан", "Бурыс алып ташланды")
                          )
                        }
                        disabled={busyId === d.debt_id || !reason.trim()}
                      >
                        {appText("Списать долг", "Бурысты алып ташлау")}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => {
                          setForgiveFor(null);
                          setReason("");
                        }}
                      >
                        {appText("Отмена", "Кире алыу")}
                      </button>
                    </div>
                  </>
                ) : (
                  <div className="act-card__actions" style={{ marginTop: 10, flexWrap: "wrap" }}>
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() =>
                        act(
                          d.debt_id,
                          () => confirmDebt(d.debt_id),
                          appText("Оплата подтверждена", "Түләү раҫланды")
                        )
                      }
                      disabled={busyId === d.debt_id}
                    >
                      <IconCheck size={18} /> {appText("Деньги пришли", "Аҡса килде")}
                    </button>
                    <button
                      type="button"
                      className="btn-soft"
                      onClick={() =>
                        act(
                          d.debt_id,
                          () => rejectDebt(d.debt_id),
                          appText("Вернули в «не оплачено»", "«Түләнмәгән»гә ҡайтарылды")
                        )
                      }
                      disabled={busyId === d.debt_id}
                    >
                      {appText("Денег нет", "Аҡса юҡ")}
                    </button>
                    <button
                      type="button"
                      className="btn-soft"
                      onClick={() => {
                        setForgiveFor(d.debt_id);
                        setReason("");
                      }}
                    >
                      {appText("Списать", "Алып ташлау")}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>

          {note && <p className="taxi-note">{note}</p>}

          <p className="receipt__foot">
            {appText(
              "«Списать» гасит долг честно и с причиной — «подтвердить» несуществующую оплату нельзя: это врёт в отчётах.",
              "«Алып ташлау» бурысты сәбәбе менән дөрөҫ яба — булмаған түләүҙе «раҫлау» ярамай: ул отчётта ялған."
            )}
          </p>
        </>
      )}
    </>
  );
}
