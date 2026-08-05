/**
 * Настроение дня. Пять состояний вместо шкалы «от 1 до 10»:
 * человек не измеряет себя цифрой, он говорит словом.
 */

export type MoodOption = {
  value: string;
  label: string;
  /** Цвет точки в истории за месяц. */
  color: string;
  /** Стоит ли Байрасу об этом сообщить сразу. */
  alert?: boolean;
};

export const MOODS: MoodOption[] = [
  { value: "light", label: "светло", color: "#ffd9a8" },
  { value: "calm", label: "спокойно", color: "#a8c8e8" },
  { value: "miss", label: "скучаю", color: "#b7a6d8" },
  { value: "tired", label: "устала", color: "#8b93a8" },
  { value: "hard", label: "тяжело", color: "#d98b8b", alert: true },
];

export function moodByValue(value: string): MoodOption | undefined {
  return MOODS.find((m) => m.value === value);
}
