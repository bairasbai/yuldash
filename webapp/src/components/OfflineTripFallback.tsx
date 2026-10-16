import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { loadOfflineTripPass } from "../utils/offlineTripPass";
import OfflineTripPass from "./OfflineTripPass";

/** Only /trip/:id offers this saved card; private screen children remain unmounted. */
export default function OfflineTripFallback() {
  const { id } = useParams();
  const { status } = useAuth();
  const [, redraw] = useState(0);
  useEffect(() => {
    const changed = () => redraw(value => value + 1);
    window.addEventListener("online", changed);
    window.addEventListener("offline", changed);
    window.addEventListener("storage", changed);
    return () => {
      window.removeEventListener("online", changed);
      window.removeEventListener("offline", changed);
      window.removeEventListener("storage", changed);
    };
  }, []);
  const pass = status === "unavailable" && navigator.onLine === false
    ? loadOfflineTripPass(Number(id)) : null;
  return pass ? <OfflineTripPass pass={pass} /> : null;
}
