"use client";

import { FormEvent, useState } from "react";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { BorderBeam } from "./BorderBeam";
import { track } from "./analytics";

// Ранний доступ / лист ожидания (волна 2, §11 «Запуск»): «оставь номер — сообщим,
// когда включим твой город». POST на относительный /waitlist (API живёт на том же
// домене yulbash.ru, как /landing-stats и /reviews/public). Роль — табы
// пассажир/водитель; водителям — оффер «0% комиссии первые 3 месяца».
// Телефон валидируем как на сервере: 10–15 цифр, опциональный ведущий +.

const PHONE_RE = /^\+?\d{10,15}$/;

type Role = "passenger" | "driver";

export function EarlyAccess() {
  const { tr } = useLang();
  const [role, setRole] = useState<Role>("passenger");
  const [phone, setPhone] = useState("");
  const [city, setCity] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState<Role | null>(null);
  const [error, setError] = useState<"phone" | "send" | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const normalized = phone.replace(/[\s\-()]/g, "");
    if (!PHONE_RE.test(normalized)) {
      setError("phone");
      return;
    }
    setSending(true);
    setError(null);
    try {
      const res = await fetch("/waitlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ phone: normalized, city: city.trim() || undefined, role }),
        // Зависший запрос иначе держит кнопку в «Отправляем…» бесконечно (catch покажет «Повторить»)
        signal: AbortSignal.timeout(15000),
      });
      if (!res.ok) throw new Error(String(res.status));
      setSent(role);
      track(role === "driver" ? "waitlist_driver" : "waitlist_passenger");
    } catch {
      setError("send");
    } finally {
      setSending(false);
    }
  };

  return (
    <section id="early-access" className="relative px-6 py-24">
      <div className="mx-auto max-w-3xl">
        <Reveal>
          <div className="glass relative overflow-hidden rounded-[34px] p-8 sm:p-12">
            <BorderBeam />
            <div className="pointer-events-none absolute -top-24 left-1/2 h-64 w-64 -translate-x-1/2 rounded-full bg-green-bright/20 blur-[100px]" />

            <div className="text-center">
              <span className="inline-block rounded-full border border-gold-light/30 bg-gold-light/10 px-4 py-1.5 text-xs font-bold uppercase tracking-widest text-gold-light">
                {tr("ea_badge")}
              </span>
              <h2 className="mt-5 font-display text-3xl font-extrabold tracking-tight sm:text-4xl">
                {tr("ea_title")}
              </h2>
              <p className="mx-auto mt-3 max-w-md text-white/60">{tr("ea_sub")}</p>
            </div>

            {sent ? (
              // -------- успех: «Ты в списке!» --------
              <div className="mx-auto mt-8 max-w-md rounded-canon border border-green-bright/30 bg-green-bright/10 p-6 text-center" role="status">
                <p className="font-display text-2xl font-extrabold text-white">{tr("ea_success_title")}</p>
                <p className="mt-2 text-white/70">
                  {sent === "driver" ? tr("ea_success_d") : tr("ea_success_p")}
                </p>
              </div>
            ) : (
              <form onSubmit={submit} className="mx-auto mt-8 max-w-md" noValidate>
                {/* табы роли */}
                <div className="grid grid-cols-2 gap-2 rounded-canon border border-white/10 bg-white/5 p-1.5" role="tablist">
                  {(["passenger", "driver"] as Role[]).map((r) => (
                    <button
                      key={r}
                      type="button"
                      role="tab"
                      aria-selected={role === r}
                      onClick={() => setRole(r)}
                      className={`min-h-[48px] rounded-[16px] px-4 text-sm font-bold transition-colors ${
                        role === r ? "bg-green-bright/20 text-white" : "text-white/50 hover:text-white/80"
                      }`}
                    >
                      {r === "passenger" ? tr("ea_tab_passenger") : tr("ea_tab_driver")}
                    </button>
                  ))}
                </div>

                {/* оффер водителям */}
                {role === "driver" && (
                  <p className="mt-3 rounded-canon border border-gold-light/25 bg-gold-light/10 px-4 py-3 text-sm text-gold-light">
                    🚖 {tr("ea_driver_hint")}
                  </p>
                )}

                <div className="mt-4 flex flex-col gap-3">
                  <label className="sr-only" htmlFor="ea-phone">{tr("ea_phone")}</label>
                  <input
                    id="ea-phone"
                    type="tel"
                    inputMode="tel"
                    autoComplete="tel"
                    required
                    placeholder="+7 9__ ___-__-__"
                    value={phone}
                    onChange={(e) => {
                      setPhone(e.target.value.slice(0, 20));
                      if (error === "phone") setError(null);
                    }}
                    className="min-h-[52px] w-full rounded-canon border border-white/15 bg-white/5 px-5 text-white placeholder-white/30 outline-none transition-colors focus:border-green-bright/60"
                  />
                  <label className="sr-only" htmlFor="ea-city">{tr("ea_city")}</label>
                  <input
                    id="ea-city"
                    type="text"
                    autoComplete="address-level2"
                    placeholder={tr("ea_city_ph")}
                    value={city}
                    onChange={(e) => setCity(e.target.value.slice(0, 40))}
                    className="min-h-[52px] w-full rounded-canon border border-white/15 bg-white/5 px-5 text-white placeholder-white/30 outline-none transition-colors focus:border-green-bright/60"
                  />

                  {error && (
                    <p className="text-sm text-red-400" role="alert">
                      {error === "phone" ? tr("ea_err_phone") : tr("ea_err_send")}
                    </p>
                  )}

                  <button
                    type="submit"
                    disabled={sending || !phone.trim()}
                    className="min-h-[52px] w-full rounded-canon bg-green-bright font-display text-base font-bold text-emerald-950 transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {sending ? tr("ea_sending") : tr("ea_cta")}
                  </button>
                  <p className="text-center text-xs text-white/40">{tr("ea_privacy")}</p>
                </div>
              </form>
            )}
          </div>
        </Reveal>
      </div>
    </section>
  );
}
