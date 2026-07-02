import React from 'react';
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, DISPLAY, BODY } from './theme';

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

// ===== Реальный скриншот-mockup (в готовой рамке телефона, прозрачный фон) =====
// object-contain: показываем ЦЕЛИКОМ, без обрезки краёв. Лёгкий подъём + Ken-Burns.
export const Mockup: React.FC<{ src: string; height?: number }> = ({ src, height = 1360 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const rise = spring({ frame: f, fps, config: { damping: 16, mass: 0.8 } });
  const y = interpolate(rise, [0, 1], [90, 0]);
  const scale = interpolate(f, [0, 110], [1, 1.04], { extrapolateRight: 'clamp' });
  return (
    <div style={{ position: 'relative', opacity: rise, transform: `translateY(${y}px) scale(${scale})` }}>
      <div style={{ position: 'absolute', inset: 40, borderRadius: 120, background: C.green, opacity: 0.22, filter: 'blur(90px)' }} />
      <Img src={staticFile(src)} style={{ position: 'relative', height, width: 'auto', borderRadius: height * 0.04, filter: 'drop-shadow(0 50px 120px rgba(0,0,0,0.55))' }} />
    </div>
  );
};

// Реальные экраны (mockup, светлая тема, снято с приложения)
export const ScreenMap: React.FC = () => <Mockup src="app-map.png" />;
export const ScreenForm: React.FC = () => <Mockup src="app-form.png" />;
export const ScreenRequest: React.FC = () => <Mockup src="app-request.png" />;
