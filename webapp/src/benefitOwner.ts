import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "./auth/AuthProvider";
import { getSessionGeneration, getToken } from "./api/client";

/** Прямой Result не разрешает публикацию после выхода, смены владельца или unmount. */
export function useBenefitOwner() {
  const { status, user } = useAuth();
  const [generation] = useState(getSessionGeneration);
  const [ownerId] = useState(() => user?.id ?? null);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const isCurrent = useCallback(() => alive.current && status === "authed" &&
    (user?.id ?? null) === ownerId && generation === getSessionGeneration() && !!getToken(),
    [status, user?.id, ownerId, generation]);
  return { generation, isCurrent };
}
