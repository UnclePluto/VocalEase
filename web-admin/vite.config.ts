import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8000' },
  },
  test: {
    exclude: [...configDefaults.exclude, 'e2e/**'],
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    // 页面测试会挂载完整 Ant Design 界面，限制并发以适配 CI 的有限 CPU。
    maxWorkers: 2,
    testTimeout: process.env.CI ? 60000 : 5000,
  },
})
