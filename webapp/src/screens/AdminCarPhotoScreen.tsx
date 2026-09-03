// ================================================================
//  Фотоконтроль машин → /admin/carphoto (RequireAdmin).
//  Зеркало backend carphoto.py (580-ФЗ).
//
//  Что это. Раз в две недели водитель показывает, на чём он возит
//  людей: четыре стороны кузова, багажник, салон. Автопроверка ловит
//  очевидное (скриншот, старое фото, дубль), а человек смотрит то,
//  чего машина не увидит: чисто ли, цел ли кузов, есть ли «шашечки».
//
//  Снимки уходили на сервер с 30.08 и ложились в очередь, которую
//  НЕ БЫЛО ГДЕ ОТКРЫТЬ. Водитель снимал машину и ждал ответа, а
//  ответить ему было некому — фича упиралась в отсутствие экрана.
//
//  Отказ здесь обязан быть объяснён словами: «не принято» без причины
//  это тупик — человек не знает, что переснимать. Сервер такой отказ
//  и не примет.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  decideCarPhoto,
  fetchCarPhotoQueue,
  type CarPhotoCheckRow,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCar, IconCheck, IconWarn } from "../components/Icons";
import { formatRelative } from "../utils/format";

type State = "loading" | "error" | "ready";

/** Что автопроверка сказала о кадре. Незнакомый код показываем как есть. */
const VERDICTS: Record<string, { ru: string; ba: string }> = {
  ok: { ru: "принято автоматом", ba: "автомат менән ҡабул ителде" },
  too_small: { ru: "слишком мелкое фото", ba: "фото бик ваҡ" },
  screenshot: { ru: "похоже на скриншот", ba: "скриншотҡа оҡшаған" },
  stale: { ru: "снято давно", ba: "күптән төшөрөлгән" },
  duplicate: { ru: "уже присылали", ba: "элек ебәрелгән" },
};

