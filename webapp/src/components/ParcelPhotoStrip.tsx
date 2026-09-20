// ================================================================
//  «Как выглядела посылка» — зеркало ParcelPhotoStrip (ParcelsScreen.kt): снимки
//  «когда забрал» / «когда вручил» плитками 84, тап — просмотр во весь экран.
//  Снимки лежат в приватной области (/secure/evidence): <img> не умеет слать
//  Bearer, поэтому тянем сами и показываем blob-URL, как документы в админке.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchSecureDoc } from "../api/admin";

function SecureShot({ url, alt, onOpen }: { url: string; alt: string; onOpen: (src: string) => void }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let obj: string | null = null;
    const ac = new AbortController();
    fetchSecureDoc(url, ac.signal)
      .then((u) => {
        obj = u;
        setSrc(u);
      })
      .catch((e) => {
        if (ac.signal.aborted || e?.name === "AbortError") return;
        setFailed(true);
      });
    return () => {
      ac.abort();
      if (obj) URL.revokeObjectURL(obj);
    };
  }, [url]);
  if (failed) return <span className="pshot__img pshot__img--none" aria-hidden />;
  if (!src) return <span className="pshot__img skeleton" aria-hidden />;
  return (
    <button type="button" className="pshot__btn" onClick={() => onOpen(src)} aria-label={alt}>
      <img className="pshot__img" src={src} alt={alt} />
    </button>
  );
}

export default function ParcelPhotoStrip({ pickupUrl, deliveryUrl }: { pickupUrl?: string | null; deliveryUrl?: string | null }) {
  const { appText } = useLang();
  const [viewing, setViewing] = useState<string | null>(null);
  const shots: { url: string; label: string }[] = [];
  if (pickupUrl?.trim()) shots.push({ url: pickupUrl, label: appText("Когда забрал", "Алғанда") });
  if (deliveryUrl?.trim()) shots.push({ url: deliveryUrl, label: appText("Когда вручил", "Тапшырғанда") });
  if (shots.length === 0) return null;
  return (
    <div className="pshots">
      <span className="dl-hint">{appText("Как выглядела посылка", "Бандероль ниндәй ине")}</span>
      <div className="pshots__row">
        {shots.map((s) => (
          <div key={s.url} className="pshot">
            <SecureShot url={s.url} alt={s.label} onOpen={setViewing} />
            <small>{s.label}</small>
          </div>
        ))}
      </div>
      {/* Просмотр во весь экран: только посмотреть и закрыть. */}
      {viewing && (
        <div className="pshot-view" role="dialog" aria-modal="true" onClick={() => setViewing(null)}>
          <img src={viewing} alt={appText("Фото посылки", "Бандероль фотоһы")} />
        </div>
      )}
    </div>
  );
}
