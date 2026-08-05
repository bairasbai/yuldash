"use client";

import { motion } from "motion/react";
import { CONFIG } from "@/lib/config";
import type { Reply } from "@/lib/store";

/**
 * Ответы под письмом. Из монолога получается переписка,
 * и к встрече письма читаются вместе с тем, что она на них ответила.
 */
export default function RepliesList({ replies }: { replies: Reply[] }) {
  if (replies.length === 0) return null;

  return (
    <div className="mt-9">
      <div
        className="mb-5 h-px w-full"
        style={{
          background:
            "linear-gradient(to right, transparent, color-mix(in srgb, var(--color-ink) 16%, transparent), transparent)",
        }}
      />

      <ul className="space-y-4">
        {replies.map((r, i) => (
          <motion.li
            key={r.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.06, duration: 0.6 }}
          >
            <div className="mb-1 flex items-baseline gap-2">
              <span className="font-hand text-xl text-ink/70">
                {r.author === "her" ? CONFIG.her.name : CONFIG.him.name}
              </span>
              <span className="font-sans text-[0.58rem] uppercase tracking-[0.14em] text-ink-faint">
                {r.created_at}
              </span>
            </div>
            <p className="whitespace-pre-line font-serif text-[1.05rem] leading-relaxed text-ink/85">
              {r.text}
            </p>
          </motion.li>
        ))}
      </ul>
    </div>
  );
}
