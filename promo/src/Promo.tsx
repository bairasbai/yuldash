import React from 'react';
import {
  AbsoluteFill,
<<<<<<< Updated upstream
  Img,
  OffthreadVideo,
  Sequence,
=======
  Easing,
>>>>>>> Stashed changes
  interpolate,
  useCurrentFrame,
} from 'remotion';
import { C, DISPLAY, BODY } from './theme';
import { Kicker, ScreenMap, ScreenForm, ScreenRequest } from './screens';

<<<<<<< Updated upstream
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

// Логотип-знак без «блюдца»: сам пин + мягкое свечение-ореол за ним + тень.
// size = ВЫСОТА знака. Знак читается на тёмном сам (жирный контур) — за ним лишь
// лёгкий подсвет для отделения от фона.
const Logo: React.FC<{ size?: number }> = ({ size = 330 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f, fps, config: { damping: 12, mass: 0.7 } });
  return (
    <div style={{ position: 'relative', transform: `scale(${s})`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ position: 'absolute', width: size * 1.25, height: size * 1.25, borderRadius: '50%', background: 'radial-gradient(circle, rgba(255,255,255,0.42) 0%, rgba(255,255,255,0.12) 42%, rgba(255,255,255,0) 68%)', filter: 'blur(22px)' }} />
      <Img src={staticFile('logo.png')} style={{ position: 'relative', height: size, width: 'auto', filter: 'drop-shadow(0 12px 30px rgba(0,0,0,0.5))' }} />
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

// Обёртка для сцен с телефоном-mockup (у скриншота своя рамка → просто центрируем)
const PhoneScene: React.FC<{ title: string; sub: string; children: React.ReactNode }> = ({ title, sub, children }) => (
  <AbsoluteFill style={{ background: `radial-gradient(120% 80% at 50% 0%, #14231b 0%, ${C.night} 70%)` }}>
    <Kicker title={title} sub={sub} top={130} />
    <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'flex-end', paddingBottom: 30 }}>
      {children}
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
        <Logo size={260} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 92, color: C.white, letterSpacing: -2, opacity: s }}>Доедем вместе</div>
        <div style={{ fontFamily: BODY, fontWeight: 500, fontSize: 42, color: C.mint, opacity: s }}>Скачай Юлдаш — присоединяйся к своим</div>
        <div style={{ marginTop: 24, display: 'flex', alignItems: 'center', gap: 16, background: C.green, borderRadius: 44, padding: '28px 56px', opacity: btn, transform: `scale(${interpolate(btn, [0, 1], [0.85, 1])})`, boxShadow: `0 20px 60px ${C.green}66` }}>
          <svg width="42" height="42" viewBox="0 0 24 24" fill={C.night} aria-hidden="true"><path d="M3 20.5 13.5 12 3 3.5C2.7 3.7 2.5 4.1 2.5 4.6v14.8c0 .5.2.9.5 1.1Zm12.3-7 2.7 2.7-9.6 5.5 6.9-8.2Zm0-3-6.9-8.2 9.6 5.5-2.7 2.7ZM20.5 12c.6.4.9 1 .9 1.6 0 .6-.3 1.2-.9 1.6l-2 1.1-3-2.7 3-2.7 2 1.1Z" /></svg>
          <span style={{ fontFamily: DISPLAY, fontWeight: 800, fontSize: 46, color: C.night }}>Скачать на Android</span>
        </div>
        <div style={{ fontFamily: BODY, fontWeight: 700, fontSize: 40, color: C.gold, marginTop: 12, opacity: btn }}>yulbash.ru</div>
      </AbsoluteFill>
    </AbsoluteFill>
=======
const C = {
  night: '#07100D',
  deep: '#0D1718',
  ivory: '#F5EFE3',
  muted: 'rgba(245,239,227,0.68)',
  brass: '#D58B26',
  steel: '#7EA3B6',
  green: '#176640',
};

const FONT = '"Segoe UI", Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif';
const ease = Easing.bezier(0.16, 1, 0.3, 1);

const clamp = {
  extrapolateLeft: 'clamp' as const,
  extrapolateRight: 'clamp' as const,
};

const rise = (frame: number, start: number, duration = 38) =>
  interpolate(frame, [start, start + duration], [0, 1], { ...clamp, easing: ease });

