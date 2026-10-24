// Sanitise all textual evidence, including values embedded in URLs or screen dumps.
export function redactText(value) {
  return String(value)
    .replace(/(\/(?:t|share)\/)[A-Za-z0-9_-]{12,}/g, "$1<redacted>")
    .replace(/([?&](?:token|code|phone|key|request_id|auth)[^=]*=)[^&#\s]*/gi, "$1<redacted>")
    .replace(/\bBearer\s+[^\s"']+/gi, "Bearer <redacted>")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, "<redacted-token>")
    .replace(/(?:\+7|\b8)[\s()\d-]{9,18}\d\b/g, "<redacted-phone>")
    .replace(/\b\d{6}\b/g, "<redacted-code>");
}
const secretKey = /token|code|phone|authorization|latitude|longitude|^(lat|lng|lon)$/i;
export function redact(value) {
  if (typeof value === "string") return redactText(value);
  if (value == null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(redact);
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key,
    secretKey.test(key) && item != null && typeof item !== "object" && key !== "exit_code"
      ? "<redacted>" : redact(item)]));
}
