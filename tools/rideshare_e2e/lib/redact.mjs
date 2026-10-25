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
// UIAutomator stores screen text in XML attributes. Decode before scrubbing so
// numeric entities cannot hide a secret, then escape masks and quotes as XML.
export function redactUiXml(value) {
  const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
  const decode = text => text.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (whole, entity) => {
    if (!entity.startsWith("#")) return named[entity] ?? whole;
    const point = entity[1].toLowerCase() === "x" ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
    if (!(point === 9 || point === 10 || point === 13 || (point >= 32 && point <= 0xD7FF) ||
          (point >= 0xE000 && point <= 0xFFFD) || (point >= 0x10000 && point <= 0x10FFFF))) throw new Error("Invalid XML character reference");
    return String.fromCodePoint(point);
  });
  const escaped = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;", "\n": "&#10;", "\r": "&#13;", "\t": "&#9;" };
  return String(value).replace(/([\w:-]+\s*=\s*)(["'])([\s\S]*?)\2/g, (_, prefix, quote, content) =>
    prefix + quote + redactText(decode(content)).replace(/[&<>"'\n\r\t]/g, char => escaped[char]) + quote);
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
