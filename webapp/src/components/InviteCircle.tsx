// ================================================================
//  «Круг своих» — пригласительные коды доверия (trust.py: /invites,
//  /invites/mine, /invites/redeem). Зеркало Android TrustScreens.kt.
//
//  Это НЕ реферальная программа. Реферальный код зовёт в приложение
//  и даёт бонусы; этот — вводит человека в круг «своих» (L3), то есть
//  говорит остальным: за него ручается тот, кто уже проверен.
//  В вебе была только рефералка, и половина смысла «между своими»
//  просто отсутствовала (сверка с Android, 2026-08-30).
//
//  Кто может звать: проверенный участник (L2+), и запас кодов на
//  человека ограничен — иначе «круг своих» перестанет что-либо
//  значить. Оба правила держит сервер, мы их просто объясняем.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createInvite, fetchMyInvites, redeemInvite, type Invite } from "../api/trust";
import { IconCopy, IconGift, IconShare } from "./Icons";
import { track } from "../analytics";

export default function InviteCircle({ canInvite }: { canInvite: boolean }) {
  const { appText } = useLang();
  const [items, setItems] = useState<Invite[]>([]);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [code, setCode] = useState("");
  const [redeemMsg, setRedeemMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [copied, setCopied] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    fetchMyInvites(signal)
      .then(setItems)
      .catch(() => {
        /* нет ручки / нет сети — блок покажет только поле активации */
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function make() {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const inv = await createInvite();
      setItems((prev) => [inv, ...prev]);
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать код.", "Код яһап булманы.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function activate() {
    const c = code.trim().toUpperCase();
    if (!c || busy) return;
    setBusy(true);
    setRedeemMsg(null);
    try {
      await redeemInvite(c);
      track("trust_redeem_invite");
      setCode("");
      setRedeemMsg({
        ok: true,
        text: appText(
          "Готово — ты в кругу своих. Теперь тебя видно как «своего».",
          "Әҙер — һин үҙебеҙҙекеләр араһында. Хәҙер һине «үҙебеҙҙеке» тип күрәләр."
        ),
      });
    } catch (e) {
      setRedeemMsg({
        ok: false,
        text:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Код не подошёл. Проверь и попробуй ещё раз.", "Код тап килмәне. Тикшереп ҡабатла."),
      });
    } finally {
      setBusy(false);
    }
  }

  /** Поделиться кодом. Текст сразу готовый: половина людей отправляет его в WhatsApp. */
  async function share(c: string) {
    const text = appText(
      `Зову тебя в Юлдаш — попутки между своими. Мой код: ${c}. https://yulbash.ru`,
      `Һине Юлдашҡа саҡырам — үҙебеҙ араһында юлдаштар. Кодым: ${c}. https://yulbash.ru`
    );
    if (navigator.share) {
      try {
        await navigator.share({ title: "Юлдаш", text });
        return;
      } catch {
        /* человек передумал — молча */
      }
    }
    try {
      await navigator.clipboard.writeText(text);
      setCopied(c);
      window.setTimeout(() => setCopied(""), 2000);
    } catch {
      /* буфер недоступен — код и так виден на экране */
    }
  }

  return (
    <>
      <h2 className="section-title">{appText("Круг своих", "Үҙебеҙҙекеләр түңәрәге")}</h2>
      <p className="taxi-note" style={{ marginTop: 0 }}>
        {appText(
          "Это не бонусы, а поручительство: код вводит человека в круг «своих», и за него ручаешься ты.",
          "Был бонус түгел, ә ышаныс: код кешене «үҙебеҙҙекеләр» түңәрәгенә индерә, ә уның өсөн һин яуап бирәһең."
        )}
      </p>

      {/* Мои коды */}
      {items.length > 0 && (
        <div className="list">
          {items.map((inv) => (
            <div key={inv.code} className="list-row">
              <div className="list-row__main">
                <div className="list-row__title">{inv.code}</div>
                <div className="list-row__sub">
                  {inv.uses_left > 0
                    ? appText(`Осталось активаций: ${inv.uses_left}`, `Ҡалған активлаштырыу: ${inv.uses_left}`)
                    : appText("Код израсходован", "Код тотонолған")}
                </div>
              </div>
              <button
                type="button"
                className="icon-btn"
                aria-label={appText("Поделиться кодом", "Код менән бүлешеү")}
                onClick={() => void share(inv.code)}
              >
                {copied === inv.code ? <IconCopy size={18} /> : <IconShare size={18} />}
              </button>
            </div>
          ))}
        </div>
      )}

      {canInvite ? (
        <button type="button" className="btn-soft" style={{ width: "100%" }} disabled={busy} onClick={() => void make()}>
          <IconGift size={18} /> {appText("Создать код приглашения", "Саҡырыу коды яһау")}
        </button>
      ) : (
        <p className="taxi-note">
          {appText(
            "Приглашать своих может проверенный участник. Пройди проверку документов — и сможешь звать.",
            "Үҙеңдекеләрҙе тикшерелгән ҡатнашыусы саҡыра ала. Документтар тикшереүен үт — саҡыра алырһың."
          )}
        </p>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}

      {/* Активировать чужой код */}
      <h2 className="section-title">{appText("Меня позвали", "Мине саҡырҙылар")}</h2>
      <label className="field">
        <input
          className="field__input"
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, 12))}
          placeholder={appText("Код от своего", "Үҙеңдекенән код")}
          autoCapitalize="characters"
          autoComplete="off"
          maxLength={12}
        />
      </label>
      <button
        type="button"
        className="btn-primary"
        style={{ width: "100%" }}
        disabled={busy || !code.trim()}
        onClick={() => void activate()}
      >
        {appText("Активировать", "Активлаштырыу")}
      </button>
      {redeemMsg && (
        <div className={redeemMsg.ok ? "invite-redeem__msg ok" : "notice"} role="status">
          {redeemMsg.text}
        </div>
      )}
    </>
  );
}
