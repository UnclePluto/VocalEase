import { useState, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'

import { AuthBootstrap } from '../auth/guards'
import { antdTheme } from '../styles/tokens'

const appTheme = import.meta.env.MODE === 'test'
  ? { ...antdTheme, token: { ...antdTheme.token, motion: false } }
  : antdTheme

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  }))
  return (
    <ConfigProvider theme={appTheme}>
      <QueryClientProvider client={queryClient}>
        <AuthBootstrap>{children}</AuthBootstrap>
      </QueryClientProvider>
    </ConfigProvider>
  )
}
