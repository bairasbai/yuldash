// ================================================================
//  Карта доставки — зеркало ParcelTrackMap (CourierScreen.kt): маршрут забор → вручение,
//  поверх — живая точка курьера. Отправитель слушает /ws/parcel/{id}/location и видит,
//  где машина; курьер видит себя и шлёт свою позицию туда же (не чаще ~5 с). Канал живёт
//  только пока доставка в работе (accepted / in_transit) и только на этом экране.
// ================================================================
import { useEffect, useRef, useState } from "react";
import type { Parcel } from "../api/parcels";
import { getToken } from "../api/client";
import { wsBase } from "../api/chat";
import YandexMap, { type GeoPoint } from "./YandexMap";

export default function ParcelTrackMap({ parcel, asCourier }: { parcel: Parcel; asCourier: boolean }) {
  const from: GeoPoint | null = parcel.from_lat != null && parcel.from_lng != null ? { lat: parcel.from_lat, lng: parcel.from_lng } : null;
  const to: GeoPoint | null = parcel.to_lat != null && parcel.to_lng != null ? { lat: parcel.to_lat, lng: parcel.to_lng } : null;
  const active = parcel.status === "accepted" || parcel.status === "in_transit";
  const [peer, setPeer] = useState<GeoPoint | null>(null);
  const [me, setMe] = useState<GeoPoint | null>(null);
  const sockRef = useRef<WebSocket | null>(null);
  const lastSentRef = useRef(0);
  const prevRef = useRef<GeoPoint | null>(null);

  // Канал позиции: отправитель только слушает, курьер — шлёт. Закрылся заказ → сокет закрыт.
  useEffect(() => {
    if (!active) return;
    const token = getToken();
    if (!token) return;
    let ws: WebSocket | null = null;
    try {
      ws = new WebSocket(`${wsBase()}/ws/parcel/${parcel.id}/location`);
    } catch {
      return;
    }
    sockRef.current = ws;
    ws.onopen = () => ws?.send(JSON.stringify({ type: "auth", token }));
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m?.type === "loc" && m.role === "courier" && typeof m.lat === "number" && typeof m.lng === "number") {
          setPeer({ lat: m.lat, lng: m.lng });
        }
      } catch {
        /* чужой кадр — пропускаем */
      }
    };
    ws.onerror = () => {
      /* карта остаётся с маршрутом; позиции просто не будет */
    };
    return () => {
      sockRef.current = null;
      try {
        ws?.close();
      } catch {
        /* уже закрыт */
      }
    };
  }, [parcel.id, active]);

  // Курьер: своя позиция → на карту и в канал (курс — по смещению между точками).
  useEffect(() => {
    if (!asCourier || !active || !navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (p) => {
        const pt = { lat: p.coords.latitude, lng: p.coords.longitude };
        setMe(pt);
        const now = Date.now();
        if (now - lastSentRef.current < 5000) return;
        lastSentRef.current = now;
        const q = prevRef.current;
        let bearing: number | null = null;
        if (q) {
          const dLat = pt.lat - q.lat;
          const dLng = pt.lng - q.lng;
          if (Math.abs(dLat) + Math.abs(dLng) >= 0.00005) {
            bearing = ((Math.atan2(dLng * Math.cos((pt.lat * Math.PI) / 180), dLat) * 180) / Math.PI + 360) % 360;
          }
        }
        prevRef.current = pt;
        const ws = sockRef.current;
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "loc", lat: pt.lat, lng: pt.lng, bearing, ts: now }));
        }
      },
      () => {
        /* без места карта показывает только маршрут */
      },
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 12000 }
    );
    return () => navigator.geolocation.clearWatch(id);
  }, [asCourier, active]);

  if (!from && !to) return null; // без координат карту не рисуем
  return (
    <div className="parcel-track">
      <YandexMap from={from} to={to} route={!!(from && to)} me={asCourier ? me : peer} height={192} />
    </div>
  );
}
