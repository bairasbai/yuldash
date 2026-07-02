/** @type {import('next').NextConfig} */
const nextConfig = {
  // Статический экспорт → папка out/ раздаётся Nginx на yulbash.ru
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
  // Вшиваем CSS прямо в HTML — нет отдельного render-blocking запроса CSS.
  // Критично для зажатых/флаки мобильных сетей (ТСПУ): стили приходят одним
  // запросом с HTML, страница не висит чёрной в ожидании отдельного CSS-файла.
  experimental: { inlineCss: true },
};

export default nextConfig;
