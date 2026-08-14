import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, createBrowserRouter, createMemoryRouter, type RouteObject } from 'react-router-dom'

import { AnonymousOnly, RequireAuth, RequirePasswordChange, RequirePasswordChanged } from '../auth/guards'

const LoginPage = lazy(() => import('../features/auth/LoginPage').then((module) => ({ default: module.LoginPage })))
const ChangePasswordPage = lazy(() => import('../features/auth/ChangePasswordPage').then((module) => ({ default: module.ChangePasswordPage })))
const AdminLayout = lazy(() => import('../layouts/AdminLayout').then((module) => ({ default: module.AdminLayout })))
const DoctorListPage = lazy(() => import('../features/doctors/DoctorListPage').then((module) => ({ default: module.DoctorListPage })))
const PatientListPage = lazy(() => import('../features/patients/PatientListPage').then((module) => ({ default: module.PatientListPage })))
const SongListPage = lazy(() => import('../features/songs/SongListPage').then((module) => ({ default: module.SongListPage })))
const PatientDataPage = lazy(() => import('../features/singing/PatientDataPage').then((module) => ({ default: module.PatientDataPage })))
const SingingDetailPage = lazy(() => import('../features/singing/SingingDetailPage').then((module) => ({ default: module.SingingDetailPage })))

function Suspended({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="route-loading" role="status">页面加载中</div>}>{children}</Suspense>
}

function Placeholder({ title }: { title: string }) {
  return <section className="route-placeholder"><h1>{title}</h1></section>
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
              { path: '/patients/:patientId/data', element: <Suspended><PatientDataPage /></Suspended> },
              { path: '/singing/:sessionId', element: <Suspended><SingingDetailPage /></Suspended> },
              { path: '/songs', element: <Suspended><SongListPage /></Suspended> },
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
