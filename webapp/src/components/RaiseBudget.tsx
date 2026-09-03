// ================================================================
//  «В аптеке дороже — ладно, бери»: заказчик поднимает согласованную
//  сумму покупки (courier.py: POST /courier/orders/{id}/raise-budget).
//
//  Зачем. В «купи и привези» заказчик называет сумму, на которую
//  согласен. Курьер приходит в аптеку — там дороже. Он не может
//  провести расчёт: сумма выше согласованной. Товар при этом уже
//  куплен на ЕГО деньги, и оба висят в подвешенном состоянии.
//
//  Дверь есть на сервере с самого начала, а нажать её было негде —
//  ни в приложении, ни в вебе (сверка «ручки без клиента», 2026-08-31).
//
//  Поднимать может ТОЛЬКО заказчик и только вверх: курьер сумму
//  своего же счёта не двигает. Это правило держит сервер.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { raiseCourierBudget } from "../api/courier";
import { IconWallet } from "./Icons";

export default function RaiseBudget({
  parcelId,
  currentKop,
  onDone,
}: {
  parcelId: number;
  /** На какую сумму заказчик согласился раньше. Новая должна быть больше. */
  currentKop: number;
  onDone: () => void;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const currentRub = Math.round(currentKop / 100);

  async function submit() {
    if (busy) return;
    const rub = Math.round(Number(value.replace(",", ".")));
    if (!Number.isFinite(rub) || rub <= currentRub) {
      setNote(
        appText(
          `Новая сумма должна быть больше ${currentRub} ₽`,
          `Яңы сумма ${currentRub} һумдан ҙурыраҡ булырға тейеш`
        )
      );
      return;
    }
    setBusy(true);
    setNote("");
    try {
      await raiseCourierBudget(parcelId, rub * 100);
      setOpen(false);
      setValue("");
      onDone();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft btn-soft--sm" onClick={() => setOpen(true)}>
        <IconWallet size={16} /> {appText("Согласен на большую сумму", "Ҙурыраҡ суммаға риза")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconWallet size={18} /> {appText("Новая сумма покупки", "Яңы һатып алыу суммаһы")}
      </div>
      <p className="act-card__text">
        {appText(
          `Сейчас курьер может потратить до ${currentRub} ₽. Если в магазине дороже — назови новую сумму, и он сможет купить. Деньги вернёшь ему из рук в руки, как договаривались.`,
          `Хәҙер курьер ${currentRub} һумға тиклем тота ала. Кибеттә ҡиммәтерәк булһа — яңы сумманы әйт, ул һатып ала алыр. Аҡсаны килешкәнсә ҡулдан-ҡулға ҡайтараһың.`
        )}
      </p>
      <label className="field">
        <span className="field__label">{appText("Сколько теперь можно, ₽", "Хәҙер күпме мөмкин, һум")}</span>
        <input
          className="field__input"
          inputMode="numeric"
          value={value}
          onChange={(e) => setValue(e.target.value.replace(/[^\d]/g, "").slice(0, 7))}
          placeholder={String(currentRub + 200)}
        />
      </label>
      <div className="act-card__actions">
        <button type="button" className="btn-primary" onClick={() => void submit()} disabled={busy}>
          {appText("Поднять сумму", "Сумманы күтәреү")}
        </button>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
          {appText("Отмена", "Кире алыу")}
        </button>
      </div>
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </div>
  );
}
