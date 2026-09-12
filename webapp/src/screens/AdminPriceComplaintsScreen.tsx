// ================================================================
//  Жалобы на цену → /admin/price-complaints (RequireAdmin). Есть только в вебе:
//  в приложении список живёт внизу «Пульса такси», откуда и берём PriceComplaintCard.
//  GET /admin/price-complaints?limit= — что людям непонятно или дорого в счёте.
//  Координат и адресов здесь нет: только сумма, причина, слова человека и строки счёта.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchPriceComplaints, type PriceComplaint } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { AdminIntro, ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";
import { PriceComplaintCard } from "./AdminTaxiPulseScreen";
import { IconCheck } from "../components/Icons";

type State = "loading" | "error" | "ready";

export default function AdminPriceComplaintsScreen() {
  const { appText } = useLang();
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
      <div className="alist">
        <AdminIntro>
          {appText(
            "Что людям непонятно или дорого в счёте: сумма, причина и их слова. Координат здесь нет.",
            "Кешеләргә иҫәптә нимә аңлайышһыҙ йәки ҡиммәт: сумма, сәбәп һәм уларҙың һүҙҙәре. Координаттар бында юҡ."
          )}
        </AdminIntro>

        {state === "loading" && <ListedLoading />}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && rows.length === 0 && (
          <ListedEmpty
            icon={<IconCheck size={34} />}
            title={appText("Жалоб нет", "Зарҙар юҡ")}
            subtitle={appText(
              "Никто не спорит с ценой. Это хорошая новость — но проверяй иногда: молчат и те, кто просто ушёл.",
              "Хаҡ менән бер кем дә бәхәсләшмәй. Был яҡшы хәбәр — әммә ҡайһы саҡ ҡара: китеп барғандар ҙа өндәшмәй."
            )}
          />
        )}

        {state === "ready" && rows.map((c) => <PriceComplaintCard key={c.id} c={c} detailed />)}
      </div>
    </>
  );
}
