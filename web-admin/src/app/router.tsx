import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, createBrowserRouter, createMemoryRouter, useParams, type RouteObject } from 'react-router-dom'

import { AnonymousOnly, RequireAuth, RequirePasswordChange, RequirePasswordChanged } from '../auth/guards'

const LoginPage = lazy(() => import('../features/auth/LoginPage').then((module) => ({ default: module.LoginPage })))
const ChangePasswordPage = lazy(() => import('../features/auth/ChangePasswordPage').then((module) => ({ default: module.ChangePasswordPage })))
const AdminLayout = lazy(() => import('../layouts/AdminLayout').then((module) => ({ default: module.AdminLayout })))
const DoctorListPage = lazy(() => import('../features/doctors/DoctorListPage').then((module) => ({ default: module.DoctorListPage })))
const PatientListPage = lazy(() => import('../features/patients/PatientListPage').then((module) => ({ default: module.PatientListPage })))

function Suspended({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="route-loading" role="status">页面加载中</div>}>{children}</Suspense>
}

function Placeholder({ title }: { title: string }) {
  return <section className="route-placeholder"><h1>{title}</h1></section>
}

function PatientDataPlaceholder() {
  const { patientId } = useParams()
  return (
    <section className="route-placeholder" aria-labelledby="patient-data-title">
      <h1 id="patient-data-title">患者数据</h1>
      <p>患者编号：<span>{patientId}</span></p>
      <p>汇总与演唱历史将在 Task 11 实现。</p>
    </section>
  )
}

export const appRoutes: RouteObject[] = [
  {
    element: <AnonymousOnly />,
    children: [{ path: '/login', element: <Suspended><LoginPage /></Suspended> }],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <RequirePasswordChange />,
        children: [{ path: '/change-password', element: <Suspended><ChangePasswordPage /></Suspended> }],
      },
      {
        element: <RequirePasswordChanged />,
        children: [
          {
            element: <Suspended><AdminLayout /></Suspended>,
            children: [
              { path: '/doctors', element: <Suspended><DoctorListPage /></Suspended> },
              { path: '/patients', element: <Suspended><PatientListPage /></Suspended> },
              { path: '/patients/:patientId/data', element: <PatientDataPlaceholder /> },
              { path: '/songs', element: <Placeholder title="曲库管理" /> },
              { path: '/patient-data', element: <Placeholder title="病人数据" /> },
              { path: '/analytics', element: <Placeholder title="数据管理" /> },
            ],
          },
        ],
      },
    ],
  },
  { path: '/', element: <Navigate to="/doctors" replace /> },
  { path: '*', element: <Navigate to="/doctors" replace /> },
]

export function createAppBrowserRouter() {
  return createBrowserRouter(appRoutes)
}

export function createAppMemoryRouter(path = '/') {
  return createMemoryRouter(appRoutes, { initialEntries: [path] })
}
