import '@testing-library/jest-dom/vitest'
import process from 'node:process'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'

import { resetApiClientForTests } from '../api/client'
import { useAuthStore } from '../auth/store'
import { clearVisibleTestCookies } from './cookies'
import { server } from './server'

// CI 的 jsdom 首次挂载完整管理界面显著慢于开发机；保持断言，增加异步等待预算。
configure({ asyncUtilTimeout: process.env.CI ? 10000 : 1000 })

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key) },
    setItem: (key, value) => { values.set(key, String(value)) },
  }
}

Object.defineProperty(window, 'localStorage', { configurable: true, value: memoryStorage() })
Object.defineProperty(window, 'sessionStorage', { configurable: true, value: memoryStorage() })

const getComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = (element: Element) => getComputedStyle(element)

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

class TestResizeObserver implements ResizeObserver {
  disconnect() {}
  observe() {}
  unobserve() {}
}

Object.defineProperty(window, 'ResizeObserver', { configurable: true, value: TestResizeObserver })
Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: TestResizeObserver })
Object.defineProperty(HTMLMediaElement.prototype, 'pause', { configurable: true, value: vi.fn() })
Object.defineProperty(HTMLMediaElement.prototype, 'load', { configurable: true, value: vi.fn() })

beforeAll(() => server.listen())
afterEach(() => {
  cleanup()
  useAuthStore.getState().reset()
  resetApiClientForTests()
  server.reset()
  window.localStorage.clear()
  window.sessionStorage.clear()
  clearVisibleTestCookies()
  vi.clearAllTimers()
  vi.useRealTimers()
  window.innerWidth = 1024
  window.history.replaceState(null, '', '/')
})
afterAll(() => server.close())
