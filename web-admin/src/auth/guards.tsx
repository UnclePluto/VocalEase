import { useEffect, type ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { Flex, Spin } from 'antd'

import { useAuthStore } from './store'

function BootScreen() {
  return (
    <Flex className="app-boot" align="center" justify="center" role="status" aria-label="正在恢复登录状态">
      <Spin size="large" />
    </Flex>
  )
}

export function AuthBootstrap({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status)
  const initialize = useAuthStore((state) => state.initialize)
  useEffect(() => {
    void initialize()
  }, [initialize])
  return status === 'booting' ? <BootScreen /> : children
}

export function RequireAuth() {
  const status = useAuthStore((state) => state.status)
  const role = useAuthStore((state) => state.user?.role)
  const location = useLocation()
  if (status === 'authenticated' && role === 'patient') return <RejectPatientAccount />
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}

function RejectPatientAccount() {
  const reset = useAuthStore((state) => state.reset)
  useEffect(() => reset(), [reset])
  return null
}

export function RequirePasswordChanged() {
  const mustChangePassword = useAuthStore((state) => state.user?.must_change_password ?? false)
  if (mustChangePassword) return <Navigate to="/change-password" replace />
  return <Outlet />
}

export function RequirePasswordChange() {
  const user = useAuthStore((state) => state.user)
  if (!user) return <Navigate to="/login" replace />
  if (!user.must_change_password) return <Navigate to="/doctors" replace />
  return <Outlet />
}

export function AnonymousOnly() {
  const status = useAuthStore((state) => state.status)
  const mustChangePassword = useAuthStore((state) => state.user?.must_change_password ?? false)
  if (status === 'authenticated') {
    return <Navigate to={mustChangePassword ? '/change-password' : '/doctors'} replace />
  }
  return <Outlet />
}
