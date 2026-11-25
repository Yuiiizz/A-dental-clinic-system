import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

// dev/build 与 vitest 共用此配置：vite build 忽略 test 段，vitest 读取 test 段。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 本地开发：把同源 /api 请求代理到后端（默认 8080）。保持同源，浏览器才会携带
  // HttpOnly 会话 Cookie；后端未配置 CORS，前端不跨源直连。仅 dev server 生效，不影响构建产物。
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
  },
});
