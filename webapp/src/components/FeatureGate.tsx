// ================================================================
//  Гейт направления: пропускает к экрану, только если сервер его включил.
//  Иначе — «Скоро в Юлдаше» (ComingSoon). Зеркало Android FeatureGate (ComingSoon.kt).
//
//  Прямые ссылки, пуши и закладки идут через маршруты, поэтому гейт стоит на маршруте:
//  закрытый экран не загружается вовсе (его кусок кода даже не скачивается).
//
//  • Пока ответ сервера ни разу не приходил и запомненного нет — скелет, а не «скоро»:
//    лучше секунду подождать, чем мигнуть неправдой.
//  • Включили, пока человек смотрит на заглушку, — содержимое приходит на её место.
//  • `guestOnly` — гейт только для гостя. Вошедшему направление не закрываем флагом:
//    у него может быть начатое (посылка в пути), а сервер сам откажет в новом.
// ================================================================
import type { ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { getSessionGeneration } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { useFeatures } from "../features";
import { soonKind, type GateNeed } from "../featureRules";
import ComingSoon from "./ComingSoon";
import { LoadingList } from "./States";
import { lazyScreen } from "../lazyScreen";

const BenefitHistoryScreen = lazyScreen(() => import("../screens/BenefitHistoryScreen"));
const CourierHistoryScreen = lazyScreen(() => import("../screens/CourierHistoryScreen"));

export default function FeatureGate({
  need,
  guestOnly = false,
  children,
}: {
  need: GateNeed;
  guestOnly?: boolean;
  children: ReactNode;
}) {
  const features = useFeatures();
  const { pathname } = useLocation();
  const { status } = useAuth();

  const enforce = !guestOnly || status === "guest";
  const kind = enforce ? soonKind(need, features) : null;
  if (!kind) return <>{children}</>;
  if (!features.ready) return <LoadingList count={2} />;
  if (need === "courier" && status === "authed") return <CourierHistoryScreen />;
  if (need === "business" && status === "authed") return <BenefitHistoryScreen key={getSessionGeneration()} partner={pathname === "/partner"} />;
  return <ComingSoon kind={kind} variant="screen" />;
}
