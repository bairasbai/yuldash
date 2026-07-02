import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, DISPLAY, BODY } from './theme';

// ===== Рамка телефона (островок + скруглённый экран) =====
export const Phone: React.FC<{ children: React.ReactNode; scale?: number }> = ({ children, scale = 1 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const rise = spring({ frame: f, fps, config: { damping: 16, mass: 0.8 } });
  const y = interpolate(rise, [0, 1], [120, 0]);
  return (
    <div style={{ transform: `translateY(${y}px) scale(${scale})`, opacity: rise }}>
      <div style={{ position: 'absolute', inset: -60, borderRadius: 120, background: C.green, opacity: 0.18, filter: 'blur(80px)' }} />
      <div
        style={{
          position: 'relative',
          width: 520,
          height: 1060,
          borderRadius: 78,
          border: `16px solid #1c2722`,
          background: C.forest,
          boxShadow: '0 60px 140px rgba(0,0,0,0.55)',
          overflow: 'hidden',
        }}
      >
        <div style={{ position: 'absolute', top: 22, left: '50%', transform: 'translateX(-50%)', width: 150, height: 34, borderRadius: 20, background: '#000', zIndex: 20 }} />
        <div style={{ position: 'absolute', inset: 0, borderRadius: 62, overflow: 'hidden', background: '#0e1714' }}>{children}</div>
      </div>
    </div>
  );
};

// Общий заголовок сцены (кинетический)
export const Kicker: React.FC<{ title: string; sub: string; top?: number }> = ({ title, sub, top = 150 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - 3, fps, config: { damping: 18 } });
  return (
    <div style={{ position: 'absolute', top, left: 90, right: 90, textAlign: 'center', opacity: s, transform: `translateY(${interpolate(s, [0, 1], [26, 0])}px)`, zIndex: 5 }}>
      <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 74, color: C.white, lineHeight: 1.05, letterSpacing: -1 }}>{title}</div>
      <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 38, color: C.glow, marginTop: 16 }}>{sub}</div>
    </div>
  );
};

