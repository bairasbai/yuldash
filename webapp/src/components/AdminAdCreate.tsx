// ================================================================
//  «Завести объявление» руками админа (POST /admin/ads).
//  Зеркало Android (ApiClient.createAdminAd).
//
//  Зачем. Партнёр из райцентра приходит не через кабинет, а по
//  телефону: «поставьте моё объявление, вот текст, вот телефон».
//  В вебе можно было только одобрять то, что партнёр оформил сам, —
//  то есть половине партнёров нужно было сначала объяснить, как
//  зарегистрироваться (сверка с Android, 2026-08-30).
//
//  Маркировка ОРД (erid) — обязательное поле по закону о рекламе,
//  и мы говорим об этом прямо, а не прячем в подсказке.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { adminCreateAd, type AdminAdInput } from "../api/admin";
import { IconRocket } from "./Icons";

const PLANS: { key: "standard" | "premium" | "founder"; ru: string; ba: string }[] = [
  { key: "standard", ru: "Обычный", ba: "Ғәҙәти" },
  { key: "premium", ru: "Премиум", ba: "Премиум" },
  { key: "founder", ru: "Основатель", ba: "Нигеҙләүсе" },
];

export default function AdminAdCreate({ onCreated }: { onCreated: () => void }) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<AdminAdInput>({ plan: "standard" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const set = (patch: Partial<AdminAdInput>) => setForm((prev) => ({ ...prev, ...patch }));

  const canSubmit =
    (form.title ?? "").trim().length > 0 &&
    (form.text ?? "").trim().length > 0 &&
    (form.erid ?? "").trim().length > 0 &&
    !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      await adminCreateAd({
        ...form,
        partner_name: (form.partner_name ?? "").trim(),
        partner_contact: (form.partner_contact ?? "").trim(),
        title: (form.title ?? "").trim(),
        text: (form.text ?? "").trim(),
        button: (form.button ?? "").trim(),
        target: (form.target ?? "").trim(),
        erid: (form.erid ?? "").trim(),
      });
      setForm({ plan: "standard" });
      setOpen(false);
      onCreated();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать. Проверь поля и сеть.", "Яһап булманы. Ҡырҙарҙы һәм селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft" style={{ width: "100%" }} onClick={() => setOpen(true)}>
        <IconRocket size={18} /> {appText("Завести объявление", "Иғлан яһау")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconRocket size={18} /> {appText("Новое объявление", "Яңы иғлан")}
      </div>
      <p className="act-card__text">
        {appText(
          "Для партнёров, которые пришли по телефону. Реклама помечается по закону — маркировка ОРД обязательна.",
          "Телефон аша килгән партнёрҙар өсөн. Реклама закон буйынса билдәләнә — ОРД маркировкаһы мотлаҡ."
        )}
      </p>

      <label className="field">
        <span className="field__label">{appText("Партнёр", "Партнёр")}</span>
        <input
          className="field__input"
          value={form.partner_name ?? ""}
          onChange={(e) => set({ partner_name: e.target.value.slice(0, 120) })}
          placeholder={appText("«Кафе Йондоҙ»", "«Йондоҙ кафеһы»")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Как с ним связаться", "Уның менән нисек бәйләнешергә")}</span>
        <input
          className="field__input"
          value={form.partner_contact ?? ""}
          onChange={(e) => set({ partner_contact: e.target.value.slice(0, 200) })}
          placeholder="+7 …"
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Заголовок", "Баш һүҙ")}</span>
        <input
          className="field__input"
          value={form.title ?? ""}
          onChange={(e) => set({ title: e.target.value.slice(0, 120) })}
          placeholder={appText("«Обеды по-домашнему»", "«Өйҙәгеләй ашамлыҡ»")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Текст", "Текст")}</span>
        <input
          className="field__input"
          value={form.text ?? ""}
          onChange={(e) => set({ text: e.target.value.slice(0, 600) })}
          placeholder={appText("Коротко, по-соседски", "Ҡыҫҡа, күршеләрсә")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Надпись на кнопке", "Кнопкалағы яҙыу")}</span>
        <input
          className="field__input"
          value={form.button ?? ""}
          onChange={(e) => set({ button: e.target.value.slice(0, 40) })}
          placeholder={appText("«Посмотреть»", "«Ҡарарға»")}
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Куда ведёт", "Ҡайҙа алып бара")}</span>
        <input
          className="field__input"
          value={form.target ?? ""}
          onChange={(e) => set({ target: e.target.value.slice(0, 500) })}
          placeholder="https://…"
          inputMode="url"
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Маркировка ОРД (erid)", "ОРД маркировкаһы (erid)")}</span>
        <input
          className="field__input"
          value={form.erid ?? ""}
          onChange={(e) => set({ erid: e.target.value.slice(0, 60) })}
          placeholder="2Vfnx…"
        />
        <span className="field__hint">
          {appText(
            "Без маркировки размещать рекламу нельзя — это требование закона, а не наше.",
            "Маркировкаһыҙ реклама урынлаштырырға ярамай — был закон талабы, беҙҙеке түгел."
          )}
        </span>
      </label>

      <div className="chips">
        {PLANS.map((p) => (
          <button
            key={p.key}
            type="button"
            className={"chip" + (form.plan === p.key ? " chip--on" : "")}
            onClick={() => set({ plan: p.key })}
            aria-pressed={form.plan === p.key}
          >
            {appText(p.ru, p.ba)}
          </button>
        ))}
      </div>

      <label className="field">
        <span className="field__label">{appText("Цена размещения, ₽", "Урынлаштырыу хаҡы, һум")}</span>
        <input
          className="field__input"
          inputMode="numeric"
          value={form.price ? String(form.price) : ""}
          onChange={(e) => set({ price: Number(e.target.value.replace(/[^\d]/g, "")) || 0 })}
          maxLength={7}
        />
        <span className="field__hint">
          {appText(
            "Больше нуля — создастся заявка на оплату, показ начнётся после подтверждения перевода.",
            "Нулдән ҙурыраҡ — түләү заявкаһы яһала, күрһәтеү күсереү раҫланғас башлана."
          )}
        </span>
      </label>

      {error && (
        <div className="notice" role="status">
          {error}
        </div>
      )}

      <div className="act-card__actions">
        <button type="button" className="btn-primary" onClick={() => void submit()} disabled={!canSubmit}>
          {busy ? appText("Создаём…", "Яһайбыҙ…") : appText("Создать", "Яһау")}
        </button>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
          {appText("Отмена", "Кире алыу")}
        </button>
      </div>
    </div>
  );
}
