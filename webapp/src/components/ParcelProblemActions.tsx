// ================================================================
//  «Доставка не сложилась» — что делать курьеру и отправителю.
//  Зеркало backend parcels.py: attempt-failed / return-start /
//  return-done / dispute.
//
//  До этого выбор был бинарный: вручить (кому, если никого нет дома?)
//  или бросить заявку висеть. Теперь есть три честных пути:
//   • «Никого нет дома» — посылка остаётся у курьера, попробует ещё;
//   • «Везу обратно» → «Вернул» — груз возвращается отправителю,
//     комиссию платформа за это не берёт (услуга не оказана);
//   • спор — через настоящую «Справедливость»: вторая сторона
//     объясняется, решение приходит с причиной.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  parcelAttemptFailed,
  parcelDispute,
  parcelReturnDone,
  parcelReturnStart,
  type Parcel,
  type ParcelDisputeType,
} from "../api/parcels";
import { IconArrow, IconFlag, IconWarn } from "./Icons";

type Sheet = "none" | "failed" | "return" | "dispute";

const DISPUTE_TYPES: { key: ParcelDisputeType; ru: string; ba: string }[] = [
  { key: "parcel_damage", ru: "Повредили", ba: "Зыян күрҙе" },
  { key: "parcel_lost", ru: "Потеряли", ba: "Юғалтҡандар" },
  { key: "parcel_delay", ru: "Опоздали", ba: "Һуңлағандар" },
  { key: "recipient_absent", ru: "Получателя не было", ba: "Алыусы булманы" },
  { key: "wrong_contents", ru: "Не то содержимое", ba: "Эсендәгеһе тап килмәй" },
];

