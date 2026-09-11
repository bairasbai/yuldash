import { useLang } from "../i18n/lang";
import { YuModeCourier, YuModeRideshare, YuModeTaxi } from "./BrandIcons";

export type RideMode = "pooling" | "taxi" | "courier";

/**
 * Переключатель режимов — зеркало Android ModeSwitchBar: одна пилюля, внутри которой активный
 * сегмент плавно едет на своё место; иконка и заголовок в строку, подпись режима — одной
 * строкой под контролом; «?» открывает объяснение «чем отличается».
 */
export default function ModeSwitch({
  mode,
  onSelect,
  onExplain,
  showExplain = true,
}: {
  mode: RideMode;
  onSelect: (mode: RideMode) => void;
  onExplain?: () => void;
  showExplain?: boolean;
}) {
  const { appText } = useLang();
  const modes: { key: RideMode; icon: JSX.Element; title: string }[] = [
    { key: "pooling", icon: <YuModeRideshare size={16} />, title: appText("Попутка", "Юлдаш") },
    { key: "taxi", icon: <YuModeTaxi size={16} />, title: appText("Такси", "Такси") },
    { key: "courier", icon: <YuModeCourier size={16} />, title: appText("Курьер", "Курьер") },
  ];
  const index = modes.findIndex((m) => m.key === mode);
  const subtitle =
    mode === "taxi"
      ? appText("машина сейчас", "машина хәҙер")
      : mode === "courier"
        ? appText("отправить посылку", "бандероль ебәреү")
        : appText("по пути, дешевле", "юл уҙа, арзаныраҡ");
  const explainLabel = appText("Чем отличается?", "Айырмаһы нимәлә?");

  return (
    <div className={"mode-switch mode-switch--" + mode}>
      <div className="mode-switch__pill">
        <div className="mode-switch__track" role="tablist" aria-label={appText("Режим поездки", "Сәфәр режимы")}>
          <span
            className="mode-switch__thumb"
            aria-hidden
            style={{ transform: `translateX(${index * 100}%)` }}
          />
          {modes.map((m) => (
            <button
              key={m.key}
              type="button"
              role="tab"
              aria-selected={m.key === mode}
              className={"mode-switch__seg" + (m.key === mode ? " is-on" : "")}
              onClick={() => onSelect(m.key)}
            >
              {m.icon}
              <span>{m.title}</span>
            </button>
          ))}
        </div>
        {showExplain && onExplain && (
          <>
            <span className="mode-switch__divider" aria-hidden />
            <button type="button" className="mode-switch__explain" onClick={onExplain} aria-label={explainLabel}>
              ?
            </button>
          </>
        )}
      </div>
      <p className="mode-switch__hint">{subtitle}</p>
    </div>
  );
}