// ===== Экран: лента поездок =====
export const ScreenRides: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const rides = [
    { n: 'Айгуль', r: '4.9', from: 'Уфа', to: 'Стерлитамак', p: '350 ₽', s: 3 },
    { n: 'Ильдар', r: '4.8', from: 'Сибай', to: 'Баймак', p: '300 ₽', s: 2 },
    { n: 'Рамиль', r: '5.0', from: 'Уфа', to: 'Бирск', p: '400 ₽', s: 4 },
  ];
  return (
    <div style={{ padding: 26, paddingTop: 96, height: '100%', display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ background: 'rgba(25,36,32,0.7)', border: `1px solid ${C.green}33`, borderRadius: 26, padding: '20px 24px', display: 'flex', alignItems: 'center', gap: 14, fontFamily: BODY, fontWeight: 700, fontSize: 28, color: C.white }}>
        <span style={{ width: 16, height: 16, borderRadius: '50%', border: `3px solid ${C.gold}` }} />
        Уфа <span style={{ color: C.glow }}>→</span> <span style={{ width: 16, height: 16, borderRadius: '50%', background: C.green }} /> Сибай
      </div>
      {rides.map((r, i) => {
        const s = spring({ frame: f - 6 - i * 9, fps, config: { damping: 15 } });
        return (
          <div key={i} style={{ opacity: s, transform: `translateX(${interpolate(s, [0, 1], [70, 0])}px)`, background: C.card, borderRadius: 26, padding: 22 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <div style={{ width: 62, height: 62, borderRadius: '50%', background: `linear-gradient(135deg, ${C.green}55, ${C.greenDeep}66)`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: DISPLAY, fontWeight: 800, fontSize: 30, color: C.glow }}>{r.n[0]}</div>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontFamily: BODY, fontWeight: 700, fontSize: 30, color: C.white }}>
                  {r.n}
                  <span style={{ marginLeft: 'auto', color: C.gold, fontSize: 26 }}>★ {r.r}</span>
                </div>
                <div style={{ fontFamily: BODY, fontSize: 24, color: C.mute, marginTop: 4 }}>{r.from} → {r.to}</div>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
              <span style={{ fontFamily: BODY, fontSize: 24, color: C.mute }}>{r.s} места</span>
              <span style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 34, color: C.gold }}>{r.p}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

// ===== Экран: карта + маршрут =====
export const ScreenMap: React.FC = () => {
  const f = useCurrentFrame();
  const p = interpolate(f, [8, 66], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const d = 'M 120 900 C 200 760, 170 560, 300 470 S 400 260, 360 180';
  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: 'radial-gradient(120% 90% at 30% 10%, #17291f, #0a1310)' }}>
      <svg viewBox="0 0 480 1000" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <g stroke="#22332b" strokeWidth={7} fill="none" strokeLinecap="round">
          <path d="M-20 220 L220 260 L500 200" /><path d="M90 -20 L130 520 L80 1020" /><path d="M-20 680 L260 700 L500 640" />
        </g>
        <path d={d} stroke={C.green} strokeWidth={16} fill="none" strokeLinecap="round" opacity={0.35} />
        <path d={d} stroke={C.glow} strokeWidth={11} fill="none" strokeLinecap="round" pathLength={1} strokeDasharray={1} strokeDashoffset={1 - p} />
        <circle cx="120" cy="900" r="14" fill={C.gold} stroke="#0a1310" strokeWidth={5} />
        <circle cx="360" cy="180" r="16" fill={C.gold} stroke="#0a1310" strokeWidth={5} opacity={p > 0.9 ? 1 : 0} />
      </svg>
      <div style={{ position: 'absolute', top: 100, left: 26, background: 'rgba(25,36,32,0.75)', borderRadius: 999, padding: '14px 26px', fontFamily: BODY, fontWeight: 700, fontSize: 28, color: C.white }}>
        ≈ 12 мин <span style={{ color: C.mute }}>· 8 км</span>
      </div>
      <div style={{ position: 'absolute', left: 26, right: 26, bottom: 26, background: 'rgba(25,36,32,0.78)', borderRadius: 28, padding: '22px 26px' }}>
        <div style={{ fontFamily: BODY, fontWeight: 700, fontSize: 30, color: C.white }}>Карта и маршрут</div>
        <div style={{ fontFamily: BODY, fontSize: 24, color: C.mute, marginTop: 4 }}>Уфа → Стерлитамак</div>
      </div>
    </div>
  );
};

// ===== Экран: чат + безопасность =====
export const ScreenChat: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const b1 = spring({ frame: f - 8, fps, config: { damping: 16 } });
  const b2 = spring({ frame: f - 22, fps, config: { damping: 16 } });
  const sos = spring({ frame: f - 40, fps, config: { damping: 14 } });
  return (
    <div style={{ padding: 26, paddingTop: 96, height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, borderBottom: '1px solid rgba(255,255,255,0.08)', paddingBottom: 22, marginBottom: 22 }}>
        <div style={{ width: 68, height: 68, borderRadius: '50%', background: `linear-gradient(135deg, ${C.green}55, ${C.greenDeep}66)`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: DISPLAY, fontWeight: 800, fontSize: 32, color: C.glow }}>А</div>
        <div>
          <div style={{ fontFamily: BODY, fontWeight: 700, fontSize: 32, color: C.white }}>Айгуль</div>
          <div style={{ fontFamily: BODY, fontWeight: 600, fontSize: 24, color: C.glow }}>✓ Проверена</div>
        </div>
      </div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ alignSelf: 'flex-start', maxWidth: '80%', opacity: b1, transform: `translateY(${interpolate(b1, [0, 1], [20, 0])}px)`, background: '#1a2520', borderRadius: '26px 26px 26px 8px', padding: '20px 26px', fontFamily: BODY, fontSize: 28, color: C.white }}>Подъезжаю, выходите</div>
        <div style={{ alignSelf: 'flex-end', maxWidth: '80%', opacity: b2, transform: `translateY(${interpolate(b2, [0, 1], [20, 0])}px)`, background: C.green, borderRadius: '26px 26px 8px 26px', padding: '20px 26px', fontFamily: BODY, fontWeight: 500, fontSize: 28, color: C.night }}>Спасибо, уже выхожу</div>
      </div>
      <div style={{ display: 'flex', gap: 14, marginTop: 22, opacity: sos, transform: `scale(${interpolate(sos, [0, 1], [0.9, 1])})` }}>
        <div style={{ flex: 1, textAlign: 'center', background: 'rgba(239,68,68,0.16)', borderRadius: 999, padding: '20px 0', fontFamily: BODY, fontWeight: 800, fontSize: 30, color: '#fca5a5' }}>◈ SOS</div>
        <div style={{ width: 70, height: 70, borderRadius: '50%', background: 'rgba(255,255,255,0.08)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 30, color: C.glow }}>↗</div>
      </div>
    </div>
  );
};