const RouteScene: React.FC = () => {
  const frame = useCurrentFrame();
  const primary = interpolate(frame, [22, 138], [0, 1], { ...clamp, easing: ease });
  const secondary = interpolate(frame, [72, 184], [0, 1], { ...clamp, easing: ease });
  const car = interpolate(frame, [64, 232], [0, 1], { ...clamp, easing: Easing.inOut(Easing.cubic) });

  return (
    <svg width="1920" height="1080" style={{ position: 'absolute', inset: 0 }}>
      <defs>
        <linearGradient id="bgRoute" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor={C.night} />
          <stop offset="100%" stopColor={C.deep} />
        </linearGradient>
        <linearGradient id="mainRoute" x1="0" x2="1">
          <stop offset="0%" stopColor={C.ivory} stopOpacity="0.12" />
          <stop offset="52%" stopColor={C.brass} stopOpacity="0.86" />
          <stop offset="100%" stopColor={C.steel} stopOpacity="0.64" />
        </linearGradient>
      </defs>
      <rect width="1920" height="1080" fill="url(#bgRoute)" />
      {Array.from({ length: 12 }).map((_, i) => (
        <path
          key={i}
          d={`M${-160 + i * 210} 1120 C ${40 + i * 180} 820, ${300 + i * 90} 420, ${420 + i * 135} -90`}
          fill="none"
          stroke="rgba(245,239,227,0.045)"
          strokeWidth="2"
        />
      ))}
      <path d="M110 760 C 360 570, 575 670, 725 470 S 1088 210, 1770 294" fill="none" stroke="rgba(245,239,227,0.12)" strokeWidth="42" strokeLinecap="round" />
      <path
        d="M110 760 C 360 570, 575 670, 725 470 S 1088 210, 1770 294"
        fill="none"
        stroke="url(#mainRoute)"
        strokeWidth="9"
        strokeLinecap="round"
        pathLength={1}
        strokeDasharray={1}
        strokeDashoffset={1 - primary}
      />
      <path
        d="M260 850 C 470 725, 568 520, 780 560 S 1120 670, 1355 480"
        fill="none"
        stroke={C.steel}
        strokeWidth="4"
        strokeLinecap="round"
        pathLength={1}
        strokeDasharray={1}
        strokeDashoffset={1 - secondary}
        opacity="0.72"
      />
      {[{ x: 110, y: 760, label: 'Уфа' }, { x: 725, y: 470, label: 'Стерлитамак' }, { x: 1770, y: 294, label: 'Сибай' }].map((point, index) => (
        <g key={point.label} opacity={rise(frame, 70 + index * 18)} transform={`translate(${point.x} ${point.y})`}>
          <circle r="18" fill={index === 2 ? C.brass : C.green} />
          <circle r="34" fill="none" stroke={index === 2 ? C.brass : C.green} strokeWidth="2" opacity="0.42" />
          <text x="0" y="-48" textAnchor="middle" fill={C.ivory} fontFamily={FONT} fontSize="26" fontWeight="800">
            {point.label}
          </text>
        </g>
      ))}
      <g transform={`translate(${interpolate(car, [0, 1], [110, 1770])} ${interpolate(car, [0, 1], [760, 294])})`}>
        <circle r="24" fill={C.brass} />
        <path d="M-9 3h18M-4-6h8M-14 3l5-10h18l5 10" stroke={C.night} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      </g>
    </svg>
>>>>>>> Stashed changes
  );
};

export const Promo: React.FC = () => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 20, 282, 300], [0, 1, 1, 0], clamp);

  return (
<<<<<<< Updated upstream
    <AbsoluteFill style={{ background: C.night }}>
      <Sequence durationInFrames={92}><Fade dur={92}><SceneIntro /></Fade></Sequence>
      <Sequence from={92} durationInFrames={120}><Fade dur={120}><SceneRoad /></Fade></Sequence>
      <Sequence from={212} durationInFrames={120}><Fade dur={120}><PhoneScene title="Карта поездок" sub="Земляки уже в пути · SOS рядом"><ScreenMap /></PhoneScene></Fade></Sequence>
      <Sequence from={332} durationInFrames={110}><Fade dur={110}><PhoneScene title="Заявка и условия" sub="Телефон скрыт · условия поездки"><ScreenForm /></PhoneScene></Fade></Sequence>
      <Sequence from={442} durationInFrames={110}><Fade dur={110}><PhoneScene title="Заявки и отклики" sub="Оставь заявку — свои откликнутся"><ScreenRequest /></PhoneScene></Fade></Sequence>
      <Sequence from={552} durationInFrames={96}><Fade dur={96}><SceneCTA /></Fade></Sequence>
=======
    <AbsoluteFill style={{ background: C.night, overflow: 'hidden', opacity }}>
      <RouteScene />
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(90deg, rgba(7,16,13,0.58) 0%, rgba(7,16,13,0.22) 48%, rgba(7,16,13,0.08) 100%)' }} />
>>>>>>> Stashed changes
    </AbsoluteFill>
  );
};
