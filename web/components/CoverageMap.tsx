"use client";

import { useState } from "react";
import { motion, useReducedMotion } from "framer-motion";
import { AnimatePresence } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// Реальный контур Башкортостана, обведён по силуэту rb-map.png (425×549).
const CONTOUR =
  "M92,0 L100,6 L103,12 L109,12 L118,22 L126,19 L128,24 L130,18 L136,24 L143,24 L149,17 L158,14 L160,11 L169,12 L171,9 L175,10 L175,19 L184,31 L190,27 L195,28 L201,18 L206,22 L210,20 L212,21 L210,27 L218,32 L220,38 L224,37 L225,42 L232,49 L239,47 L242,43 L248,44 L250,42 L249,36 L254,33 L256,27 L259,28 L263,25 L265,27 L264,33 L269,40 L267,47 L275,52 L283,45 L292,46 L294,50 L303,50 L309,47 L315,51 L319,49 L321,52 L327,48 L329,43 L333,43 L337,48 L352,54 L359,52 L361,42 L374,47 L377,44 L380,45 L379,57 L372,59 L372,65 L377,69 L374,80 L368,84 L379,84 L381,86 L379,90 L381,104 L389,100 L391,106 L401,105 L402,110 L390,120 L374,122 L372,125 L373,137 L370,136 L362,145 L360,144 L361,139 L356,136 L353,139 L357,142 L345,142 L344,146 L348,149 L346,157 L354,166 L347,175 L334,175 L332,169 L328,172 L327,167 L322,162 L323,158 L319,154 L315,154 L309,159 L313,162 L313,166 L308,171 L309,177 L305,182 L297,181 L296,178 L302,169 L299,163 L299,154 L306,152 L308,149 L303,146 L302,142 L279,140 L271,136 L265,137 L258,145 L254,145 L255,148 L252,150 L254,156 L246,162 L249,166 L249,173 L255,176 L246,188 L255,198 L264,203 L266,202 L269,207 L273,205 L274,210 L286,218 L285,226 L289,227 L295,236 L300,239 L313,226 L319,225 L325,219 L338,229 L342,229 L349,219 L361,216 L369,209 L380,212 L395,196 L396,188 L401,185 L403,180 L413,186 L424,182 L424,186 L420,191 L422,196 L418,212 L406,223 L406,227 L411,231 L408,242 L415,251 L410,257 L411,265 L408,266 L398,260 L385,261 L376,282 L371,285 L361,286 L360,304 L357,308 L359,319 L354,324 L354,328 L360,330 L358,335 L358,366 L361,368 L361,383 L365,384 L366,388 L357,389 L357,395 L351,405 L354,424 L350,431 L357,437 L357,442 L353,445 L353,448 L358,453 L359,461 L366,462 L361,474 L350,473 L349,470 L346,472 L346,479 L343,484 L345,491 L349,493 L349,497 L345,499 L346,512 L341,526 L324,526 L317,531 L310,532 L306,524 L299,524 L296,519 L289,521 L280,517 L277,530 L268,532 L261,530 L259,539 L247,548 L243,548 L240,540 L232,536 L231,530 L222,528 L219,529 L223,532 L225,543 L215,546 L215,526 L206,519 L206,513 L213,508 L209,503 L213,495 L210,491 L206,493 L205,490 L208,487 L205,484 L199,482 L196,484 L194,479 L188,486 L183,486 L180,483 L180,476 L196,465 L195,455 L197,453 L192,452 L190,445 L196,434 L194,433 L186,440 L173,427 L170,439 L167,439 L164,444 L160,440 L159,451 L161,454 L156,460 L148,460 L147,454 L146,458 L136,454 L134,452 L136,449 L134,447 L136,434 L131,433 L130,426 L127,424 L120,429 L117,421 L118,417 L123,418 L124,421 L129,419 L125,415 L125,412 L129,410 L128,405 L117,402 L115,399 L111,401 L111,398 L106,394 L105,385 L99,376 L99,364 L93,359 L80,363 L80,359 L75,356 L76,348 L61,339 L57,344 L53,344 L45,335 L43,325 L36,318 L33,307 L20,297 L17,276 L10,252 L9,229 L13,218 L13,208 L18,205 L26,193 L24,184 L27,174 L21,175 L17,167 L18,164 L9,159 L6,151 L2,150 L0,145 L13,143 L17,137 L27,139 L27,135 L35,127 L46,123 L55,105 L66,97 L69,91 L65,90 L59,82 L55,83 L55,79 L51,80 L52,75 L49,70 L40,68 L38,61 L46,51 L56,54 L66,37 L78,31 L81,26 L79,18 L86,14 L91,1 Z";

