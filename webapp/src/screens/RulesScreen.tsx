// Правила сервиса (пользовательское соглашение / оферта) → /rules (публично).
import LegalScreen from "./LegalScreen";
import { TERMS } from "../legal";

export default function RulesScreen() {
  return <LegalScreen doc={TERMS} />;
}
