"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import Envelope from "./Envelope";
import LetterSheet, { type LetterSheetData } from "./LetterSheet";
import ReplyBox from "./ReplyBox";

/**
 * Сегодняшний конверт и то, что из него выходит.
 *
 * Вскрывать интересно один раз. Если она уже открывала это письмо,
 * при следующем заходе оно показывается сразу развёрнутым.
 */
export default function TodayLetter({ letter }: { letter: LetterSheetData }) {
  const [state, setState] = useState<"sealed" | "opening" | "open">("sealed");
  const [instant, setInstant] = useState(false);

  useEffect(() => {
    try {
      if (localStorage.getItem(`opened-${letter.n}`)) {
        setInstant(true);
        setState("open");
      }
    } catch {
      // приватный режим браузера — просто дадим вскрыть заново
    }
  }, [letter.n]);

  const open = () => {
    setState("opening");
    try {
      localStorage.setItem(`opened-${letter.n}`, "1");
    } catch {
      // не критично
    }
    // Успеваем показать вскрытие целиком, и только потом меняем на лист
    window.setTimeout(() => setState("open"), 1450);
  };

  return (
    <div className="flex w-full flex-col items-center">
      <AnimatePresence mode="wait">
        {state !== "open" ? (
          <motion.div
            key="envelope"
            exit={{ opacity: 0, scale: 0.96, y: -20 }}
            transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
          >
            <Envelope
              n={letter.n}
              dateLabel={letter.dateLabel}
              state={state === "opening" ? "opening" : "sealed"}
              onOpen={open}
            />
          </motion.div>
        ) : (
          <motion.div
            key="sheet"
            className="w-full"
            initial={instant ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
          >
            <LetterSheet letter={letter} footer={<ReplyBox n={letter.n} />} />
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
