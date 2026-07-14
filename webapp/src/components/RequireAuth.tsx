import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";

/**
 * Обёртка приватных маршрутов. Публичное (лента поездок, карта) доступно без входа —
 * их сюда не заворачиваем. Приватное без токена уводит на /login (с запоминанием, куда шли).
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  const { appText } = useLang();

  if (status === "loading") {
    return (
      <div className="center-fill" role="status" aria-live="polite">
        <div className="spinner" aria-hidden />
        <p>{appText("Секундочку…", "Бер секунд…") /* DRAFT */}</p>
      </div>
    );
  }

  if (status === "guest") {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <>{children}</>;
}
