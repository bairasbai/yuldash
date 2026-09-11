import { useLang } from "../i18n/lang";
import { YuTripList } from "./BrandIcons";

/** Скелетон карточки поездки (состояние загрузки). */
export function RideCardSkeleton() {
  return (
    <div className="skeleton-card" aria-hidden>
      <div className="skeleton" style={{ height: 22, width: "70%" }} />
      <div
        className="skeleton"
        style={{ height: 14, width: "45%", marginTop: 14 }}
      />
      <div
        style={{
          marginTop: 16,
          display: "flex",
          alignItems: "center",
          gap: 10,
        }}
      >
        <div
          className="skeleton"
          style={{ height: 40, width: 40, borderRadius: "50%" }}
        />
        <div className="skeleton" style={{ height: 14, width: "40%" }} />
        <div
          className="skeleton"
          style={{ height: 20, width: 64, marginLeft: "auto" }}
        />
      </div>
    </div>
  );
}

export function LoadingList({ count = 4 }: { count?: number }) {
  return (
    <div>
      {Array.from({ length: count }).map((_, i) => (
        <RideCardSkeleton key={i} />
      ))}
    </div>
  );
}

export function EmptyState() {
  const { t } = useLang();
  return (
    <div className="state">
      <div className="state__icon">
        <YuTripList size={36} />
      </div>
      <h2>{t("emptyTitle")}</h2>
      <p>{t("emptyHint")}</p>
    </div>
  );
}

export function ErrorState({
  onRetry,
  title,
  hint,
}: {
  onRetry: () => void;
  title?: string;
  hint?: string;
}) {
  const { t } = useLang();
  return (
    <div className="state">
      <div className="state__icon state__icon--warn">
        <YuTripList size={36} />
      </div>
      <h2>{title ?? t("errorTitle")}</h2>
      <p>{hint ?? t("errorHint")}</p>
      <button type="button" className="btn-primary" onClick={onRetry}>
        {t("retry")}
      </button>
    </div>
  );
}