// Узлы городов в координатах реальной карты РБ (rb-map.png, 425×549).
type Node = { x: number; y: number; ru: string; ba: string; hub?: boolean };
// Координаты откалиброваны по детальной карте районов РБ (доли габаритов формы).
const NODES: Node[] = [
  { x: 174, y: 252, ru: "Уфа", ba: "Өфө", hub: true },
  { x: 77, y: 78, ru: "Нефтекамск", ba: "Нефтекама" },
  { x: 153, y: 170, ru: "Бирск", ba: "Бөрө" },
  { x: 74, y: 282, ru: "Туймазы", ba: "Туймазы" },
  { x: 162, y: 346, ru: "Стерлитамак", ba: "Стәрлетамаҡ" },
  { x: 170, y: 374, ru: "Салават", ba: "Салауат" },
  { x: 158, y: 450, ru: "Кумертау", ba: "Күмертау" },
  { x: 270, y: 314, ru: "Белорецк", ba: "Белорет" },
  { x: 376, y: 260, ru: "Учалы", ba: "Учалы" },
  { x: 338, y: 420, ru: "Сибай", ba: "Сибай" },
  { x: 308, y: 436, ru: "Баймак", ba: "Баймаҡ" },
];

// ViewBox карты (см. svg ниже) — для пересчёта координат узла в проценты контейнера
const VB = { minX: -14, minY: -16, w: 453, h: 581 };
// Два ближайших города — «популярные направления» для всплывающей карточки
function nearestTwo(i: number) {
  return NODES.map((n, j) => ({ j, d: (n.x - NODES[i].x) ** 2 + (n.y - NODES[i].y) ** 2 }))
    .filter((o) => o.j !== i)
    .sort((a, b) => a.d - b.d)
    .slice(0, 2)
    .map((o) => NODES[o.j]);
}

