// ================================================================
//  Ряд фильтров ленты «Ближайшие поездки»: Откуда · Куда · Когда · Фильтры.
//  Зеркало NearbyFiltersPanel.kt и NearbyCityFilter.kt (Android).
//
//  Было двенадцать чипов в одном ряду: условия листались вбок, «Тихая поездка» пряталась за
//  краем экрана. Теперь каждый чип — один вопрос (где, куда, когда, какие условия), а ответ
//  выбирают в нижней шторке; выбранное видно прямо на чипе («Откуда · Баймак +1»,
//  «Когда · Завтра», «Фильтры · 2»).
// ================================================================
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { useLang } from "../i18n/lang";
import type { NearbyCityFilter } from "../nearbyCityFilter";
import {
  chosenNames,
  cityChipLabel,
  cityRows,
  dayOptions,
  isOtherDay,
  isPickableDay,
  localDayIso,
  NEARBY_CONDITIONS,
  otherDayLabel,
  showRidesLabel,
  toggleCondition,
  whenChipLabel,
  type NearbyCondition,
} from "../nearbyFeed";
import CityField from "./CityField";
import { useSheetExit } from "./useSheetExit";
import { ConditionIcon } from "./conditionIcons";
import { IconCalendar, IconCheck, IconClose, IconFlag, IconPin, IconShield, IconTune } from "./Icons";

// ---------------------------------------------------------------- шторка и строки

const FOCUSABLE = 'button:not(:disabled), input:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])';

/**
 * Нижняя шторка ленты: заголовок (+ действие справа, например «Сбросить») и содержимое.
 * Закрывается тапом по затемнению, Esc, крестиком и кнопкой внутри — одинаково: шторка уезжает
 * вниз, затемнение гаснет (пара к появлению `sheetUp` / `fadeIn`, `useSheetExit`), и только потом `onClose`
 * убирает её со страницы. Раньше она пропадала мгновенно (п.3 «Готово»). Фокус уходит внутрь и возвращается на чип.
 */
export function NearbySheet({
  title,
  onClose,
  action,
  children,
}: {
  title: string;
  /** Шторка уже уехала — убери её со страницы. */
  onClose: () => void;
  action?: ReactNode;
  /** Функцией — когда кнопке внутри («Готово», выбор дня) нужно закрыть шторку тем же движением. */
  children: ReactNode | ((close: () => void) => ReactNode);
}) {
  const { appText } = useLang();
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const exit = useSheetExit(onClose, dialogRef);
  const close = exit.close;

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // На саму шторку, а не на поле поиска: иначе на телефоне сразу вылезла бы клавиатура.
    dialogRef.current?.focus();
    return () => previous?.focus();
  }, []);

  return exit.portal(
    <div className={exit.backdropClass} onClick={close} role="presentation">
      <div
        ref={dialogRef}
        className="sheet nbs"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={exit.onAnimationEnd}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.stopPropagation();
            close();
            return;
          }
          if (event.key !== "Tab") return;
          const controls = dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
          if (!controls?.length) return;
          const first = controls[0];
          const last = controls[controls.length - 1];
          const active = document.activeElement;
          if (event.shiftKey && (active === first || active === dialogRef.current)) {
            event.preventDefault();
            last.focus();
          } else if (!event.shiftKey && active === last) {
            event.preventDefault();
            first.focus();
          }
        }}
      >
        <div className="sheet__grip" aria-hidden />
        <div className="nbs__head">
          <h2 className="nbs__title" id={titleId}>
            {title}
          </h2>
          {action}
          <button type="button" className="nbs__close" onClick={close} aria-label={appText("Закрыть", "Ябырға")}>
            <IconClose size={22} />
          </button>
        </div>
        <div className="nbs__body">{typeof children === "function" ? children(close) : children}</div>
      </div>
    </div>
  );
}

/**
 * Строка-галочка шторок ленты (города, условия, день): вся строка — цель нажатия ≥48, читалка
 * экрана слышит «флажок, отмечен» (`role="radio"` — «переключатель», для выбора одного из).
 */
export function NearbyCheckRow({
  label,
  selected,
  icon,
  role = "checkbox",
  onToggle,
}: {
  label: string;
  selected: boolean;
  icon?: ReactNode;
  role?: "checkbox" | "radio";
  onToggle: () => void;
}) {
  return (
    <button type="button" role={role} aria-checked={selected} className={"nbs-row" + (selected ? " is-on" : "")} onClick={onToggle}>
      {icon && (
        <span className="nbs-row__icon" aria-hidden>
          {icon}
        </span>
      )}
      <span className="nbs-row__label">{label}</span>
      <span className="nbs-row__check" aria-hidden>
        <IconCheck size={20} />
      </span>
    </button>
  );
}

