import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  publicDir: false,
  build: { outDir: "tf09-qa-dist", rollupOptions: { input: "tf09-qa.html" } },
});
