import { SITE_URL, SOCIAL } from "./config";

// JSON-LD — структурные данные для Google (rich-сниппеты).
// Server-компонент: рендерится в статику, в браузере не исполняется.
export function StructuredData() {
  const data = [
    {
      "@context": "https://schema.org",
      "@type": "SoftwareApplication",
      name: "Юлдаш",
      applicationCategory: "TravelApplication",
      operatingSystem: "Android",
      description:
        "Приложение попуток по Башкортостану между своими. Доверие, тёплые поездки и честная цена.",
      url: SITE_URL,
      downloadUrl: `${SITE_URL}/yuldash.apk`,
      inLanguage: ["ru", "ba"],
      offers: { "@type": "Offer", price: "0", priceCurrency: "RUB" },
      // aggregateRating добавим, когда будут РЕАЛЬНЫЕ отзывы (иначе санкции Google за ложь).
    },
    {
      "@context": "https://schema.org",
      "@type": "Organization",
      name: "Юлдаш",
      url: SITE_URL,
      logo: `${SITE_URL}/logo.png`,
      sameAs: [SOCIAL.telegram, SOCIAL.vk],
    },
    {
      "@context": "https://schema.org",
      "@type": "FAQPage",
      mainEntity: [
        ["Это бесплатно?", "Да, приложение бесплатное. Платишь только за саму поездку — напрямую водителю."],
        ["Это безопасно?", "Водители проходят проверку, есть рейтинги, бейдж «проверен», кнопка SOS и «поделиться поездкой» с близкими."],
        ["Почему просит установку из неизвестного источника?", "Пока мы не в Google Play, приложение ставится через APK. Это нормально — разреши установку один раз."],
        ["А на iPhone есть?", "Пока только Android. Версии для iPhone нет, сроков не называем — напишите, если нужна."],
        ["Как стать водителем?", "Установи приложение, заполни профиль и опубликуй свою поездку. Модерация — вручную, для доверия."],
      ].map(([q, a]) => ({
        "@type": "Question",
        name: q,
        acceptedAnswer: { "@type": "Answer", text: a },
      })),
    },
  ];

  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(data) }}
    />
  );
}
