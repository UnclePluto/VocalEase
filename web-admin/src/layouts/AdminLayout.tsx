import { useEffect, useMemo, useState } from 'react'
import {
  BarChartOutlined,
  DatabaseOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MedicineBoxOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Button, Drawer, Layout, Menu } from 'antd'
import type { ItemType, MenuItemType } from 'antd/es/menu/interface'
import { Link, Outlet, useLocation } from 'react-router-dom'

import { useAuthStore } from '../auth/store'

const { Header, Sider, Content } = Layout
const DESKTOP_BREAKPOINT = 900

function NavLink({ to, label }: { to: string; label: string }) {
  const active = useLocation().pathname === to
  return <Link to={to} aria-current={active ? 'page' : undefined}>{label}</Link>
}

function Navigation() {
  const location = useLocation()
  const items = useMemo<ItemType<MenuItemType>[]>(
    () => [
      {
        key: 'accounts',
        icon: <TeamOutlined />,
        label: '账号管理',
        children: [
          { key: '/doctors', label: <NavLink to="/doctors" label="医生管理" /> },
          { key: '/patients', label: <NavLink to="/patients" label="病人管理" /> },
        ],
      },
      { key: '/songs', icon: <MedicineBoxOutlined />, label: <NavLink to="/songs" label="曲库管理" /> },
      { key: '/patient-data', icon: <BarChartOutlined />, label: <NavLink to="/patient-data" label="病人数据" /> },
      { key: '/analytics', icon: <DatabaseOutlined />, label: <NavLink to="/analytics" label="数据管理" /> },
    ],
    [],
  )
  return (
    <Menu
      mode="inline"
      items={items}
      selectedKeys={[location.pathname]}
      defaultOpenKeys={['accounts']}
      className="admin-menu"
    />
  )
}

function Brand() {
  return (
    <div className="admin-brand" aria-label="VocaEase">
      <span className="admin-brand-mark" aria-hidden="true">V</span>
      <span>VocaEase</span>
    </div>
  )
}

export function AdminLayout() {
  const [mobile, setMobile] = useState(() => window.innerWidth < DESKTOP_BREAKPOINT)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const loginId = useAuthStore((state) => state.user?.login_id ?? '')
  const logout = useAuthStore((state) => state.logout)

  useEffect(() => {
    const update = () => {
      const nextMobile = window.innerWidth < DESKTOP_BREAKPOINT
      setMobile(nextMobile)
      if (!nextMobile) setDrawerOpen(false)
    }
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  return (
    <Layout className="admin-shell">
      {!mobile ? (
        <Sider width={208} theme="light" className="admin-sider">
          <Brand />
          <nav aria-label="后台主导航"><Navigation /></nav>
        </Sider>
      ) : null}
      <Layout className="admin-main">
        <Header className="admin-header">
          {mobile ? (
            <Button
              type="text"
              icon={<MenuFoldOutlined />}
              aria-label="打开导航菜单"
              onClick={() => setDrawerOpen(true)}
            />
          ) : <span />}
          <div className="admin-user">
            <span className="admin-user-name"><UserOutlined />{loginId}</span>
            <Button type="text" icon={<LogoutOutlined />} aria-label="退出登录" onClick={() => void logout()}>
              退出
            </Button>
          </div>
        </Header>
        <Content className="admin-content"><Outlet /></Content>
      </Layout>
      <Drawer
        title={<Brand />}
        placement="left"
        open={mobile && drawerOpen}
        onClose={() => setDrawerOpen(false)}
        styles={{ wrapper: { width: 280 }, body: { padding: 0 } }}
      >
        <nav aria-label="移动端后台主导航" onClick={() => setDrawerOpen(false)}><Navigation /></nav>
      </Drawer>
    </Layout>
  )
}