/** Чип, открывающий шторку. Выбранное — цветом и словами на самом чипе («Когда · Завтра»). */
function FeedChip({ icon, label, on, onClick }: { icon: ReactNode; label: string; on: boolean; onClick: () => void }) {
  return (
    <button type="button" className={"fchip" + (on ? " is-on" : "")} aria-haspopup="dialog" onClick={onClick}>
      <span className="fchip__icon" aria-hidden>
        {icon}
      </span>
      {label}
    </button>
  );
}

// ---------------------------------------------------------------- «Когда»

/** Чип «Когда» + шторка дня. Выбор одного из вариантов — шторка закрывается сразу. */
export function NearbyWhenChip({ day, onChange }: { day: string | null; onChange: (day: string | null) => void }) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  return (
    <>
      <FeedChip icon={<IconCalendar size={15} />} label={whenChipLabel(day, new Date(), appText)} on={day != null} onClick={() => setOpen(true)} />
      {open && (
        <NearbySheet title={appText("Когда едем", "Ҡасан барабыҙ")} onClose={() => setOpen(false)}>
          {(close) => (
            <NearbyDayOptions
              day={day}
              onPick={(next) => {
                onChange(next);
                close();
              }}
            />
          )}
        </NearbySheet>
      )}
    </>
  );
}

/**
 * Варианты дня (все дни / сегодня / завтра) и «Другая дата…»: поездки в РБ планируют на выходные
 * и на неделю вперёд, а не только на «завтра». Календарь — родной, от сегодняшнего дня.
 */