export function CoverageMap() {
  const { tr, lang } = useLang();
  const reduce = useReducedMotion();
  const hub = NODES[0];
  const [hovered, setHovered] = useState<number | null>(null);
  const name = (n: Node) => (lang === "ba" ? n.ba : n.ru);

  return (
    <section id="coverage" className="relative px-6 py-24">
      <div className="mx-auto max-w-5xl">
        <Reveal className="mx-auto mb-12 max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{tr("cov_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("cov_sub")}</p>
        </Reveal>

        <Reveal>
          <div className="glass relative mx-auto max-w-xl overflow-hidden rounded-canon p-6 sm:p-10">
            <div className="pointer-events-none absolute left-1/2 top-1/2 h-72 w-72 -translate-x-1/2 -translate-y-1/2 rounded-full bg-green-bright/15 blur-[100px]" />
            <div className="relative mx-auto w-full max-w-md">
            <svg viewBox="-14 -16 453 581" className="block w-full">
              <defs>
                {/* мягкое свечение для контура и сигналов */}
                <filter id="covGlow" x="-30%" y="-30%" width="160%" height="160%">
                  <feGaussianBlur stdDeviation="2.4" result="b" />
                  <feMerge>
                    <feMergeNode in="b" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
                <linearGradient id="covEdge" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0%" stopColor="#7FE3AB" />
                  <stop offset="55%" stopColor="#E8C36B" />
                  <stop offset="100%" stopColor="#7FE3AB" />
                </linearGradient>
              </defs>

              {/* реальный контур Башкортостана */}
              <image href="/rb-map.png" x="0" y="0" width="425" height="549" opacity="0.85" />

              {/* обводка контура — прорисовка при появлении */}
              <motion.path
                d={CONTOUR}
                fill="none"
                stroke="url(#covEdge)"
                strokeWidth="2"
                strokeLinejoin="round"
                strokeLinecap="round"
                filter="url(#covGlow)"
                initial={{ pathLength: reduce ? 1 : 0, opacity: reduce ? 0.55 : 0 }}
                whileInView={{ pathLength: 1, opacity: 0.55 }}
                viewport={{ once: true }}
                transition={{ duration: 2.2, ease: "easeInOut" }}
              />

              {/* бегущие «кометы» по контуру (две, со сдвигом фазы) */}
              {!reduce && (
                <>
                  <path
                    d={CONTOUR}
                    fill="none"
                    stroke="#F4D98B"
                    strokeWidth="2.6"
                    strokeLinecap="round"
                    filter="url(#covGlow)"
                    pathLength={1}
                    strokeDasharray="0.05 0.95"
                    className="coverage-runner"
                  />
                  <path
                    d={CONTOUR}
                    fill="none"
                    stroke="#9CF0C2"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    filter="url(#covGlow)"
                    pathLength={1}
                    strokeDasharray="0.04 0.96"
                    className="coverage-runner coverage-runner-2"
                  />
                </>
              )}

              {/* линии маршрутов от Уфы */}
              {NODES.slice(1).map((n, i) => (
                <motion.line
                  key={`l${i}`}
                  x1={hub.x}
                  y1={hub.y}
                  x2={n.x}
                  y2={n.y}
                  stroke="#E8C36B"
                  strokeOpacity="0.5"
                  strokeWidth="1.6"
                  strokeDasharray="3 4"
                  initial={{ pathLength: 0, opacity: 0 }}
                  whileInView={{ pathLength: 1, opacity: 1 }}
                  viewport={{ once: true }}
                  transition={{ duration: 0.9, delay: 0.25 + i * 0.06, ease: "easeOut" }}
                />
              ))}

              {/* сигналы-импульсы, летящие от Уфы к городам */}
              {!reduce &&
                NODES.slice(1).map((n, i) => (
                  <circle key={`p${i}`} r="2.8" fill="#F4D98B" filter="url(#covGlow)">
                    <animateMotion
                      dur="2.6s"
                      begin={`${(i * 0.24).toFixed(2)}s`}
                      repeatCount="indefinite"
                      path={`M${hub.x},${hub.y} L${n.x},${n.y}`}
                    />
                    <animate
                      attributeName="opacity"
                      values="0;1;1;0"
                      keyTimes="0;0.12;0.8;1"
                      dur="2.6s"
                      begin={`${(i * 0.24).toFixed(2)}s`}
                      repeatCount="indefinite"
                    />
                  </circle>
                ))}

              {/* узлы городов */}
              {NODES.map((n, i) => {
                const rightSide = n.x > 230;
                return (
                  <motion.g
                    key={`n${i}`}
                    initial={{ scale: 0, opacity: 0 }}
                    whileInView={{ scale: 1, opacity: 1 }}
                    viewport={{ once: true }}
                    transition={{ delay: 0.3 + i * 0.05, type: "spring", stiffness: 300, damping: 18 }}
                    className="cursor-pointer"
                    onMouseEnter={() => setHovered(i)}
                    onMouseLeave={() => setHovered(null)}
                    onClick={() => setHovered((h) => (h === i ? null : i))}
                  >
                    {/* увеличенная зона наведения */}
                    <circle cx={n.x} cy={n.y} r={16} fill="transparent" />
                    {/* пульс-кольцо на каждом узле */}
                    {!reduce && (
                      <circle cx={n.x} cy={n.y} r={n.hub ? 7 : 4.5} fill="none" stroke={n.hub ? "#E8C36B" : "#7FE3AB"} strokeWidth="1.4">
                        <animate attributeName="r" values={n.hub ? "7;20;7" : "4.5;12;4.5"} dur={n.hub ? "2.6s" : "3s"} begin={`${(i * 0.2).toFixed(2)}s`} repeatCount="indefinite" />
                        <animate attributeName="opacity" values="0.7;0;0.7" dur={n.hub ? "2.6s" : "3s"} begin={`${(i * 0.2).toFixed(2)}s`} repeatCount="indefinite" />
                      </circle>
                    )}
                    <circle cx={n.x} cy={n.y} r={n.hub ? 6.5 : 4.5} fill={n.hub ? "#E8C36B" : "#7FE3AB"} stroke="#0a1410" strokeWidth="2" />
                    <text
                      x={n.x + (rightSide ? -9 : 9)}
                      y={n.y + 4}
                      fontSize="13"
                      fontWeight="700"
                      textAnchor={rightSide ? "end" : "start"}
                      fill={n.hub ? "#E8C36B" : "rgba(234,242,236,0.85)"}
                      style={{ paintOrder: "stroke" }}
                      stroke="#0a1410"
                      strokeWidth="3"
                      strokeLinejoin="round"
                    >
                      {lang === "ba" ? n.ba : n.ru}
                    </text>
                  </motion.g>
                );
              })}
            </svg>

            {/* Всплывающая карточка популярных направлений */}
            <AnimatePresence>
              {hovered !== null && (
                <motion.div
                  key={hovered}
                  initial={{ opacity: 0, y: 6, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 6, scale: 0.96 }}
                  transition={{ duration: 0.18, ease: [0.21, 0.47, 0.32, 0.98] }}
                  className="glass pointer-events-none absolute z-20 w-44 -translate-x-1/2 -translate-y-[115%] rounded-2xl px-3.5 py-3 text-left shadow-card"
                  style={{
                    left: `${((NODES[hovered].x - VB.minX) / VB.w) * 100}%`,
                    top: `${((NODES[hovered].y - VB.minY) / VB.h) * 100}%`,
                  }}
                >
                  <div className="font-display text-sm font-extrabold text-white">{name(NODES[hovered])}</div>
                  <div className="mt-1.5 text-[11px] font-semibold uppercase tracking-wide text-green-glow/80">
                    {tr("cov_pop")}
                  </div>
                  <div className="mt-1 space-y-1">
                    {nearestTwo(hovered).map((d) => (
                      <div key={d.ru} className="flex items-center gap-1.5 text-xs text-white/75">
                        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="text-green-bright">
                          <path d="M5 12h14M13 6l6 6-6 6" />
                        </svg>
                        {name(d)}
                      </div>
                    ))}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
