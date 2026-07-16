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

export const IconSettings = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="12" r="3.2" />
    <path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5 5l2.1 2.1M16.9 16.9 19 19M19 5l-2.1 2.1M7.1 16.9 5 19" />
  </svg>
);

export const IconBlock = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="12" r="9" />
    <path d="M5.6 5.6l12.8 12.8" />
  </svg>
);

export const IconFlag = ({ size = 24 }: P) => (
  <svg {...base(size)}>
    <path d="M5 21V4M5 4h11l-1.5 3.5L16 11H5" />
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

export const IconHome = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M4 11l8-6 8 6" />
    <path d="M6 10v9a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-9" />
    <path d="M10 20v-5h4v5" />
  </svg>
);

export const IconWork = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <rect x="3" y="7" width="18" height="13" rx="2" />
    <path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
    <path d="M3 12h18" />
  </svg>
);

export const IconPin = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M12 21s7-6 7-11a7 7 0 0 0-14 0c0 5 7 11 7 11Z" />
    <circle cx="12" cy="10" r="2.6" />
  </svg>
);

export const IconTrash = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M4 7h16" />
    <path d="M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
    <path d="M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13" />
    <path d="M10 11v6M14 11v6" />
  </svg>
);

export const IconBell = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M6 9a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6Z" />
    <path d="M10 20a2 2 0 0 0 4 0" />
  </svg>
);

export const IconWallet = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
    <path d="M16 12h4v-2h-4a1 1 0 0 0 0 2Z" />
  </svg>
);

export const IconReceipt = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M6 3h12v18l-3-2-3 2-3-2-3 2Z" />
    <path d="M9 8h6M9 12h6" />
  </svg>
);

export const IconClock = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 7.5V12l3 2" />
  </svg>
);

export const IconFilter = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M4 5h16l-6 8v5l-4 2v-7L4 5Z" />
  </svg>
);

export const IconRoute = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <circle cx="6" cy="18" r="2.4" />
    <circle cx="18" cy="6" r="2.4" />
    <path d="M8 17.5c6 0 8-2 8-6.5" />
  </svg>
);

export const IconHospital = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <rect x="4" y="4" width="16" height="16" rx="2" />
    <path d="M12 8v8M8 12h8" />
  </svg>
);

export const IconTrend = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M4 16l5-5 3 3 7-7" />
    <path d="M16 7h4v4" />
  </svg>
);

export const IconRocket = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M12 3c3.5 1.5 5.5 4.5 5.5 8.5L15 14H9l-2.5-2.5C6.5 7.5 8.5 4.5 12 3Z" />
    <circle cx="12" cy="9.5" r="1.6" />
    <path d="M9 14l-2 4M15 14l2 4M12 15v4" />
  </svg>
);

export const IconCalendar = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <rect x="4" y="5" width="16" height="16" rx="2.5" />
    <path d="M4 9h16M8 3v4M16 3v4" />
  </svg>
);

export const IconCamera = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z" />
    <circle cx="12" cy="13" r="3.2" />
  </svg>
);

export const IconWheel = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="2.6" />
    <path d="M12 14.6V21M9.7 11.2 4.2 8M14.3 11.2 19.8 8" />
  </svg>
);

export const IconPower = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M12 3v9" />
    <path d="M7.5 6.5a7 7 0 1 0 9 0" />
  </svg>
);

export const IconCar = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M3 13l1.8-5.1A2 2 0 0 1 6.7 6.5h10.6a2 2 0 0 1 1.9 1.4L21 13v5a1 1 0 0 1-1 1h-1.5a1 1 0 0 1-1-1v-1H6.5v1a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
    <path d="M3.5 13h17" />
    <circle cx="7.5" cy="16.5" r="0.6" />
    <circle cx="16.5" cy="16.5" r="0.6" />
  </svg>
);

export const IconPhone = ({ size = 20 }: P) => (
  <svg {...base(size)}>
    <path d="M5 4h3l1.5 4-2 1.4a12 12 0 0 0 5.1 5.1L19 16l-1 3-1 1c-7 0-13-6-13-13z" />
  </svg>
);

export const IconBox = ({ size = 22 }: P) => (
  <svg {...base(size)}>
    <path d="M21 8.2 12 3 3 8.2v7.6L12 21l9-5.2z" />
    <path d="M3.3 8 12 13l8.7-5" />
    <path d="M12 13v8" />
    <path d="M7.5 5.6 16.5 10.8" />
  </svg>
);