export default function AdminCarPhotoScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<CarPhotoCheckRow[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [reason, setReason] = useState<Record<number, string>>({});
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchCarPhotoQueue(signal)
      .then((r) => {
        setRows(r);
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

  async function decide(row: CarPhotoCheckRow, ok: boolean) {
    if (busyId) return;
    const why = (reason[row.id] ?? "").trim();
    // Ту же проверку делает сервер. Но лучше сказать до отправки, чем после:
    // человек уже написал решение и не должен получать отказ формы.
    if (!ok && !why) {
      setNote(appText("Напиши, что переснять", "Нимәне яңынан төшөрөргә — яҙ"));
      return;
    }
    setBusyId(row.id);
    setNote("");
    try {
      await decideCarPhoto(row.id, ok, why);
      setRows((prev) => prev.filter((x) => x.id !== row.id));
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить решение.", "Ҡарарҙы һаҡлап булманы.")
      );
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Фотоконтроль машин", "Машина фотоконтроле")}
        subtitle={appText("580-ФЗ · очередь на просмотр", "580-ФЗ · ҡарау сираты")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={2} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && rows.length === 0 && (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Очередь пуста", "Сират буш")}</h2>
          <p>
            {appText(
              "Все фотоконтроли разобраны. Новые появятся здесь, как только водители пришлют снимки.",
              "Бөтә фотоконтролдәр ҡаралған. Йөрөтөүселәр рәсем ебәреү менән яңылары бында күренер."
            )}
          </p>
        </div>
      )}

      {state === "ready" &&
        rows.map((row) => (
          <div key={row.id} className="act-card">
            <div className="act-card__title">
              <IconCar size={18} />{" "}
              {row.mode === "courier"
                ? appText("Курьер", "Курьер")
                : appText("Таксист", "Таксист")}{" "}
              #{row.user_id}
              {row.kind !== "periodic" && (
                <span className="badge badge--gold" style={{ marginLeft: 8 }}>
                  {appText("по жалобе", "зар буйынса")}
                </span>
              )}
            </div>

            <p className="act-card__text">
              {row.submitted_at
                ? appText(
                    `Прислано ${formatRelative(row.submitted_at, true)}`,
                    `Ебәрелде: ${formatRelative(row.submitted_at, false)}`
                  )
                : appText("Ждём снимки", "Рәсемдәрҙе көтәбеҙ")}
              {row.seq > 0 &&
                appText(` · контроль №${row.seq}`, ` · ${row.seq}-се контроль`)}
            </p>

            {/* Что именно смотреть глазами. Зимой чистоту кузова не спрашиваем —
                это было бы требование к погоде, а не к человеку. */}
            <ul className="price-factors">
              {row.check_clean_body && (
                <li className="price-factors__row">
                  <span className="price-factors__dot" aria-hidden />
                  <span className="price-factors__text">
                    <span className="price-factors__title">
                      {appText("Кузов целый и чистый", "Кузов бөтөн һәм таҙа")}
                    </span>
                  </span>
                </li>
              )}
              {row.check_signs && (
                <li className="price-factors__row">
                  <span className="price-factors__dot" aria-hidden />
                  <span className="price-factors__text">
                    <span className="price-factors__title">
                      {appText("Опознавательные знаки: фонарь и «шашечки»", "Танытыу билдәләре: фонарь һәм «шашка»")}
                    </span>
                  </span>
                </li>
              )}
              {row.clean_rules.map((r, i) => (
                <li key={i} className="price-factors__row">
                  <span className="price-factors__dot" aria-hidden />
                  <span className="price-factors__text">
                    <span className="price-factors__title">{ru ? r.ru : r.ba}</span>
                  </span>
                </li>
              ))}
            </ul>

            {/* Сами снимки. Открываются в новой вкладке: смотреть надо крупно. */}
            <div className="photo-grid">
              {row.photos.map((p) => (
                <div key={p.code} className="photo-grid__cell">
                  {p.url ? (
                    <a href={p.url} target="_blank" rel="noreferrer">
                      <img className="photo-grid__img" src={p.url} alt={ru ? p.ru : p.ba} />
                    </a>
                  ) : (
                    <div className="photo-grid__empty">{appText("нет фото", "фото юҡ")}</div>
                  )}
                  <div className="photo-grid__cap">{ru ? p.ru : p.ba}</div>
                  {p.verdict && p.verdict !== "ok" && (
                    <div className="photo-grid__verdict">
                      <IconWarn size={12} />{" "}
                      {VERDICTS[p.verdict]
                        ? appText(VERDICTS[p.verdict].ru, VERDICTS[p.verdict].ba)
                        : p.verdict}
                    </div>
                  )}
                </div>
              ))}
            </div>

            {/* Причина отказа. Обязательна: человеку нужно знать, что переснимать. */}
            <label className="field">
              <span className="field__label">
                {appText("Что переснять (если не принято)", "Нимәне яңынан төшөрөргә (ҡабул ителмәһә)")}
              </span>
              <input
                className="field__input"
                value={reason[row.id] ?? ""}
                onChange={(e) =>
                  setReason((prev) => ({ ...prev, [row.id]: e.target.value.slice(0, 200) }))
                }
                placeholder={appText("«Номер не читается на фото сзади»", "«Арттағы фотола номер уҡылмай»")}
              />
            </label>

            <div className="act-card__actions">
              <button
                type="button"
                className="btn-primary"
                disabled={busyId === row.id}
                onClick={() => void decide(row, true)}
              >
                <IconCheck size={16} /> {appText("Принять", "Ҡабул итеү")}
              </button>
              <button
                type="button"
                className="btn-ghost"
                disabled={busyId === row.id}
                onClick={() => void decide(row, false)}
              >
                {appText("Переснять", "Яңынан төшөрөргә")}
              </button>
            </div>
          </div>
        ))}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
