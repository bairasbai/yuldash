import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchReferral, redeemReferral, type ReferralMe } from "../api/referral";
import { LoadingList, ErrorState } from "../components/States";
import { IconCopy, IconGift, IconShare } from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";
import InviteCircle from "../components/InviteCircle";
import { fetchMyTrust } from "../api/trust";

/**
 * Инвайты «позови своего» (реальный backend: GET /referral/me, POST /referral/redeem).
 * Свой код (копировать/поделиться), сколько людей пришло, накоплённые бонусы,
 * и поле «ввести чужой код». 1 бонус = 1 бесплатное поднятие поездки.
 */
export default function InvitesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [data, setData] = useState<ReferralMe | null>(null);
  const [state, setState] = useState<"loading" | "error" | "ready">("loading");

  const [redeem, setRedeem] = useState("");
  const [redeemBusy, setRedeemBusy] = useState(false);
  const [redeemMsg, setRedeemMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchReferral(signal)
      .then((r) => {
        setData(r);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  /** Может ли человек звать своих. Решает сервер (L2+), клиент это только показывает. */
  const [canInvite, setCanInvite] = useState(false);
  useEffect(() => {
    const ac = new AbortController();
    fetchMyTrust(ac.signal)
      .then((t) => setCanInvite(t.can_invite))
      .catch(() => setCanInvite(false));
    return () => ac.abort();
  }, []);

  const shareText = (code: string) =>
    appText(
      `Я в Юлдаше — попутки между своими по Башкортостану. Мой код: ${code}. Введи его в приложении — получим бонусы. https://yulbash.ru`,
      `Мин Юлдашта — Башҡортостан буйлап үҙебеҙ араһында юлдаштар. Кодым: ${code}. Ҡушымтала индер — бонус алырбыҙ. https://yulbash.ru`
    );

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      /* clipboard недоступен — тихо игнорируем */
    }
  }

  async function shareCode(code: string) {
    const text = shareText(code);
    if (navigator.share) {
      try {
        await navigator.share({ title: "Юлдаш", text });
        return;
      } catch {
        /* пользователь отменил — не ошибка */
      }
    }
    copyCode(text);
  }

  async function onRedeem() {
    const code = redeem.trim().toUpperCase();
    if (!code || redeemBusy) return;
    setRedeemBusy(true);
    setRedeemMsg(null);
    try {
      const res = await redeemReferral(code);
      setRedeemMsg({
        ok: true,
        text: appText(`Готово! Бонусов: ${res.credits}.`, `Әҙер! Бонус: ${res.credits}.`),
      });
      setRedeem("");
      load(); // обновим свои цифры
    } catch (e) {
      const detail = e instanceof ApiError ? e.message : "";
      // Бэкенд отдаёт «Код уже введён» / «Код не найден» / «Нужен код».
      let text = appText("Не получилось. Проверь код.", "Булманы. Кодты тикшер.");
      if (/уже введ/i.test(detail)) text = appText("Ты уже вводил код.", "Һин кодты индергәнһең инде.");
      else if (/не найден/i.test(detail)) text = appText("Такой код не найден.", "Бындай код табылманы.");
      setRedeemMsg({ ok: false, text });
    } finally {
      setRedeemBusy(false);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Позови своего", "Үҙеңдекеләрҙе саҡыр")}
        subtitle={appText("Пригласи соседа — бонус обоим", "Күршеңде саҡыр — икәүгә лә бонус")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}
      {state === "ready" && data && (
        <>
          <div className="invite-card">
            <div className="invite-card__gift">
              <IconGift size={26} />
            </div>
            <div className="invite-card__label">
              {appText("Твой код приглашения", "Саҡырыу кодың")}
            </div>
            <div className="invite-card__code">{data.code}</div>

            <div className="invite-card__actions">
              <button type="button" className="btn-soft" onClick={() => copyCode(data.code)}>
                <IconCopy size={18} />
                {copied ? appText("Скопировано", "Күсерелде") : appText("Копировать", "Күсереү")}
              </button>
              <button type="button" className="btn-primary invite-share" onClick={() => shareCode(data.code)}>
                <IconShare size={18} />
                {appText("Поделиться", "Бүлешеү")}
              </button>
            </div>
          </div>

          <div className="invite-stats">
            <div className="invite-stat">
              <b>{data.invited}</b>
              <span>{appText("позвал", "саҡырҙың")}</span>
            </div>
            <div className="invite-stat">
              <b>{data.credits}</b>
              <span>{appText("бонусов", "бонус")}</span>
            </div>
          </div>
          <p className="invite-hint">
            {appText(
              "1 бонус = 1 бесплатное поднятие поездки. Пришёл сосед по коду — бонус получаете оба.",
              "1 бонус = 1 бушлай сәфәр күтәреү. Күрше код буйынса килде — икәүегеҙ ҙә бонус ала."
            )}
          </p>

          {!data.redeemed && (
            <div className="invite-redeem">
              <div className="invite-redeem__title">
                {appText("Есть код друга?", "Дуҫтың коды бармы?")}
              </div>
              <div className="invite-redeem__row">
                <input
                  className="auth__name"
                  type="text"
                  maxLength={12}
                  placeholder={appText("Введи код", "Кодты индер")}
                  value={redeem}
                  onChange={(e) => {
                    setRedeem(e.target.value.toUpperCase().replace(/\s/g, ""));
                    setRedeemMsg(null);
                  }}
                  aria-label={appText("Код друга", "Дуҫ коды")}
                />
                <button
                  type="button"
                  className="btn-primary"
                  onClick={onRedeem}
                  disabled={redeemBusy || redeem.trim().length === 0}
                >
                  {redeemBusy ? appText("…", "…") : appText("Применить", "Ҡулланыу")}
                </button>
              </div>
              {redeemMsg && (
                <div className={"invite-redeem__msg" + (redeemMsg.ok ? " ok" : "")}>
                  {redeemMsg.text}
                </div>
              )}
            </div>
          )}
          {data.redeemed && (
            <div className="invite-redeem__msg ok">
              {appText("Ты уже ввёл код друга. Спасибо!", "Дуҫ кодын индергәнһең инде. Рәхмәт!")}
            </div>
          )}

          {/* Круг своих — отдельная от бонусов вещь: поручительство, а не скидка.
              Право звать даёт сервер (L2+), поэтому спрашиваем его, а не гадаем. */}
          <InviteCircle canInvite={canInvite} />
        </>
      )}
    </>
  );
}
