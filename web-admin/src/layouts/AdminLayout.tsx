import { useEffect, useMemo, useState } from 'react'
import {
  BarChartOutlined,
  BellOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  SoundOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Avatar, Button, Drawer, Layout, Menu } from 'antd'
import type { ItemType, MenuItemType } from 'antd/es/menu/interface'
import { Link, Outlet, useLocation } from 'react-router-dom'

import { useAuthStore } from '../auth/store'
import { BrandLogo } from '../components/BrandLogo'

const { Header, Sider, Content } = Layout
const DESKTOP_BREAKPOINT = 900

function NavLink({ to, label }: { to: string; label: string }) {
  const pathname = useLocation().pathname
  const active = pathname === to || (to === '/patient-data' && (pathname.startsWith('/patients/') || pathname.startsWith('/singing/')))
  return <Link to={to} aria-current={active ? 'page' : undefined}>{label}</Link>
}

function Navigation() {
  const location = useLocation()
  const selectedKey = location.pathname.startsWith('/patients/') || location.pathname.startsWith('/singing/') ? '/patient-data' : location.pathname
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
      { key: '/songs', icon: <SoundOutlined />, label: <NavLink to="/songs" label="曲库管理" /> },
      { key: '/patient-data', icon: <BarChartOutlined />, label: <NavLink to="/patient-data" label="病人数据" /> },
      { key: '/analytics', icon: <DatabaseOutlined />, label: <NavLink to="/analytics" label="数据管理" /> },
    ],
    [],
  )
  return (
    <Menu
      theme="dark"
      mode="inline"
      items={items}
      selectedKeys={[selectedKey]}
      defaultOpenKeys={['accounts']}
      className="admin-menu"
    />
  )
}

function Brand() {
  return (
    <div className="admin-brand" aria-label="VocaEase">
      <BrandLogo className="admin-brand-mark" />
      <span>VocaEase</span>
    </div>
  )
}

function breadcrumbFor(pathname: string) {
  if (pathname === '/doctors') return ['账号管理', '医生管理']
  if (pathname === '/patients') return ['账号管理', '病人管理']
  if (pathname === '/songs') return ['曲库管理', '歌曲列表']
  if (pathname.startsWith('/patients/') && pathname.endsWith('/data')) return ['病人数据', '患者详情']
  if (pathname.startsWith('/singing/')) return ['病人数据', '唱歌明细']
  if (pathname === '/analytics') return ['数据管理', '综合数据汇总']
  return ['首页', '医生管理']
}

export function AdminLayout() {
  const [mobile, setMobile] = useState(() => window.innerWidth < DESKTOP_BREAKPOINT)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const loginId = useAuthStore((state) => state.user?.login_id ?? '')
  const logout = useAuthStore((state) => state.logout)
  const location = useLocation()
  const breadcrumb = breadcrumbFor(location.pathname)

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
        <Sider width={208} theme="dark" className="admin-sider">
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
          ) : (
            <div className="admin-breadcrumb" aria-label="当前位置">
              <span>{breadcrumb[0]}</span><i aria-hidden="true">/</i><strong>{breadcrumb[1]}</strong>
            </div>
          )}
          <div className="admin-user">
            <BellOutlined className="admin-notification" aria-label="通知" />
            <button type="button" className="admin-user-trigger" aria-label="退出登录" onClick={() => void logout()}>
              <Avatar size={32} className="admin-avatar">{loginId === 'demo-admin' ? '管' : loginId.slice(0, 1).toUpperCase()}</Avatar>
              <span className="admin-user-name">{loginId === 'demo-admin' ? '管理员' : loginId}</span>
            </button>
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
