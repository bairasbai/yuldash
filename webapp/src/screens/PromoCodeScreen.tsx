// ================================================================
//  Промокод (promo.py). RequireAuth.
//  Ввод кода друга/акции → POST /promo/apply → результат/бонус.
//  Показываем уже применённый код (GET /promo/mine). Один код на жизнь.
//  Отказ ввести код ничего не ломает — попутка остаётся бесплатной.
//  Появится на проде после мержа release → мягкая деградация.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { applyPromo, fetchPromoMine, type PromoMine } from "../api/promo";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconGift, IconCheck, IconWarn } from "../components/Icons";
import { track } from "../analytics";

type Status = "loading" | "error" | "soon" | "ready";

export default function PromoCodeScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [mine, setMine] = useState<PromoMine | null>(null);

  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchPromoMine(signal)
      .then((r) => {
        setMine(r);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(
          e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error"
        );
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onApply() {
    const c = code.trim().toUpperCase();
    if (!c || busy) return;
    setBusy(true);
    setMsg(null);
    try {
      const res = await applyPromo(c);
      track("promo_apply");
      setMsg({ ok: true, text: appText(res.message_ru, res.message_ba) });
      setCode("");
      load(); // покажем применённый код
    } catch (e) {
      const detail = e instanceof ApiError ? e.message : "";
      // Бэкенд отдаёт двуязычный detail — показываем как есть; иначе общий текст.
      setMsg({
        ok: false,
        text: detail || appText("Не получилось. Проверь код.", "Булманы. Кодты тикшер."),
      });
    } finally {
      setBusy(false);
    }
  }

  const applied = mine?.promo ?? null;

  return (
    <>
      <SubHeader
        title={appText("Промокод", "Промокод")}
        subtitle={appText("Код друга или акции — и тебе бонус", "Дуҫ йәки акция коды — һиңә бонус")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon"><IconGift size={34} /></div>
          <h2>{appText("Промокоды скоро", "Промокодтар тиҙҙән")}</h2>
          <p>{appText("Раздел включится после ближайшего обновления.", "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар.")}</p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && (
        <>
          {applied ? (
            <div className="promo-applied">
              <div className="promo-applied__gift">
                <IconCheck size={26} />
              </div>
              <div className="promo-applied__label">
                {appText("Промокод активирован", "Промокод әүҙем")}
              </div>
              <div className="promo-applied__code">{applied.code}</div>
              {applied.title && (
                <div className="promo-applied__title">{applied.title}</div>
              )}
              {applied.kind === "boost" && applied.perk_value > 0 && (
                <div className="promo-applied__perk">
                  {appText(
                    `+${applied.perk_value} бесплатных поднятий поездки`,
                    `+${applied.perk_value} бушлай сәфәр күтәреү`
                  )}
                </div>
              )}
              {/* Что теперь делать — ничего. Это и надо сказать: иначе человек
                  ищет, куда ещё нажать, а скидка ждёт следующего заказа сама. */}
              {(mine?.discount_kop ?? 0) > 0 && (
                <div className="promo-applied__discount">
                  {mine?.discount_used_order_id ? (
                    appText(
                      `Скидка ${Math.round((mine.discount_kop ?? 0) / 100)} ₽ уже использована — она пошла на прошлый заказ такси.`,
                      `${Math.round((mine.discount_kop ?? 0) / 100)} ₽ ташлама ҡулланылған — ул үткән такси заказына китте.`
                    )
                  ) : mine?.discount_available ? (
                    <>
                      <b>{appText("Вводить больше ничего не нужно", "Башҡа бер нәмә лә индерергә кәрәкмәй")}</b>
                      <p>
                        {appText(
                          `Скидка ${Math.round((mine.discount_kop ?? 0) / 100)} ₽ сработает сама при следующем заказе такси — ты увидишь её в цене ещё до кнопки «Вызвать».`,
                          `${Math.round((mine.discount_kop ?? 0) / 100)} ₽ ташлама киләһе такси заказында үҙе эшләй — уны «Саҡырыу» төймәһенә тиклем үк хаҡта күрәһең.`
                        )}
                      </p>
                    </>
                  ) : (
                    appText(
                      "Срок скидки вышел или акцию закрыли. Промокод остаётся за тобой.",
                      "Ташлама ваҡыты үтте йәки акция ябылды. Промокод һинеке булып ҡала."
                    )
                  )}
                </div>
              )}

              <p className="invite-hint" style={{ marginTop: 12 }}>
                {appText(
                  "Один промокод на всю жизнь аккаунта — этот уже применён.",
                  "Аккаунтҡа бер промокод — был инде ҡулланылған."
                )}
              </p>
            </div>
          ) : (
            <div className="invite-redeem" style={{ marginTop: 16 }}>
              <div className="invite-card__gift" style={{ margin: "0 auto 12px" }}>
                <IconGift size={26} />
              </div>
              <div className="invite-redeem__title" style={{ textAlign: "center" }}>
                {appText("Введи промокод", "Промокодты индер")}
              </div>
              <div className="invite-redeem__row">
                <input
                  className="auth__name"
                  type="text"
                  maxLength={32}
                  placeholder={appText("Например, DRUG2026", "Мәҫәлән, DRUG2026")}
                  value={code}
                  onChange={(e) => {
                    setCode(e.target.value.toUpperCase().replace(/\s/g, ""));
                    setMsg(null);
                  }}
                  aria-label={appText("Промокод", "Промокод")}
                />
                <button
                  type="button"
                  className="btn-primary"
                  onClick={onApply}
                  disabled={busy || code.trim().length === 0}
                >
                  {busy ? appText("…", "…") : appText("Применить", "Ҡулланыу")}
                </button>
              </div>
              {msg && (
                <div className={"invite-redeem__msg" + (msg.ok ? " ok" : "")}>
                  {msg.text}
                </div>
              )}
              <p className="invite-hint" style={{ marginTop: 14 }}>
                {appText(
                  "Промокод — это подарок, а не плата. Не вводить код — попутки всё равно бесплатные.",
                  "Промокод — бүләк, түләү түгел. Код индермәһәң дә, юлдаштар барыбер бушлай."
                )}
              </p>
            </div>
          )}
        </>
      )}
    </>
  );
}
