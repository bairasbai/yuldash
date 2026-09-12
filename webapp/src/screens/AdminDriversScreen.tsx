// ================================================================
//  Модерация водителей → /admin/drivers (RequireAdmin).
//  GET /admin/drivers/pending — очередь на проверку (docs_status=pending).
//  Фото прав/авто защищены (GET /secure/docs/{name}, только админ/владелец) —
//  грузим с Bearer-токеном через fetchSecureDoc → blob-URL (<img> не шлёт заголовки).
//  Одобрить/отклонить: POST /admin/drivers/{id}/moderate {approve}.
//  Вид — зеркало AdminDriversContent (SecondaryScreens.kt): вводная строка,
//  карточка с рамкой, AutoCheckRow, подписи документов, тумблер пола,
//  «Одобрить» + контурная «Отклонить».
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchPendingDrivers,
  moderateDriver,
  fetchSecureDoc,
  type PendingDriver,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { AdminIntro, ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";
import { SettingSwitchRow } from "../components/cabinetUi";
import { IconProfile } from "../components/Icons";

type State = "loading" | "error" | "ready";

/**
 * Что распознала автопроверка: номер прав и срок действия. Модератору важен
 * не сам вердикт робота, а то, что робот прочитал, — цифры он сверит с фото
 * глазами за секунду.
 */
function autocheckDetails(dataJson: string): { num: string; expiry: string } {
  try {
    const p = JSON.parse(dataJson || "{}");
    return { num: String(p.license_number ?? ""), expiry: String(p.expiry ?? "") };
  } catch {
    return { num: "", expiry: "" }; // мусор в поле — просто не показываем строку
  }
}

/** AutoCheckRow: вердикт робота 14 Bold на подложке 10 % его цвета + распознанные данные 12. */
function AutoCheckRow({ result, dataJson }: { result: string; dataJson: string }) {
  const { appText } = useLang();
  if (!result) return null;
  const { num, expiry } = autocheckDetails(dataJson);
  const [label, tone] =
    result === "pass"
      ? [appText("🤖 Авто: похоже на действительные права", "🤖 Авто: ысын права кеүек"), "green"]
      : result === "reject"
        ? [appText("🤖 Авто: фото не распознано", "🤖 Авто: фото танылманы"), "red"]
        : result === "error"
          ? [appText("🤖 Авто: проверка недоступна", "🤖 Авто: тикшереп булманы"), "muted"]
          : [appText("🤖 Авто: нужна ручная проверка", "🤖 Авто: ҡул менән тикшерергә"), "muted"];
  const recog = [
    num ? appText("№ прав ", "права № ") + num : "",
    expiry ? appText("срок до ", "ваҡыты ") + expiry : "",
  ]
    .filter(Boolean)
    .join("  ·  ");
  return (
    <div className={`autocheck autocheck--${tone}`}>
      <strong>{label}</strong>
      {recog && <small>{recog}</small>}
    </div>
  );
}

/** Защищённое фото документа: тянем с токеном → objectURL, чистим при размонтировании. */
function SecureImage({ url, alt }: { url: string | null; alt: string }) {
  const { appText } = useLang();
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!url) {
      setFailed(true);
      return;
    }
    let objUrl: string | null = null;
    const ac = new AbortController();
    fetchSecureDoc(url, ac.signal)
      .then((u) => {
        objUrl = u;
        setSrc(u);
      })
      .catch((e) => {
        if (ac.signal.aborted || e?.name === "AbortError") return;
        setFailed(true);
      });
    return () => {
      ac.abort();
      if (objUrl) URL.revokeObjectURL(objUrl);
    };
  }, [url]);

  // Android DocImage: без файла — строка «нет файла» 12 muted, без пустой дыры.
  if (failed) {
    return <small className="adoc-none">{appText("нет файла", "файл юҡ")}</small>;
  }
  if (!src) {
    return <div className="adoc skeleton" aria-hidden />;
  }
  return (
    <a href={src} target="_blank" rel="noreferrer" className="adoc">
      <img src={src} alt={alt} loading="lazy" />
    </a>
  );
}

