// ================================================================
//  Material 3 AlertDialog — как в приложении: карточка 28 по центру над затемнением,
//  заголовок 24 Bold, текст 14 muted, кнопки-текст справа внизу. Подтверждение красное
//  или зелёное — по смыслу действия, «отмена» всегда зелёная.
// ================================================================
import { useEffect, type ReactNode } from "react";

export type AlertTone = "danger" | "green";

export default function AlertDialog({
  title,
  text,
  children,
  confirm,
  dismiss,
  onClose,
}: {
  title: string;
  text?: string;
  children?: ReactNode;
  confirm?: { label: string; tone?: AlertTone; onClick: () => void; disabled?: boolean; busy?: boolean };
  dismiss?: { label: string; onClick: () => void; disabled?: boolean; tone?: "green" | "muted" };
  /** Тап по затемнению и Esc. */
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="adlg-backdrop" onClick={onClose} role="presentation">
      <div className="adlg" role="alertdialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h2 className="adlg__title">{title}</h2>
        {text && <p className="adlg__text">{text}</p>}
        {children}
        {(confirm || dismiss) && (
          <div className="adlg__actions">
            {dismiss && (
              <button type="button" className={"adlg__btn adlg__btn--" + (dismiss.tone ?? "green")} onClick={dismiss.onClick} disabled={dismiss.disabled}>
                {dismiss.label}
              </button>
            )}
            {confirm && (
              <button
                type="button"
                className={"adlg__btn adlg__btn--" + (confirm.tone ?? "green")}
                onClick={confirm.onClick}
                disabled={confirm.disabled || confirm.busy}
              >
                {confirm.busy ? <span className="spinner spinner--sm" aria-hidden /> : confirm.label}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
