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

export const IconCheck = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M5 12.5l4.5 4.5L19 6.5" />
  </svg>
);

export const IconChevron = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M9 6l6 6-6 6" />
  </svg>
);

export const IconShield = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6l7-3Z" />
    <path d="M9 12l2 2 4-4" />
  </svg>
);

export const IconStar = ({ size = 18 }: P) => (
  <svg {...base(size)} fill="currentColor" stroke="none">
    <path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 17l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9L12 3.5Z" />
  </svg>
);

export const IconCopy = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <rect x="9" y="9" width="11" height="11" rx="2.5" />
    <path d="M6 15H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" />
  </svg>
);

export const IconShare = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M12 3v12" />
    <path d="M8 7l4-4 4 4" />
    <path d="M6 12v7a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-7" />
  </svg>
);

export const IconGift = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <rect x="4" y="9" width="16" height="11" rx="2" />
    <path d="M4 12h16M12 9v11" />
    <path d="M12 9S9.5 4 7.5 5.2 9.5 9 12 9Zm0 0s2.5-5 4.5-3.8S14.5 9 12 9Z" />
  </svg>
);

export const IconLogout = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M15 5V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h7a2 2 0 0 0 2-2v-1" />
    <path d="M10 12h11M18 9l3 3-3 3" />
  </svg>
);

export const IconTelegram = ({ size = 22 }: P) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
    <path d="M21.6 4.3 2.9 11.5c-1 .4-1 1 0 1.3l4.6 1.4 1.8 5.6c.2.6.4.8.9.8.4 0 .6-.2.9-.5l2.4-2.3 4.7 3.5c.9.5 1.5.2 1.7-.8l3.1-14.6c.3-1.2-.5-1.8-1.4-1.5Zm-3.6 3.4-8.4 7.6-.3 3.4-1.6-5 10-6.5c.5-.3.9 0 .3.5Z" />
  </svg>
);
