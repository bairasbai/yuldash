// ================================================================
//  Жалобы на цену → /admin/price-complaints (RequireAdmin).
//  Зеркало backend instant.py: GET /admin/price-complaints.
//
//  Зачем очередь. Тариф надо менять по фактам, а не по ощущениям.
//  Здесь видно, на какой сумме люди отваливаются и какую строку
//  счёта чаще всего не понимают: одна и та же причина двадцать раз
//  подряд — это не жалоба, это ошибка в нашем экране.
//
//  Жалоба уходила на сервер и из приложения, и (с этой волны) из
//  веба — а прочитать её было негде ни там, ни там.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchPriceComplaints, type PriceComplaint } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCar, IconCheck, IconWallet } from "../components/Icons";
import { formatRelative } from "../utils/format";

type State = "loading" | "error" | "ready";

/** Причины — те же коды, что шлёт клиент. Незнакомый код показываем как есть. */
const REASONS: Record<string, { ru: string; ba: string }> = {
  expensive_for_distance: { ru: "Дорого для расстояния", ba: "Ара өсөн ҡиммәт" },
  was_cheaper: { ru: "Было дешевле минуту назад", ba: "Бер минут элек арзаныраҡ ине" },
  line_unclear: { ru: "Не понимает строку счёта", ba: "Иҫәп юлын аңламай" },
  other: { ru: "Другое", ba: "Башҡа" },
};

/** Строки счёта приходят как объект или как строка JSON — показываем и то и другое. */
function breakdownText(b: PriceComplaint["breakdown"]): string {
  if (!b) return "";
  const obj = typeof b === "string" ? safeParse(b) : b;
  if (!obj) return typeof b === "string" ? b : "";
  return Object.entries(obj)
    .filter(([, v]) => Number(v) > 0)
    .map(([k, v]) => `${k}: ${v}`)
    .join(" · ");
}

function safeParse(s: string): Record<string, unknown> | null {
  try {
    const v = JSON.parse(s);
    return v && typeof v === "object" ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export default function AdminPriceComplaintsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<PriceComplaint[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchPriceComplaints(100, signal)
      .then((r) => {
        setRows(r.items ?? []);
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

  return (
    <>
      <SubHeader title={appText("Жалобы на цену", "Хаҡҡа зарлар")} onBack={() => navigate(-1)} />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && rows.length === 0 && (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Жалоб нет", "Зарҙар юҡ")}</h2>
          <p>
            {appText(
              "Никто не спорит с ценой. Это хорошая новость — но проверяй иногда: молчат и те, кто просто ушёл.",
              "Хаҡ менән бер кем дә бәхәсләшмәй. Был яҡшы хәбәр — әммә ҡайһы саҡ ҡара: китеп барғандар ҙа өндәшмәй."
            )}
          </p>
        </div>
      )}

      {state === "ready" && rows.length > 0 && (
        <div className="list">
          {rows.map((c) => {
            const r = REASONS[c.reason];
            const parts = breakdownText(c.breakdown);
            return (
              <div key={c.id} className="list-row list-row--stack">
                <div className="list-row__main">
                  <div className="repeat-route">
                    {c.kind === "courier" ? <IconWallet size={18} /> : <IconCar size={18} />}
                    <span>
                      {c.kind === "courier"
                        ? appText("Доставка", "Илтеү")
                        : appText("Такси", "Такси")}
                      {" · "}
                      {ru ? `${c.price} ₽` : `${c.price} һум`}
                    </span>
                    {c.handled && (
                      <span className="badge badge--mint">{appText("Разобрано", "Ҡаралған")}</span>
                    )}
                  </div>
                  <div className="list-row__sub">
                    {r ? appText(r.ru, r.ba) : c.reason}
                    {c.order_id ? ` · ${appText("заказ", "заказ")} #${c.order_id}` : ""}
                    {c.created_at ? ` · ${formatRelative(c.created_at, ru)}` : ""}
                  </div>
                  {/* Слова человека — самое ценное в жалобе: по ним видно, что именно непонятно. */}
                  {c.comment && <div className="list-row__sub">«{c.comment}»</div>}
                  {/* Строки счёта, как их видел он. Координат тут нет и быть не должно. */}
                  {parts && <div className="list-row__sub">{parts}</div>}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
