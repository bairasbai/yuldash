import React from 'react';
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
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

// ===== Реальный скриншот приложения с лёгким Ken-Burns =====
export const Shot: React.FC<{ src: string }> = ({ src }) => {
  const f = useCurrentFrame();
  const scale = interpolate(f, [0, 110], [1.06, 1.12], { extrapolateRight: 'clamp' });
  const ty = interpolate(f, [0, 110], [0, -14], { extrapolateRight: 'clamp' });
  return (
    <AbsoluteFill>
      <Img
        src={staticFile(src)}
        style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top', transform: `scale(${scale}) translateY(${ty}px)` }}
      />
    </AbsoluteFill>
  );
};

// Реальные экраны (снято с приложения, тёмная тема)
export const ScreenMap: React.FC = () => <Shot src="app-map.png" />;
export const ScreenForm: React.FC = () => <Shot src="app-form.png" />;
export const ScreenRequest: React.FC = () => <Shot src="app-request.png" />;
