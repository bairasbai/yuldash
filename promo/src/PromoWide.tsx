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
import { Mockup } from './screens';

const Fade: React.FC<{ dur: number; children: React.ReactNode }> = ({ dur, children }) => {
  const f = useCurrentFrame();
  const o = interpolate(f, [0, 14, dur - 14, dur], [0, 1, 1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return <AbsoluteFill style={{ opacity: o }}>{children}</AbsoluteFill>;
};

const RoadBg: React.FC<{ opacity?: number }> = ({ opacity = 1 }) => (
  <AbsoluteFill>
    <OffthreadVideo src={staticFile('road.mp4')} muted style={{ width: '100%', height: '100%', objectFit: 'cover', opacity }} />
    <AbsoluteFill style={{ background: `linear-gradient(180deg, rgba(7,15,11,0.5), rgba(7,15,11,0.35) 40%, rgba(7,15,11,0.9))` }} />
  </AbsoluteFill>
);

// Логотип-знак без «блюдца»: сам пин + мягкое свечение-ореол + тень.
const Logo: React.FC<{ size?: number }> = ({ size = 200 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f, fps, config: { damping: 12, mass: 0.7 } });
  return (
    <div style={{ position: 'relative', transform: `scale(${s})`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ position: 'absolute', width: size * 1.9, height: size * 1.9, borderRadius: '50%', background: 'radial-gradient(circle, rgba(255,255,255,0.62) 0%, rgba(255,255,255,0.18) 36%, rgba(255,255,255,0) 66%)', filter: 'blur(16px)' }} />
      <Img src={staticFile('logo.png')} style={{ position: 'relative', width: size, height: size, objectFit: 'contain', filter: 'drop-shadow(0 10px 26px rgba(0,0,0,0.45))' }} />
    </div>
  );
};

const SceneIntro: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const w = spring({ frame: f - 16, fps, config: { damping: 15 } });
  return (
    <AbsoluteFill>
      <RoadBg opacity={0.4} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', gap: 50 }}>
        <Logo />
        <div style={{ opacity: w, transform: `translateX(${interpolate(w, [0, 1], [50, 0])}px)` }}>
          <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 150, color: C.white, letterSpacing: -4, lineHeight: 1 }}>Юлдаш</div>
          <div style={{ fontFamily: BODY, fontWeight: 600, fontSize: 46, color: C.glow, marginTop: 12 }}>Попутки между своими</div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const SceneRoad: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - 4, fps, config: { damping: 18 } });
  const s2 = spring({ frame: f - 16, fps, config: { damping: 18 } });
  return (
    <AbsoluteFill>
      <RoadBg />
      <AbsoluteFill style={{ justifyContent: 'flex-end', padding: 110, paddingBottom: 130 }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 110, color: C.white, lineHeight: 1.02, opacity: s, transform: `translateY(${interpolate(s, [0, 1], [40, 0])}px)` }}>
          Уфа → Сибай. <span style={{ color: C.glow }}>И дальше.</span>
        </div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 46, color: C.mint, marginTop: 20, opacity: s2 }}>Вся республика — между своими</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

// Showcase: 3 телефона в ряд
const SceneShowcase: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const title = spring({ frame: f - 2, fps, config: { damping: 18 } });
  const srcs = ['app-map.png', 'app-form.png', 'app-request.png'];
  return (
    <AbsoluteFill style={{ background: `radial-gradient(100% 80% at 50% 0%, #14231b 0%, ${C.night} 70%)` }}>
      <div style={{ position: 'absolute', top: 46, left: 0, right: 0, textAlign: 'center', opacity: title, transform: `translateY(${interpolate(title, [0, 1], [24, 0])}px)`, zIndex: 5 }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 66, color: C.white }}>Юлдаш изнутри</div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 33, color: C.glow, marginTop: 10 }}>Карта · заявка · условия и отклики</div>
      </div>
      <AbsoluteFill style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 56, paddingTop: 250 }}>
        {srcs.map((src, i) => {
          const s = spring({ frame: f - 10 - i * 8, fps, config: { damping: 16 } });
          return (
            <div key={i} style={{ opacity: s, transform: `translateY(${interpolate(s, [0, 1], [60, 0])}px)` }}>
              <Mockup src={src} height={700} />
            </div>
          );
        })}
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const SceneCTA: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - 6, fps, config: { damping: 15 } });
  const btn = spring({ frame: f - 20, fps, config: { damping: 13 } });
  return (
    <AbsoluteFill>
      <RoadBg opacity={0.3} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 24 }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 110, color: C.white, letterSpacing: -2, opacity: s }}>Доедем вместе</div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 46, color: C.mint, opacity: s }}>Скачай Юлдаш — присоединяйся к своим</div>
        <div style={{ marginTop: 22, display: 'flex', alignItems: 'center', gap: 16, background: C.green, borderRadius: 44, padding: '26px 54px', opacity: btn, transform: `scale(${interpolate(btn, [0, 1], [0.85, 1])})`, boxShadow: `0 20px 60px ${C.green}66` }}>
          <svg width="40" height="40" viewBox="0 0 24 24" fill={C.night} aria-hidden="true"><path d="M3 20.5 13.5 12 3 3.5C2.7 3.7 2.5 4.1 2.5 4.6v14.8c0 .5.2.9.5 1.1Zm12.3-7 2.7 2.7-9.6 5.5 6.9-8.2Zm0-3-6.9-8.2 9.6 5.5-2.7 2.7ZM20.5 12c.6.4.9 1 .9 1.6 0 .6-.3 1.2-.9 1.6l-2 1.1-3-2.7 3-2.7 2 1.1Z" /></svg>
          <span style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 44, color: C.night }}>Скачать на Android</span>
        </div>
        <div style={{ fontFamily: BODY, fontWeight: 700, fontSize: 40, color: C.gold, marginTop: 10, opacity: btn }}>yulbash.ru</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

export const PromoWide: React.FC = () => {
  return (
    <AbsoluteFill style={{ background: C.night }}>
      <Sequence durationInFrames={90}><Fade dur={90}><SceneIntro /></Fade></Sequence>
      <Sequence from={90} durationInFrames={110}><Fade dur={110}><SceneRoad /></Fade></Sequence>
      <Sequence from={200} durationInFrames={150}><Fade dur={150}><SceneShowcase /></Fade></Sequence>
      <Sequence from={350} durationInFrames={100}><Fade dur={100}><SceneCTA /></Fade></Sequence>
    </AbsoluteFill>
  );
};
