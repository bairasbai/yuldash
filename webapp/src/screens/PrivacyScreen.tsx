// Политика конфиденциальности → /privacy (публично). Контент — src/legal.ts.
import LegalScreen from "./LegalScreen";
import { PRIVACY } from "../legal";

export default function PrivacyScreen() {
  return <LegalScreen doc={PRIVACY} />;
}
