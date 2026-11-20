import { useEffect, useRef, useState } from "react";
import { advanceDriverStage, checkDriverStage, DriverStageProblem, repeatDriverStage } from "../api/driverStages";
import { getSessionGeneration } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import {
  bookingCommandOwner, isCurrentBookingOwner, readDriverStageCommands,
  type BookingCommandOwner, type DriverStage, type DriverStageCommand,
} from "./bookingCommandStore";

type ActionResult = Awaited<ReturnType<typeof advanceDriverStage>>;
type Notice = ({ result: ActionResult } | { error: unknown }) & { language: string };
type Controls = ReturnType<typeof useDriverStages>;

export function useDriverStages(bookingId?: number) {
  const { user } = useAuth();
  const { lang } = useLang();
  const generation = getSessionGeneration();
  const owner = bookingCommandOwner(user, generation);
  const namespace = owner?.namespace;
  const key = JSON.stringify([namespace, generation, bookingId]);
  const screenKey = useRef<string>();
  const operation = useRef<AbortController | null>(null);
  const lifetime = useRef<AbortController | null>(null);
  const [pending, setPending] = useState<DriverStageCommand[]>([]);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [attempt, setAttempt] = useState(0);

  async function read(current: BookingCommandOwner, scope: number | undefined, signal: AbortSignal) {
    try {
      const saved = await readDriverStageCommands(current);
      if (signal.aborted || !isCurrentBookingOwner(current)) return;
      setPending(saved.filter(row => row.state === "unknown" && (scope === undefined || row.bookingId === scope)));
      setLoadFailed(false);
      setLoaded(true);
    } catch {
      if (signal.aborted || !isCurrentBookingOwner(current)) return;
      setLoaded(false);
      setLoadFailed(true);
    }
  }

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    screenKey.current = key;
    setPending([]);
    setNotice(null);
    setBusy(false);
    setLoaded(false);
    setLoadFailed(false);
    if (user && namespace) void read({ userId: user.id, generation, namespace }, bookingId, controller.signal);
    return () => {
      controller.abort();
      operation.current?.abort();
      operation.current = null;
    };
  }, [namespace, generation, user?.id, bookingId, attempt, key]);

  async function run(action: (current: BookingCommandOwner, signal: AbortSignal) => Promise<ActionResult>) {
    const screen = lifetime.current;
    if (!owner || !screen || screenKey.current !== key || screen.signal.aborted || operation.current || !isCurrentBookingOwner(owner)) return;
    const controller = new AbortController();
    operation.current = controller;
    setBusy(true);
    setNotice(null);
    let result: ActionResult | undefined;
    try {
      result = await action(owner, controller.signal);
      if (!controller.signal.aborted && isCurrentBookingOwner(owner)) setNotice({ result, language: lang });
    } catch (error) {
      if (!controller.signal.aborted && isCurrentBookingOwner(owner)) setNotice({ error, language: lang });
    } finally {
      if (operation.current === controller) {
        await read(owner, bookingId, controller.signal);
        if (operation.current === controller && isCurrentBookingOwner(owner)) {
          operation.current = null;
          setBusy(false);
        }
      }
    }
    if (!controller.signal.aborted && !screen.signal.aborted && screenKey.current === key && isCurrentBookingOwner(owner)) return result;
  }

  const current = Boolean(owner && screenKey.current === key && isCurrentBookingOwner(owner));
  return {
    pending: current ? pending : [],
    busy: current && busy,
    notice: current ? notice : null,
    loadFailed: current && loadFailed,
    ready: current && loaded,
    reload: () => setAttempt(value => value + 1),
    advance: (id: number, stage: DriverStage) => run((current, signal) => advanceDriverStage(current, id, stage, signal)),
    check: (command: DriverStageCommand, close: boolean) =>
      run((current, signal) => checkDriverStage(current, command, close, signal)),
    repeat: (command: DriverStageCommand) => run((current, signal) => repeatDriverStage(current, command, signal)),
  };
}

export type { Controls as DriverStageControls };

export function useDriverStageNotice(notice: Notice | null): string {
  const { appText, lang } = useLang();
  if (!notice) return "";
  if ("error" in notice) {
    if (notice.error instanceof DriverStageProblem) {
      switch (notice.error.kind) {
        case "unknown": return appText("Ответ не пришёл. Проверь, сохранилась ли отметка.", "Яуап килмәне. Билдә һаҡланғанмы, тикшер.");
        case "expired": return appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.");
        case "refresh": return appText("Не удалось обновить статус. Проверь связь.", "Торошто яңыртып булманы. Бәйләнеште тикшер.");
      }
    }
    return appText("Не получилось проверить отметку. Попробуй ещё раз.", "Билдәне тикшереп булманы. Тағы ҡабатла.");
  }
  switch (notice.result.state) {
    case "applied": return appText("Статус поездки проверен.", "Сәфәр торошо тикшерелде.");
    case "unseen": return appText("Результат пока неизвестен. Можно повторить или закрыть запрос.", "Һөҙөмтә әлегә билдәһеҙ. Һорауҙы ҡабатларға йәки ябырға мөмкин.");
    case "rejected": return (notice.language === lang && notice.result.refusal?.message) ||
      appText("Сервер отклонил запрос. Проверь статус поездки.", "Сервер һорауҙы кире ҡаҡты. Сәфәр торошон тикшер.");
    case "closed_no_effect": return appText("Запрос закрыт. Можно отправить отметку заново.", "Һорау ябылды. Билдәне яңынан ебәрергә мөмкин.");
  }
}