export default function AdminDriversScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [drivers, setDrivers] = useState<PendingDriver[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);
  const mounted = useRef(true);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchPendingDrivers(signal)
      .then((list) => {
        setDrivers(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    mounted.current = true;
    const ac = new AbortController();
    load(ac.signal);
    return () => {
      mounted.current = false;
      ac.abort();
    };
  }, [load]);

  /**
   * Галочки «пол подтверждаю» по каждому водителю. Ключ — user_id.
   * Ставит модератор, сверив с фото прав, которые уже перед глазами.
   */
  const [genderOk, setGenderOk] = useState<Record<number, boolean>>({});

  async function moderate(userId: number, approve: boolean) {
    if (busyId) return;
    setBusyId(userId);
    setRowError(null);
    try {
      const claimed = drivers.find((d) => d.user_id === userId)?.gender_claimed ?? "";
      // Ничего не заявил — нечего и подтверждать: поле не шлём вовсе.
      await moderateDriver(userId, approve, claimed ? Boolean(genderOk[userId]) : undefined);
      if (mounted.current) setDrivers((prev) => prev.filter((d) => d.user_id !== userId));
    } catch (e) {
      setRowError({
        id: userId,
        msg:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара."), // DRAFT
      });
    } finally {
      if (mounted.current) setBusyId(null);
    }
  }

  return (
    <>
      <SubHeader title={appText("Модерация водителей", "Йөрөтөүселәрҙе модерациялау")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText("Проверь права и фото авто. Одобри или отклони.", "Права һәм авто фотоһын тикшер. Раҫла йәки кире ҡаҡ.")}
        </AdminIntro>

        {state === "loading" && <ListedLoading />}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && drivers.length === 0 && (
          <ListedEmpty
            title={appText("Нет заявок на проверку", "Тикшереүгә заявка юҡ")}
            subtitle={appText("Здесь появятся водители, отправившие документы.", "Бында документ ебәргән йөрөтөүселәр күренер")}
          />
        )}

        {state === "ready" &&
          drivers.map((d) => (
            <article key={d.user_id} className="acard">
              <strong className="acard__title">{d.name}</strong>
              <span className="acard__sub">{(d.car || "—") + " · " + d.phone}</span>
              <AutoCheckRow result={d.autocheck_result} dataJson={d.autocheck_data} />

              <span className="acard__label">{appText("Водительское удостоверение", "Йөрөтөүсе таныҡлығы")}</span>
              <SecureImage url={d.license_url} alt={appText("Водительские права", "Водитель праваһы")} />
              <span className="acard__label">{appText("Фото автомобиля", "Автомобиль фотоһы")}</span>
              <SecureImage url={d.car_photo_url} alt={appText("Фото авто", "Авто фотоһы")} />

              {/* Пол подтверждает человек по фото прав. Пока не подтверждён — бейдж
                  «женщина за рулём» скрыт и женские заказы такси не приходят: иначе
                  фильтр, который женщина включает ради безопасности, ничего не значит. */}
              {d.gender_claimed && (
                <div className="acard__switch">
                  <SettingSwitchRow
                    icon={<IconProfile size={24} />}
                    title={
                      d.gender_claimed === "female"
                        ? appText("Это женщина — подтверждаю", "Был ҡатын-ҡыҙ — раҫлайым")
                        : appText("Это мужчина — подтверждаю", "Был ир-ат — раҫлайым")
                    }
                    subtitle={appText(
                      "Сверь с фото прав. Без подтверждения бейдж и женские заказы не работают.",
                      "Права фотоһы менән сағыштыр. Раҫлауһыҙ билдә лә, ҡатын-ҡыҙ заказы ла эшләмәй."
                    )}
                    checked={genderOk[d.user_id] ?? d.gender_verified ?? false}
                    onChange={(next) => setGenderOk((prev) => ({ ...prev, [d.user_id]: next }))}
                  />
                </div>
              )}

              {rowError?.id === d.user_id && <div className="auth__error">{rowError.msg}</div>}

              <div className="acard__actions">
                <button type="button" className="abtn" onClick={() => moderate(d.user_id, true)} disabled={busyId !== null}>
                  {busyId === d.user_id ? appText("…", "…") : appText("Одобрить", "Раҫлау")}
                </button>
                <button
                  type="button"
                  className="abtn abtn--outline abtn--red"
                  onClick={() => moderate(d.user_id, false)}
                  disabled={busyId !== null}
                >
                  {busyId === d.user_id ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
                </button>
              </div>
            </article>
          ))}
      </div>
    </>
  );
}
