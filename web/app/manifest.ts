import type { MetadataRoute } from "next";

export const dynamic = "force-static";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Юлдаш — попутки между своими",
    short_name: "Юлдаш",
    description: "Попутки по Башкортостану между своими. Доверие, тёплые поездки и честная цена.",
    start_url: "/",
    display: "standalone",
    background_color: "#0A1410",
    theme_color: "#0A1410",
    lang: "ru",
    icons: [
      { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
