// Лёгкие inline-иконки (stroke=currentColor), без внешних зависимостей.
type P = { size?: number };
const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.9,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
});

export const IconMap = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2Z" />
    <path d="M9 4v14M15 6v14" />
  </svg>
);

export const IconRides = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <path d="M5 17H3v-5l2-5h11l3 5h1a1 1 0 0 1 1 1v4h-2" />
    <circle cx="7.5" cy="17.5" r="1.6" />
    <circle cx="16.5" cy="17.5" r="1.6" />
    <path d="M9 17h6" />
  </svg>
);

export const IconRequest = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <rect x="4" y="3" width="16" height="18" rx="2.5" />
    <path d="M8 8h8M8 12h8M8 16h5" />
  </svg>
);

export const IconChat = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <path d="M4 5h16v11H9l-5 4V5Z" />
    <path d="M8 9h8M8 12.5h5" />
  </svg>
);

export const IconProfile = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="8.5" r="3.6" />
    <path d="M4.5 20a7.5 7.5 0 0 1 15 0" />
  </svg>
);

export const IconArrow = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M5 12h13M13 6l6 6-6 6" />
  </svg>
);
