import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：/api -> 本地后端 8080，生产经 Nginx 反代
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
