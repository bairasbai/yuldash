// Бренд Юлдаш — синхронизирован с лендингом (globals.css / Theme.kt).
export const C = {
  night: '#0A1410',
  forest: '#0F1613',
  card: '#141d19',
  green: '#2FB36E',
  greenDeep: '#0E8247',
  glow: '#7FE3AB',
  gold: '#E8C36B',
  goldSoft: '#FFE3A1',
  white: '#EAF2EC',
  mint: '#CFEFD9',
  mute: 'rgba(234,242,236,0.55)',
} as const;

// Шрифты (совпадают с лендингом): Unbounded — заголовки, Inter — текст.
import { loadFont as loadDisplay } from '@remotion/google-fonts/Unbounded';
import { loadFont as loadBody } from '@remotion/google-fonts/Inter';

export const DISPLAY = loadDisplay('normal', { weights: ['700', '800'], subsets: ['cyrillic', 'latin'] }).fontFamily;
export const BODY = loadBody('normal', { weights: ['400', '500', '600', '700'], subsets: ['cyrillic', 'latin'] }).fontFamily;
