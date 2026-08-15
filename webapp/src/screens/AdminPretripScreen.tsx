// ================================================================
//  Журнал готовности к работе → /admin/pretrip (RequireAdmin).
//  GET /admin/taxi/pretrip?day= (580-ФЗ).
//
//  Смысл записи: при разборе ДТП или проверки видно, что водитель
//  заявил в этот день. Координат пассажиров и маршрутов здесь нет —
//  только факт подтверждения, время и заметка самого водителя.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchPretripJournal, type PretripJournalItem } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconPhone, IconShield } from "../components/Icons";
import { formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";

/** Сегодняшний день в формате YYYY-MM-DD (местный, без сдвига в UTC). */
function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export default function AdminPretripScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [day, setDay] = useState(today());
  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<PretripJournalItem[]>([]);

  const load = useCallback((d: string, signal?: AbortSignal) => {
    setState("loading");
    fetchPretripJournal(d, signal)
      .then((r) => {
        setRows(r.items);
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
    load(day, ac.signal);
    return () => ac.abort();
  }, [load, day]);

  return (
    <>
      <SubHeader
        title={appText("Готовность к работе", "Эшкә әҙерлек")}
        subtitle={appText("Кто подтвердил в этот день", "Был көндә кем раҫланы")}
        onBack={() => navigate(-1)}
      />

      <label className="field" style={{ marginTop: 10 }}>
        <span className="field__label">{appText("День", "Көн")}</span>
        <input
          className="field__input"
          type="date"
          value={day}
          onChange={(e) => setDay(e.target.value || today())}
        />
      </label>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load(day)} />}

      {state === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 28 }}>
            <div className="state__icon">
              <IconShield size={34} />
            </div>
            <h2>{appText("Записей за этот день нет", "Был көнгә яҙма юҡ")}</h2>
            <p>
              {appText(
                "Никто ещё не отмечал готовность. Выбери другой день или загляни позже.",
                "Әле бер кем әҙерлеген билдәләмәгән. Башҡа көндө һайла йәки һуңыраҡ кил."
              )}
            </p>
          </div>
        ) : (
          <>
            <div className="money-total">
              <div className="money-total__label">
                {appText("Подтвердили готовность", "Әҙерлеген раҫланы")}
              </div>
              <div className="money-total__value">{rows.length}</div>
            </div>

            <div className="admin-cards">
              {rows.map((r) => (
                <div key={`${r.driver_id}-${r.confirmed_at}`} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title">
                      <IconCheck size={16} /> {r.name}
                    </div>
                    <span className="badge badge--mint">{formatWhen(r.confirmed_at, ru)}</span>
                  </div>
                  {r.note && <div className="admin-card__reason">{r.note}</div>}
                  {r.phone && (
                    <a className="admin-card__phone" href={`tel:${r.phone}`}>
                      <IconPhone size={18} /> {r.phone}
                    </a>
                  )}
                </div>
              ))}
            </div>

            <p className="receipt__foot">
              {appText(
                "Это самодекларация водителя, а не медосмотр. Запись хранится как след на случай разбора.",
                "Был — йөрөтөүсенең үҙ раҫлауы, медосмотр түгел. Яҙма тикшереү осрағына эҙ булып һаҡлана."
              )}
            </p>
          </>
        ))}
    </>
  );
}
