import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react()],
  build: {
    outDir: "../app/build/generated/webAssets/web",
    emptyOutDir: true,
    sourcemap: false,
    target: "chrome60",
    // Keep modern glass effects in the packaged CSS. The JavaScript bundle still targets the
    // oldest supported WebView syntax, while the runtime-required Android WebView supports these
    // CSS features natively.
    cssTarget: "chrome121",
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
  },
});