export default function ParcelProblemActions({
  parcel,
  role,
  onChanged,
}: {
  parcel: Parcel;
  /** courier — везёт посылку; sender — отправитель. */
  role: "courier" | "sender";
  onChanged: (p?: Parcel) => void;
}) {
  const { appText } = useLang();
  const [sheet, setSheet] = useState<Sheet>("none");
  const [reason, setReason] = useState("");
  const [dtype, setDtype] = useState<ParcelDisputeType>("parcel_damage");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const returning = String(parcel.status) === "returning";
  const closed = ["delivered", "canceled", "returned"].includes(String(parcel.status));

  async function run(action: () => Promise<unknown>, okText?: string) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const res = await action();
      setSheet("none");
      setReason("");
      if (okText) setNote(okText);
      onChanged((res as Parcel)?.id ? (res as Parcel) : undefined);
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {/* Курьер: везёт и что-то пошло не так */}
      {role === "courier" && !closed && sheet === "none" && (
        <div className="act-card__actions" style={{ marginTop: 10, flexWrap: "wrap" }}>
          {!returning && (
            <>
              <button type="button" className="btn-soft" onClick={() => setSheet("failed")}>
                {appText("Никого нет дома", "Өйҙә бер кем юҡ")}
              </button>
              <button type="button" className="btn-soft" onClick={() => setSheet("return")}>
                <IconArrow size={16} /> {appText("Везу обратно", "Кире алып барам")}
              </button>
            </>
          )}
          {returning && (
            <button
              type="button"
              className="btn-primary"
              onClick={() =>
                run(
                  () => parcelReturnDone(parcel.id),
                  appText("Возврат закрыт", "Кире ҡайтарыу ябылды")
                )
              }
              disabled={busy}
            >
              {appText("Вернул отправителю", "Ебәреүсегә ҡайтарҙым")}
            </button>
          )}
        </div>
      )}

      {/* Обе стороны: спор */}
      {!closed || role === "sender" ? (
        sheet === "none" && (
          <button
            type="button"
            className="link-btn"
            style={{ marginTop: 8 }}
            onClick={() => setSheet("dispute")}
          >
            <IconFlag size={14} /> {appText("Открыть спор", "Бәхәс асыу")}
          </button>
        )
      ) : null}

      {/* Никого нет дома — посылка остаётся у курьера */}
      {sheet === "failed" && (
        <div className="act-card act-card--warn">
          <div className="act-card__title">
            <IconWarn size={18} /> {appText("Никого нет дома", "Өйҙә бер кем юҡ")}
          </div>
          <p className="act-card__text">
            {appText(
              "Посылка останется у тебя — попробуешь вручить ещё раз. Отправитель увидит причину.",
              "Аҫылма һиндә ҡала — тағы тапшырып ҡарарһың. Ебәреүсе сәбәпте күрәсәк."
            )}
          </p>
          <label className="field">
            <input
              className="field__input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={200}
              placeholder={appText("«Никто не открыл, звонил трижды»", "«Асмайҙар, өс тапҡыр шылтыраттым»")}
            />
          </label>
          <div className="act-card__actions" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-primary"
              onClick={() =>
                run(
                  () => parcelAttemptFailed(parcel.id, reason.trim()),
                  appText("Отметили попытку", "Тырышыу билдәләнде")
                )
              }
              disabled={busy}
            >
              {busy ? appText("Отмечаем…", "Билдәләйбеҙ…") : appText("Отметить", "Билдәләү")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setSheet("none")}>
              {appText("Отмена", "Баш тартыу")}
            </button>
          </div>
        </div>
      )}

      {/* Возврат отправителю */}
      {sheet === "return" && (
        <div className="act-card act-card--warn">
          <div className="act-card__title">
            <IconArrow size={18} /> {appText("Везу обратно", "Кире алып барам")}
          </div>
          <p className="act-card__text">
            {(() => {
              // Курьер, который зря съездил, должен видеть, что дорога ему оплачена: иначе
              // возврат читается как «полдня и бензин впустую», и в село он больше не поедет.
              const дорога = Math.round((parcel.return_fee_parts?.total_kop ?? 0) / 100);
              // Заезды по просьбе отправителя называем отдельно: курьер должен видеть, что
              // вторая поездка оплачена, иначе он читает просьбу как «съезди по-человечески».
              const заезды = (parcel.return_fee_parts?.redeliver_kop ?? 0) > 0;
              const заЧтоRu = заезды ? "за дорогу, повторные заезды и ожидание" : "за дорогу и ожидание";
              const заЧтоBa = заезды ? "юл, ҡабат инеүҙәр һәм көтөү өсөн" : "юл һәм көтөү өсөн";
              return дорога > 0
                ? appText(
                    `Комиссию за возврат мы не берём — услуга не оказана. Ты приезжал и не застал получателя: отправитель вернёт тебе ${дорога} ₽ ${заЧтоRu}. Напиши причину для отправителя.`,
                    `Кире ҡайтарыу өсөн комиссия алмайбыҙ — хеҙмәт күрһәтелмәгән. Һин килдең, әммә алыусыны тапманың: ебәреүсе һиңә ${заЧтоBa} ${дорога} һум ҡайтара. Ебәреүсегә сәбәпте яҙ.`
                  )
                : appText(
                    "Комиссию за возврат мы не берём — услуга не оказана. Напиши причину для отправителя.",
                    "Кире ҡайтарыу өсөн комиссия алмайбыҙ — хеҙмәт күрһәтелмәгән. Ебәреүсегә сәбәпте яҙ."
                  );
            })()}
          </p>
          <label className="field">
            <input
              className="field__input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={200}
              placeholder={appText("«Получатель отказался»", "«Алыусы баш тартты»")}
            />
          </label>
          <div className="act-card__actions" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-primary"
              onClick={() =>
                run(
                  () => parcelReturnStart(parcel.id, reason.trim()),
                  appText("Возврат начат", "Кире ҡайтарыу башланды")
                )
              }
              disabled={busy}
            >
              {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Начать возврат", "Кире ҡайтарыуҙы башлау")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setSheet("none")}>
              {appText("Отмена", "Баш тартыу")}
            </button>
          </div>
        </div>
      )}

      {/* Спор по доставке */}
      {sheet === "dispute" && (
        <div className="act-card">
          <div className="act-card__title">
            <IconFlag size={18} /> {appText("Спор по доставке", "Илтеү буйынса бәхәс")}
          </div>
          <p className="act-card__text">
            {appText(
              "Вторая сторона объяснится, решение придёт с причиной — это разбор, а не жалоба в пустоту.",
              "Икенсе яҡ аңлатма бирер, ҡарар сәбәбе менән килер — был бушлыҡҡа зарланыу түгел."
            )}
          </p>
          <div className="chips">
            {DISPUTE_TYPES.map((t) => (
              <button
                key={t.key}
                type="button"
                className={"chip" + (dtype === t.key ? " chip--on" : "")}
                onClick={() => setDtype(t.key)}
              >
                {appText(t.ru, t.ba)}
              </button>
            ))}
          </div>
          <label className="field" style={{ marginTop: 10 }}>
            <textarea
              className="field__area"
              rows={3}
              maxLength={1000}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={appText("Что случилось — спокойно и по делу", "Нимә булды — тыныс һәм эш буйынса")}
            />
          </label>

          {/* От чего будут считать. Без объявленной ценности разбор упирается
              в «она стоила дорого» против «она ничего не стоила» — и решать
              приходится вслепую. Говорим об этом до того, как спор открыт. */}
          <p className="act-card__text" style={{ marginTop: 6 }}>
            {(parcel.declared_value_kop ?? 0) > 0
              ? appText(
                  `Ориентир при споре — объявленная ценность: ${Math.round((parcel.declared_value_kop ?? 0) / 100)} ₽.`,
                  `Бәхәстә ориентир — иғлан ителгән хаҡ: ${Math.round((parcel.declared_value_kop ?? 0) / 100)} һум.`
                )
              : appText(
                  "Ценность не объявлена — решаем по договорённости между своими.",
                  "Хаҡ иғлан ителмәгән — үҙ-ара килешеү буйынса хәл итәбеҙ."
                )}
          </p>
          <div className="act-card__actions" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-primary"
              onClick={() =>
                run(
                  () => parcelDispute(parcel.id, { reason: reason.trim(), type: dtype }),
                  appText("Спор открыт — смотри в «Справедливости»", "Бәхәс асылды — «Ғәҙеллек»тә ҡара")
                )
              }
              disabled={busy || !reason.trim()}
            >
              {busy ? appText("Открываем…", "Асабыҙ…") : appText("Открыть спор", "Бәхәс асыу")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setSheet("none")}>
              {appText("Отмена", "Баш тартыу")}
            </button>
          </div>
        </div>
      )}

      {note && <p className="demand__quiet">{note}</p>}
    </>
  );
}
