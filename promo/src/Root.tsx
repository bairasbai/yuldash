import React from 'react';
import { Composition } from 'remotion';
import { Promo } from './Promo';
import { PromoWide } from './PromoWide';

export const RemotionRoot: React.FC = () => {
  return (
    <>
      {/* Вертикаль 9:16 — Reels / Shorts / Telegram / hero-луп */}
      <Composition id="PromoPortrait" component={Promo} durationInFrames={648} fps={30} width={1080} height={1920} />
      {/* Горизонталь 16:9 — сайт / YouTube */}
      <Composition id="PromoWide" component={PromoWide} durationInFrames={450} fps={30} width={1920} height={1080} />
    </>
  );
};
