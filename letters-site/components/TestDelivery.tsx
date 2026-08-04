"use client";

import { useState } from "react";
import { testDelivery } from "@/app/actions";

/** Кнопка «а бот вообще жив?». Нужна ровно один раз, при настройке. */
export default function TestDelivery() {
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  return (
    <div className="flex items-center gap-4">
      <button
        type="button"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          const r = await testDelivery();
          setMsg(r.message);
          setBusy(false);
        }}
        className="rounded-full border border-panel-border px-5 py-2.5 font-sans text-[0.66rem] uppercase tracking-[0.18em] text-sky-ink transition-colors hover:bg-white/8 disabled:opacity-40"
      >
        {busy ? "проверяю…" : "проверить бота"}
      </button>
      {msg && <span className="font-sans text-xs text-sky-ink-soft">{msg}</span>}
    </div>
  );
}
