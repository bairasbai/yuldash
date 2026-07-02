/** @type {import('next').NextConfig} */
const nextConfig = {
  // Статический экспорт → папка out/ раздаётся Nginx на yulbash.ru
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
  // Вшиваем CSS в HTML — один запрос, стили не render-blocking отдельным файлом.
  // (Тест без inlineCss дал LCP хуже — отдельный CSS блокировал рендер сильнее.)
  experimental: { inlineCss: true },
};

export default nextConfig;
