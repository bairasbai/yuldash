import React from 'react';
import {
  AbsoluteFill,
  Img,
  Sequence,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';

const GREEN = '#0B6B3A';
const GREEN_DARK = '#063A21';
const YELLOW = '#F2B705';
const WHITE = '#FFFFFF';
const MINT = '#CFEFD9';
const FONT = '"Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif';

// Плавное появление/исчезновение содержимого сцены (внутри Sequence frame = относительный).
const Fade: React.FC<{dur: number; children: React.ReactNode}> = ({dur, children}) => {
  const f = useCurrentFrame();
  const o = interpolate(f, [0, 12, dur - 12, dur], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  return <AbsoluteFill style={{opacity: o}}>{children}</AbsoluteFill>;
};

const Logo: React.FC<{size?: number}> = ({size = 360}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame: f, fps, config: {damping: 13, mass: 0.7}});
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        background: WHITE,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transform: `scale(${s})`,
        boxShadow: '0 30px 90px rgba(0,0,0,0.35)',
      }}
    >
      <Img src={staticFile('logo.png')} style={{width: size * 0.75, height: size * 0.75}} />
    </div>
  );
};

// Сцена 1 — лого + слово
const SceneIntro: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const w = spring({frame: f - 16, fps, config: {damping: 14}});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 46}}>
      <Logo />
      <div style={{opacity: w, transform: `translateY(${interpolate(w, [0, 1], [40, 0])}px)`, textAlign: 'center'}}>
        <div style={{color: WHITE, fontFamily: FONT, fontSize: 140, fontWeight: 900, letterSpacing: -3}}>Юлдаш</div>
        <div style={{color: MINT, fontFamily: FONT, fontSize: 46, fontWeight: 600, marginTop: 6}}>Поездки между своими</div>
      </div>
    </AbsoluteFill>
  );
};

// Сцена 2 — маршрут рисуется + заголовок
const SceneRoute: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const p = interpolate(f, [6, 70], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const d = 'M 150 1650 C 380 1350, 820 1430, 560 1080 S 250 720, 840 460';
  const head = spring({frame: f - 4, fps, config: {damping: 16}});
  return (
    <AbsoluteFill>
      <svg width="1080" height="1920" style={{position: 'absolute', top: 0, left: 0}}>
        <path d={d} stroke="rgba(255,255,255,0.16)" strokeWidth={30} fill="none" strokeLinecap="round" />
        <path
          d={d}
          stroke={YELLOW}
          strokeWidth={14}
          fill="none"
          strokeLinecap="round"
          pathLength={1}
          strokeDasharray={1}
          strokeDashoffset={1 - p}
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          top: 240,
          left: 80,
          right: 80,
          opacity: head,
          transform: `translateY(${interpolate(head, [0, 1], [30, 0])}px)`,
        }}
      >
        <div style={{color: WHITE, fontFamily: FONT, fontSize: 92, fontWeight: 900, lineHeight: 1.05}}>
          Свой маршрут —<br />свои люди
        </div>
        <div style={{color: MINT, fontFamily: FONT, fontSize: 42, fontWeight: 500, marginTop: 18}}>
          Башкортостан, по-соседски
        </div>
      </div>
    </AbsoluteFill>
  );
};

const Chip: React.FC<{i: number; label: string}> = ({i, label}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame: f - 8 - i * 12, fps, config: {damping: 15}});
  return (
    <div
      style={{
        opacity: s,
        transform: `translateX(${interpolate(s, [0, 1], [90, 0])}px)`,
        background: WHITE,
        borderRadius: 30,
        padding: '34px 44px',
        display: 'flex',
        alignItems: 'center',
        gap: 28,
        boxShadow: '0 20px 50px rgba(0,0,0,0.22)',
      }}
    >
      <div style={{width: 26, height: 26, borderRadius: '50%', background: GREEN, flexShrink: 0}} />
      <div style={{color: GREEN_DARK, fontFamily: FONT, fontSize: 50, fontWeight: 800}}>{label}</div>
    </div>
  );
};

// Сцена 3 — три фишки доверия
const SceneFeatures: React.FC = () => (
  <AbsoluteFill style={{justifyContent: 'center', padding: 80, gap: 36}}>
    <Chip i={0} label="Скрытый номер" />
    <Chip i={1} label="Код посадки при встрече" />
    <Chip i={2} label="Проверенные водители" />
  </AbsoluteFill>
);

// Сцена 4 — финал / CTA
const SceneCTA: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame: f, fps, config: {damping: 14}});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 28}}>
      <Logo size={240} />
      <div style={{color: WHITE, fontFamily: FONT, fontSize: 120, fontWeight: 900, letterSpacing: -2, opacity: s}}>Юлдаш</div>
      <div style={{color: MINT, fontFamily: FONT, fontSize: 46, fontWeight: 600, opacity: s}}>Попутки между своими</div>
      <div
        style={{
          marginTop: 26,
          background: YELLOW,
          color: GREEN_DARK,
          fontFamily: FONT,
          fontSize: 44,
          fontWeight: 800,
          padding: '26px 56px',
          borderRadius: 40,
          opacity: s,
        }}
      >
        Скоро в Google Play
      </div>
    </AbsoluteFill>
  );
};

export const Promo: React.FC = () => {
  return (
    <AbsoluteFill>
      <AbsoluteFill style={{background: `linear-gradient(160deg, ${GREEN} 0%, ${GREEN_DARK} 100%)`}} />
      <Sequence durationInFrames={95}>
        <Fade dur={95}>
          <SceneIntro />
        </Fade>
      </Sequence>
      <Sequence from={95} durationInFrames={95}>
        <Fade dur={95}>
          <SceneRoute />
        </Fade>
      </Sequence>
      <Sequence from={190} durationInFrames={80}>
        <Fade dur={80}>
          <SceneFeatures />
        </Fade>
      </Sequence>
      <Sequence from={270} durationInFrames={60}>
        <Fade dur={60}>
          <SceneCTA />
        </Fade>
      </Sequence>
    </AbsoluteFill>
  );
};
