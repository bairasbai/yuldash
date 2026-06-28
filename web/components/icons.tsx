// Единый набор иконок (Lucide-стиль, stroke 2, без эмодзи).
type P = { className?: string; size?: number };
const base = (size = 24) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
});

export const BadgeCheck = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z" />
    <path d="m9 12 2 2 4-4" />
  </svg>
);

export const Star = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 2.5l2.9 5.9 6.5.95-4.7 4.58 1.1 6.47L12 17.9l-5.8 3.05 1.1-6.47-4.7-4.58 6.5-.95Z" />
  </svg>
);

export const LifeBuoy = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="12" cy="12" r="10" />
    <circle cx="12" cy="12" r="4" />
    <path d="m4.9 4.9 4.2 4.2m5.8 5.8 4.2 4.2m0-14.2-4.2 4.2m-5.8 5.8-4.2 4.2" />
  </svg>
);

export const Lock = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <rect x="3.5" y="11" width="17" height="10.5" rx="2.5" />
    <path d="M7.5 11V7a4.5 4.5 0 0 1 9 0v4" />
  </svg>
);

export const ShieldHeart = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
    <path d="M12 13.7s-2.6-1.6-2.6-3.3c0-.9.8-1.6 1.6-1.6.6 0 1 .4 1 .4s.4-.4 1-.4c.8 0 1.6.7 1.6 1.6 0 1.7-2.6 3.3-2.6 3.3Z" />
  </svg>
);

export const Tag = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.83 0L2.5 12.5V4.5a2 2 0 0 1 2-2h8l8.1 8.07a2 2 0 0 1 0 2.83Z" />
    <circle cx="7.5" cy="7.5" r="1.4" fill="currentColor" stroke="none" />
  </svg>
);

export const UserGlyph = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <circle cx="12" cy="8.5" r="3.8" />
    <path d="M4.5 20a7.5 7.5 0 0 1 15 0" />
  </svg>
);

export const Megaphone = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="m3 11 14-6v14L3 13v-2Z" />
    <path d="M17 8a3 3 0 0 1 0 8" />
    <path d="M6 13v4a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-2" />
  </svg>
);

export const Handshake = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M11 17 9.5 15.5" />
    <path d="m3 11 4-4 5 4 2-2 7 5-3 4-3-2-3 3-3-2-3 3-3-3 1-4Z" />
  </svg>
);

export const Spark = ({ className, size }: P) => (
  <svg {...base(size)} className={className}>
    <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M18 6l-2.5 2.5M8.5 15.5 6 18" />
  </svg>
);
