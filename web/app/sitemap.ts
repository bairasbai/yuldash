import type { MetadataRoute } from "next";
import { SITE_URL } from "@/components/config";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    { url: `${SITE_URL}/`, changeFrequency: "monthly", priority: 1 },
    { url: `${SITE_URL}/ba`, changeFrequency: "monthly", priority: 0.9 },
    { url: `${SITE_URL}/safety`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${SITE_URL}/help`, changeFrequency: "monthly", priority: 0.6 },
    { url: `${SITE_URL}/privacy`, changeFrequency: "yearly", priority: 0.3 },
    { url: `${SITE_URL}/terms`, changeFrequency: "yearly", priority: 0.3 },
    // Ссылку на удаление аккаунта требует Google Play — она должна быть найдена и без приложения.
    { url: `${SITE_URL}/delete-account`, changeFrequency: "yearly", priority: 0.3 },
  ];
}
