import React from 'react';
import {
  AbsoluteFill,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
import { C, DISPLAY, BODY } from './theme';
import { Phone, Kicker, ScreenMap, ScreenForm, ScreenRequest } from './screens';

// Плавное появление/уход сцены (frame внутри Sequence — относительный)
const Fade: React.FC<{ dur: number; children: React.ReactNode }> = ({ dur, children }) => {
  const f = useCurrentFrame();
  const o = interpolate(f, [0, 14, dur - 14, dur], [0, 1, 1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return <AbsoluteFill style={{ opacity: o }}>{children}</AbsoluteFill>;
};

// Кинематографичный road-футаж с бренд-тонировкой
const RoadBg: React.FC<{ opacity?: number }> = ({ opacity = 1 }) => (
  <AbsoluteFill>
    <OffthreadVideo src={staticFile('road.mp4')} muted style={{ width: '100%', height: '100%', objectFit: 'cover', opacity }} />
    <AbsoluteFill style={{ background: `linear-gradient(180deg, rgba(7,15,11,0.55), rgba(7,15,11,0.4) 40%, rgba(7,15,11,0.92))` }} />
  </AbsoluteFill>
);

const Logo: React.FC<{ size?: number }> = ({ size = 300 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f, fps, config: { damping: 12, mass: 0.7 } });
  return (
    <div style={{ transform: `scale(${s})` }}>
      <div style={{ position: 'absolute', inset: -40, borderRadius: '50%', background: C.green, opacity: 0.3, filter: 'blur(70px)' }} />
      <div style={{ position: 'relative', width: size, height: size, borderRadius: '50%', background: C.forest, border: `3px solid ${C.green}55`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Img src={staticFile('logo.png')} style={{ width: size * 0.72, height: size * 0.72, objectFit: 'contain' }} />
      </div>
    </div>
  );
};

// ===== Сцена 1: интро =====
const SceneIntro: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const w = spring({ frame: f - 18, fps, config: { damping: 15 } });
  return (
    <AbsoluteFill>
      <RoadBg opacity={0.35} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 44 }}>
        <Logo />
        <div style={{ textAlign: 'center', opacity: w, transform: `translateY(${interpolate(w, [0, 1], [40, 0])}px)` }}>
          <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 150, color: C.white, letterSpacing: -4, lineHeight: 1 }}>Юлдаш</div>
          <div style={{ fontFamily: BODY, fontWeight: 600, fontSize: 46, color: C.glow, marginTop: 14 }}>Попутки между своими</div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

// ===== Сцена 2: road + кинетический заголовок =====
const SceneRoad: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - 4, fps, config: { damping: 18 } });
  const s2 = spring({ frame: f - 16, fps, config: { damping: 18 } });
  return (
    <AbsoluteFill>
      <RoadBg />
      <AbsoluteFill style={{ justifyContent: 'flex-end', padding: 90, paddingBottom: 180 }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 96, color: C.white, lineHeight: 1.04, opacity: s, transform: `translateY(${interpolate(s, [0, 1], [40, 0])}px)` }}>
          Уфа → Сибай.
        </div>
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 96, color: C.glow, lineHeight: 1.04, opacity: s2, transform: `translateY(${interpolate(s2, [0, 1], [40, 0])}px)` }}>
          И дальше.
        </div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 42, color: C.mint, marginTop: 22, opacity: s2 }}>Вся республика — между своими</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

// Обёртка для сцен с телефоном
const PhoneScene: React.FC<{ title: string; sub: string; children: React.ReactNode }> = ({ title, sub, children }) => (
  <AbsoluteFill style={{ background: `radial-gradient(120% 80% at 50% 0%, #14231b 0%, ${C.night} 70%)` }}>
    <Kicker title={title} sub={sub} top={130} />
    <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'flex-end', paddingBottom: 40 }}>
      <div style={{ transform: 'scale(0.9)' }}>
        <Phone>{children}</Phone>
      </div>
    </AbsoluteFill>
  </AbsoluteFill>
);

// ===== Сцена 6: CTA =====
const SceneCTA: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - 6, fps, config: { damping: 15 } });
  const btn = spring({ frame: f - 20, fps, config: { damping: 13 } });
  return (
    <AbsoluteFill>
      <RoadBg opacity={0.3} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 26 }}>
        <Logo size={220} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 92, color: C.white, letterSpacing: -2, opacity: s }}>Доедем вместе</div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 42, color: C.mint, opacity: s }}>Скачай Юлдаш — присоединяйся к своим</div>
        <div style={{ marginTop: 24, display: 'flex', alignItems: 'center', gap: 16, background: C.green, borderRadius: 44, padding: '28px 56px', opacity: btn, transform: `scale(${interpolate(btn, [0, 1], [0.85, 1])})`, boxShadow: `0 20px 60px ${C.green}66` }}>
          <svg width="42" height="42" viewBox="0 0 24 24" fill={C.night} aria-hidden="true"><path d="M3 20.5 13.5 12 3 3.5C2.7 3.7 2.5 4.1 2.5 4.6v14.8c0 .5.2.9.5 1.1Zm12.3-7 2.7 2.7-9.6 5.5 6.9-8.2Zm0-3-6.9-8.2 9.6 5.5-2.7 2.7ZM20.5 12c.6.4.9 1 .9 1.6 0 .6-.3 1.2-.9 1.6l-2 1.1-3-2.7 3-2.7 2 1.1Z" /></svg>
          <span style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 46, color: C.night }}>Скачать на Android</span>
        </div>
        <div style={{ fontFamily: BODY, fontWeight: 700, fontSize: 40, color: C.gold, marginTop: 12, opacity: btn }}>yulbash.ru</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

export const Promo: React.FC = () => {
  return (
    <AbsoluteFill style={{ background: C.night }}>
      <Sequence durationInFrames={92}><Fade dur={92}><SceneIntro /></Fade></Sequence>
      <Sequence from={92} durationInFrames={120}><Fade dur={120}><SceneRoad /></Fade></Sequence>
      <Sequence from={212} durationInFrames={120}><Fade dur={120}><PhoneScene title="Карта поездок" sub="Земляки уже в пути · SOS рядом"><ScreenMap /></PhoneScene></Fade></Sequence>
      <Sequence from={332} durationInFrames={110}><Fade dur={110}><PhoneScene title="Заявка и условия" sub="Телефон скрыт · условия поездки"><ScreenForm /></PhoneScene></Fade></Sequence>
      <Sequence from={442} durationInFrames={110}><Fade dur={110}><PhoneScene title="Заявки и отклики" sub="Оставь заявку — свои откликнутся"><ScreenRequest /></PhoneScene></Fade></Sequence>
      <Sequence from={552} durationInFrames={96}><Fade dur={96}><SceneCTA /></Fade></Sequence>
    </AbsoluteFill>
  );
};
