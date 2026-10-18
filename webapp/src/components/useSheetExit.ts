// ================================================================
//  Уход нижней шторки (.sheet-backdrop / .sheet): шторка уезжает вниз, затемнение гаснет —
//  пара к появлению (sheetUp / fadeIn в ui.css), и только потом шторку убирают со страницы.
//  Раньше шторки пропадали в тот же кадр — «самоделка» (п.3 ⭐ «Готово» в AGENTS.md, приёмки PR151).
//
//  Шторка, которая пользуется уходом, — отдельный компонент: он живёт, пока шторка открыта,
//  поэтому каждое открытие начинается с чистого состояния. Хук — только на useState/useRef/useEffect:
//  тесты бронирования подменяют React ровно этими тремя (и react-dom — заглушкой createPortal).
//
//  Шторка рисуется прямо в <body> (`exit.portal(…)`), а не там, где её открыли. Затемнение — это
//  `position: fixed; inset: 0`, и оно накрывает экран, только пока ни у одного предка нет transform,
//  filter или will-change. А они есть: карточка после своего появления (fadeUp … both) так и держит
//  transform, панель такси двигается transform-ом. Внутри такой карточки «почему не сможешь» у
//  водителя такси рисовалась в её рамке: затемнение 356×158 вместо всего экрана, заголовок и первая
//  причина — под картой, снизу торчали кнопки поездки (живая проверка 17.10.2026). В <body> предков
//  с transform нет — шторка всегда во весь экран, где бы её ни открыли.
// ================================================================
import { useEffect, useRef, useState, type AnimationEvent, type KeyboardEvent, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";

/** Запас к длительности ухода, после которого шторка закрывается и без события о конце движения. */
const LEAVE_GRACE_MS = 80;

/** Человек просил «Уменьшить движение» — анимаций нет (index.css), и ждать конца ухода нечего. */
export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

/** Длительность из getComputedStyle («0.26s», «260ms», через запятую) → миллисекунды, самая долгая. */
export function cssTimeMs(value: string): number {
  return Math.max(0, ...value.split(",").map((v) => (v.trim().endsWith("ms") ? parseFloat(v) : parseFloat(v) * 1000) || 0));
}

/** Что внутри шторки ловит Tab: кнопки, поля и ссылки по порядку (выключенные — мимо). */
const FOCUSABLE =
  'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])';

/** Номер для id заголовка: у каждой открытой шторки свой (`aria-labelledby`). */
let titleSeq = 0;

/** Элемент под фокусом сейчас — или null, если браузера нет (тесты без document). */
function focusedElement(): HTMLElement | null {
  if (typeof document === "undefined" || typeof HTMLElement === "undefined") return null;
  const el = document.activeElement;
  return el instanceof HTMLElement ? el : null;
}

/** Идущее сейчас CSS-движение элемента с этим именем (появление шторки или затемнения). */
function runningAnimation(el: Element | null | undefined, name: string): Animation | null {
  const list = el?.getAnimations?.() ?? [];
  return list.find((a) => (a as CSSAnimation).animationName === name && a.playState === "running") ?? null;
}

/**
 * Уход шторки. `close` просит её уйти (повторное нажатие во время ухода ничего не делает), а
 * `onClose` вызывается ровно один раз — когда шторка ушла: по концу её `sheetDown` или, если это
 * событие не пришло (вкладка в фоне, стиль не применился), по длительности самого движения.
 *
 * - Уходящая шторка сразу становится `inert`: в ней ничего не нажать — ни пальцем, ни Enter с
 *   клавиатуры или читалки — и по Tab в неё не попасть (приёмка PR154: Esc, а за ним Enter на
 *   «Забронировать» отправлял бронь из исчезающей шторки).
 * - Закрыли, пока шторка ещё поднимается, — её же появление разворачивается с того места, где она
 *   сейчас: без рывка «вверх до конца — и вниз» (приёмка PR154).
 * - «Уменьшить движение» — `onClose` сразу.
 *
 * Шторка — диалог, и всё про клавиатуру и читалку живёт здесь, одно на все шторки:
 * - открылась — фокус внутрь: на саму шторку (читалка называет её заголовок) или на `initialFocus`;
 *   если фокус уже внутри (поле с autoFocus), его не трогаем;
 * - Esc уводит шторку вниз (если `canClose` не против: бронь или отмена ещё в пути), Tab ходит по кругу
 *   внутри и на страницу под затемнением не выходит;
 * - убрали со страницы — фокус возвращается туда, откуда шторку открыли.
 * Шторка вынесена в <body> (см. `portal`): без этого читалка дошла бы до неё только в конце страницы.
 *
 * `backdropClass` — на .sheet-backdrop; на саму .sheet (её же держит `sheetRef`) — `{...dialogProps}`,
 * `aria-labelledby={titleId}` и `onAnimationEnd`; на заголовок — `id={titleId}`. Всю разметку от затемнения
 * вниз компонент отдаёт через `portal`: `return exit.portal(<div className={exit.backdropClass}>…)`.
 */
export function useSheetExit(
  onClose: () => void,
  sheetRef: RefObject<HTMLElement>,
  options: {
    /** Куда поставить фокус при открытии вместо самой шторки (например, на безопасную «Оставить»). */
    initialFocus?: RefObject<HTMLElement>;
    /** Можно ли закрыть с клавиатуры (Esc) прямо сейчас: false, пока бронь или отмена в пути. */
    canClose?: () => boolean;
  } = {}
) {
  /** «down» — уезжает вниз (sheetDown); «rewind» — разворачивает недоигранное появление. */
  const [mode, setMode] = useState<"down" | "rewind" | null>(null);
  const started = useRef(false);
  const closed = useRef(false);
  /** Шторка ещё на странице. После размонтирования `onClose` не зовётся ни по какому пути (см. ниже). */
  const mounted = useRef(true);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const optionsRef = useRef(options);
  optionsRef.current = options;
  /** Откуда открыли: запоминаем ещё до того, как фокус сдвинет autoFocus поля внутри шторки. */
  const opener = useRef<HTMLElement | null | undefined>(undefined);
  if (opener.current === undefined) opener.current = focusedElement();
  const titleId = useRef("");
  if (!titleId.current) titleId.current = `sheet-title-${++titleSeq}`;

  function finish() {
    if (closed.current || !mounted.current) return;
    closed.current = true;
    onCloseRef.current();
  }

  function close() {
    if (started.current || closed.current) return;
    started.current = true;
    const sheet = sheetRef.current;
    if (sheet) sheet.inert = true;
    // inert уводит фокус только к следующему кадру, а Enter или пробел, нажатые следом за Esc, приходят
    // раньше — и доставались кнопке или галочке в уходящей шторке. Снимаем фокус сразу; когда шторка
    // уйдёт, он вернётся туда, откуда её открыли.
    const focused = document.activeElement;
    if (sheet && focused instanceof HTMLElement && sheet.contains(focused)) focused.blur();
    if (prefersReducedMotion()) {
      finish();
      return;
    }
    const rising = runningAnimation(sheet, "sheetUp");
    if (!rising) {
      setMode("down");
      return;
    }
    const elapsed = Number(rising.currentTime) || 0;
    if (rising.pending || elapsed <= 0) {
      // Подъём ещё не начался — шторки на экране нет. Разворот с нулевой точки браузер начинает с конца
      // появления: шторка мелькнула бы во весь рост (второй проход приёмки PR154). Убираем сразу.
      finish();
      return;
    }
    // Ещё поднимается: обратный ход того же движения — столько же, сколько она уже ехала вверх.
    const backdrop = sheet?.parentElement;
    const dimming = runningAnimation(backdrop, "fadeIn");
    rising.reverse();
    if (dimming) dimming.reverse();
    else backdrop?.animate?.([{ backgroundColor: "transparent" }], { duration: elapsed, easing: "ease", fill: "forwards" });
    rising.finished.then(finish, finish);
    setMode("rewind");
  }

  useEffect(() => {
    if (!mode) return;
    const el = sheetRef.current;
    const timer = window.setTimeout(finish, (el ? cssTimeMs(window.getComputedStyle(el).animationDuration) : 0) + LEAVE_GRACE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  // Одно правило: убрали шторку со страницы (экран закрыли, нажали «Назад») — `onClose` больше НЕ зовётся
  // ни по какому пути: ни по таймеру выше, ни по концу движения, ни по `rising.finished` в развороте
  // (отменённое движение отвечает как раз при размонтировании). `onClose` может уводить на другой экран
  // (RideDetailScreen: navigate("/map")), и вызов после размонтирования перехватывал бы «Назад»: человек
  // нажал «Назад» в первые ~260 мс ухода и оказывался на /map вместо предыдущей страницы (приёмка PR166).
  // Родитель, который убрал шторку сам, знает, что её нет. `mounted` возвращается в true при повторном
  // запуске эффекта (StrictMode в разработке сразу после монтирования гоняет очистку и эффект ещё раз).
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Открылась — фокус внутрь (если его туда ещё не поставило поле с autoFocus); убрали — обратно к тому,
  // откуда открыли. Раньше половина шторок фокус не переносила вовсе, и после выноса в <body> читалка
  // доходила до них только в конце страницы (приёмка PR181).
  useEffect(() => {
    const sheet = sheetRef.current;
    const inside = focusedElement();
    if (sheet && !(inside && sheet.contains(inside))) (optionsRef.current.initialFocus?.current ?? sheet).focus();
    return () => opener.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Esc — уход вниз; Tab и Shift+Tab — по кругу внутри шторки, наружу не выпускают. */
  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.stopPropagation();
      event.preventDefault();
      if (optionsRef.current.canClose?.() !== false) close();
      return;
    }
    if (event.key !== "Tab") return;
    const sheet = sheetRef.current;
    const controls = sheet?.querySelectorAll<HTMLElement>(FOCUSABLE);
    if (!sheet || !controls?.length) return;
    const first = controls[0];
    const last = controls[controls.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === sheet)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return {
    /** Шторка уходит: в ней уже ничего не нажимается (в том числе бронь). */
    leaving: mode !== null,
    close,
    backdropClass: "sheet-backdrop" + (mode === "down" ? " is-leaving" : ""),
    /** На саму .sheet: диалог для читалки, фокусируемый целиком; Esc и Tab — см. onKeyDown выше. */
    dialogProps: { role: "dialog" as const, "aria-modal": true as const, tabIndex: -1, onKeyDown },
    /** id заголовка шторки: `id={titleId}` на заголовок, `aria-labelledby={titleId}` на .sheet. */
    titleId: titleId.current,
    /**
     * Затемнение со шторкой — в <body>: ни один предок с transform не сожмёт затемнение до своей рамки.
     * События React по-прежнему идут через того, кто открыл шторку (портал так устроен). В DOM шторка —
     * в конце <body>, поэтому фокус в неё переносит сам хук (эффект выше), а Tab держит внутри onKeyDown.
     */
    portal(node: ReactNode) {
      return createPortal(node, document.body);
    },
    /** Своё движение «вниз», а не всплывшее от строки или кнопки внутри шторки. */
    onAnimationEnd(e: AnimationEvent<HTMLElement>) {
      if (mode === "down" && e.target === e.currentTarget && e.animationName === "sheetDown") finish();
    },
  };
}
