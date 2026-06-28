/** @type {import('next').NextConfig} */
const nextConfig = {
  // Статический экспорт → папка out/ раздаётся Nginx на yulbash.ru
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
};

export default nextConfig;