export function NearbyDayOptions({ day, onPick }: { day: string | null; onPick: (day: string | null) => void }) {
  const { appText } = useLang();
  const now = useMemo(() => new Date(), []);
  const [picking, setPicking] = useState(false);
  const dateRef = useRef<HTMLInputElement>(null);
  const other = isOtherDay(day, now);

  useEffect(() => {
    if (!picking) return;
    const el = dateRef.current;
    if (!el) return;
    el.focus();
    try {
      el.showPicker?.();
    } catch {
      /* браузер не умеет открывать календарь сам — поле видно, дату выбирают в нём */
    }
  }, [picking]);

  return (
    <div role="radiogroup" aria-label={appText("Когда едем", "Ҡасан барабыҙ")} className="nbs__body">
      {dayOptions(now).map((o) => (
        <NearbyCheckRow key={o.value ?? "all"} role="radio" label={appText(o.ru, o.ba)} selected={day === o.value} onToggle={() => onPick(o.value)} />
      ))}
      <NearbyCheckRow role="radio" label={otherDayLabel(day, now, appText)} selected={other} onToggle={() => setPicking(true)} />
      {picking && (
        <label className="field nbs__date">
          <span className="field__label">{appText("Дата поездки", "Сәфәр көнө")}</span>
          <input
            ref={dateRef}
            className="field__input"
            type="date"
            min={localDayIso(now)}
            value={other && day ? day : ""}
            onChange={(e) => {
              // Прошедший день и пустое поле (человек стёр дату) ленту не меняют.
              if (isPickableDay(e.target.value, now)) onPick(e.target.value);
            }}
          />
        </label>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- «Фильтры» (условия)

/**
 * Чип «Фильтры · N» + шторка условий. Условия применяются сразу (лента за шторкой обновляется),
 * кнопка внизу показывает, сколько поездок осталось, — человек видит цену каждой галочки.
 */
export function NearbyConditionsChip({
  chosen,
  onChange,
  verifiedOnly,
  total,
}: {
  chosen: readonly NearbyCondition[];
  onChange: (next: NearbyCondition[]) => void;
  verifiedOnly: boolean;
  total: number;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const base = appText("Фильтры", "Фильтрҙар");
  return (
    <>
      <FeedChip icon={<IconTune size={15} />} label={chosen.length ? `${base} · ${chosen.length}` : base} on={chosen.length > 0} onClick={() => setOpen(true)} />
      {open && (
        <NearbySheet
          title={base}
          onClose={() => setOpen(false)}
          action={
            chosen.length > 0 ? (
              <button type="button" className="nbs__reset" onClick={() => onChange([])}>
                {appText("Сбросить", "Бушатырға")}
              </button>
            ) : null
          }
        >
          {(close) => (
            <>
              <NearbyConditionsList chosen={chosen} onChange={onChange} verifiedOnly={verifiedOnly} />
              <button type="button" className="btn-primary nbs__cta" onClick={close}>
                <span aria-live="polite">{showRidesLabel(total, appText)}</span>
              </button>
            </>
          )}
        </NearbySheet>
      )}
    </>
  );
}

/** Пояснение «только проверенные» + галочки условий. */
export function NearbyConditionsList({
  chosen,
  onChange,
  verifiedOnly,
}: {
  chosen: readonly NearbyCondition[];
  onChange: (next: NearbyCondition[]) => void;
  verifiedOnly: boolean;
}) {
  const { appText } = useLang();
  return (
    <>
      {/* «Только проверенные» включают в «Безопасности», а режет она эту же ленту. Без строки здесь
          человек видел меньше поездок и не понимал почему. */}
      {verifiedOnly && (
        <p className="nbs-note">
          <IconShield size={20} />
          <span>
            {appText(
              "Показываем только проверенных водителей — так включено в «Безопасности».",
              "Тик тикшерелгән йөрөтөүселәрҙе күрһәтәбеҙ — «Хәүефһеҙлек» бүлегендә шулай ҡабыҙылған."
            )}
          </span>
        </p>
      )}
      <div role="group" aria-label={appText("Фильтры", "Фильтрҙар")} className="nbs__body">
        {NEARBY_CONDITIONS.map((c) => {
          const on = chosen.includes(c.key);
          return (
            <div key={c.key} className="nbs__body">
              <NearbyCheckRow label={appText(c.ru, c.ba)} selected={on} icon={<ConditionIcon condition={c.key} size={20} />} onToggle={() => onChange(toggleCondition(chosen, c.key))} />
              {/* «Только женщины» — это и женщины за рулём, а не одни поездки с такой отметкой. */}
              {c.key === "women" && on && (
                <p className="nbs-hint">{appText("Женщины за рулём и поездки «только для женщин».", "Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр.")}</p>
              )}
            </div>
          );
        })}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- «Откуда» и «Куда»

/**
 * Чипы «Откуда» и «Куда» в общем ряду. Нет городов с поездками (старый сервер, пустая база) —
 * не рисуем ничего: пустая шторка хуже.
 */
export function NearbyCityChips({ filter }: { filter: NearbyCityFilter }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [sheetFor, setSheetFor] = useState<"from" | "to" | null>(null);
  if (!filter.offered) return null;
  const isFrom = sheetFor === "from";
  return (
    <>
      <FeedChip
        icon={<IconPin size={15} />}
        label={cityChipLabel(appText("Откуда", "Ҡайҙан"), chosenNames(filter.from, filter.fromOptions, filter.found, ru))}
        on={filter.from.length > 0}
        onClick={() => setSheetFor("from")}
      />
      <FeedChip
        icon={<IconFlag size={15} />}
        label={cityChipLabel(appText("Куда", "Ҡайҙа"), chosenNames(filter.to, filter.toOptions, filter.found, ru))}
        on={filter.to.length > 0}
        onClick={() => setSheetFor("to")}
      />
      {sheetFor && (
        <NearbyCitySheet
          title={isFrom ? appText("Откуда", "Ҡайҙан") : appText("Куда", "Ҡайҙа")}
          anyLabel={isFrom ? appText("Все города", "Бөтә ҡалалар") : appText("Любое направление", "Теләһә ҡайҙа")}
          filter={filter}
          isFrom={isFrom}
          onClose={() => {
            setSheetFor(null);
            // Найденные поиском и не выбранные уходят вместе со шторкой: иначе деревня, найденная и тут же
            // снятая, висела строкой в «Откуда» и всплывала в «Куда». Выбранные остаются — по ним подписан чип.
            filter.dropUnchosenFound();
          }}
        />
      )}
    </>
  );
}

/**
 * Шторка выбора мест: поиск по справочнику (райцентры, деревни РБ и приграничья — та же строка,
 * что в публикации поездки), «Все города» снимает выбор, ниже — галочки: сначала найденные
 * поиском, потом города, где поездки есть сейчас. Можно несколько.
 */
function NearbyCitySheet({
  title,
  anyLabel,
  filter,
  isFrom,
  onClose,
}: {
  title: string;
  anyLabel: string;
  filter: NearbyCityFilter;
  isFrom: boolean;
  onClose: () => void;
}) {
  const { appText } = useLang();
  const [query, setQuery] = useState("");
  const chosen = isFrom ? filter.from : filter.to;
  const options = isFrom ? filter.fromOptions : filter.toOptions;
  const setChosen = isFrom ? filter.setFrom : filter.setTo;
  return (
    <NearbySheet title={title} onClose={onClose}>
      {(close) => (
        <>
          <CityField
            label={appText("Найти город или деревню", "Ҡала йәки ауыл табыу")}
            value={query}
            onChange={setQuery}
            onPick={(s) => {
              filter.addFound({ ru: s.name_ru, ba: s.name_ba || s.name_ru });
              if (!chosen.includes(s.name_ru)) setChosen([...chosen, s.name_ru]);
              setQuery(""); // выбрали — поле свободно для следующего города
            }}
          />
          <NearbyCheckRow label={anyLabel} selected={chosen.length === 0} onToggle={() => setChosen([])} />
          {cityRows(chosen, options, filter.found).map((city) => {
            const on = chosen.includes(city.ru);
            return (
              <NearbyCheckRow
                key={city.ru}
                label={appText(city.ru, city.ba)}
                selected={on}
                onToggle={() => setChosen(on ? chosen.filter((c) => c !== city.ru) : [...chosen, city.ru])}
              />
            );
          })}
          <button type="button" className="btn-primary nbs__cta" onClick={close}>
            {appText("Готово", "Әҙер")}
          </button>
        </>
      )}
    </NearbySheet>
  );
}
