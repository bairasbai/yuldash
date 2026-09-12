// ================================================================
//  Долги по комиссии → /admin/debts (RequireAdmin). Есть только в вебе:
//  в приложении этот раздел живёт внутри «Заявок на оплату», откуда и
//  берём карточку DebtCard, чтобы вид был один.
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
import { fetchAdminDebts, type AdminDebt } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { AdminIntro, AdminStatCard, ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";
import { DebtCard } from "./AdminPaymentRequestsScreen";
import { kopExactLabel } from "../utils/format";

type State = "loading" | "error" | "ready";

export default function AdminDebtsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminDebt[]>([]);
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

  const total = rows.reduce((sum, r) => sum + r.amount_kop, 0);

  return (
    <>
      <SubHeader title={appText("Долги по комиссии", "Комиссия бурыстары")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Водитель перевёл комиссию по СБП и нажал «Я оплатил». Сверь по имени и сумме — подтверди, и такси у него разблокируется.",
            "Йөрөтөүсе комиссияны СБП аша күсереп «Мин түләнем» баҫҡан. Исем һәм сумма буйынса тикшер — раҫла, такси блокан асыла."
          )}
        </AdminIntro>

        {state === "loading" && <ListedLoading />}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && rows.length === 0 && (
          <ListedEmpty
            title={appText("Ожидающих оплат нет", "Көтөлгән түләүҙәр юҡ")}
            subtitle={appText(
              "Как только водитель отметит оплату комиссии — она появится здесь.",
              "Йөрөтөүсе комиссия түләүен билдәләү менән — бында күренәсәк."
            )}
          />
        )}

        {state === "ready" && rows.length > 0 && (
          <div className="astat-row">
            <AdminStatCard label={appText("Ждёт подтверждения", "Раҫлауҙы көтә")} value={kopExactLabel(total)} />
            <AdminStatCard label={appText("Водителей", "Йөрөтөүсе")} value={String(rows.length)} />
          </div>
        )}

        {note && <p className="dl-hint">{note}</p>}

        {state === "ready" &&
          rows.map((d) => (
            <DebtCard
              key={d.debt_id}
              debt={d}
              busy={false}
              onDone={(msg) => {
                setNote(msg);
                load();
              }}
            />
          ))}

        {state === "ready" && rows.length > 0 && (
          <p className="dl-hint">
            {appText(
              "«Списать» гасит долг честно и с причиной — «подтвердить» несуществующую оплату нельзя: это врёт в отчётах.",
              "«Һүндереү» бурысты сәбәбе менән дөрөҫ яба — булмаған түләүҙе «раҫлау» ярамай: ул отчётта ялған."
            )}
          </p>
        )}
      </div>
    </>
  );
}
