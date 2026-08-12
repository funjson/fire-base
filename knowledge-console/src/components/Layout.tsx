import {
  Activity,
  Blocks,
  BookOpen,
  BookOpenCheck,
  Cable,
  ChevronRight,
  Database,
  FlaskConical,
  Gauge,
  LogOut,
  Network,
  Search,
  ShieldCheck,
} from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../lib/auth-context'

const navigation = [
  { to: '/', label: '运行总览', icon: Gauge, adminOnly: true },
  { to: '/spaces', label: '知识空间', icon: Blocks, adminOnly: true },
  { to: '/documents', label: '文档管理', icon: BookOpen, adminOnly: true },
  { to: '/wiki', label: '知识页', icon: BookOpenCheck, adminOnly: true },
  { to: '/graph', label: '知识图谱', icon: Network, adminOnly: true },
  { to: '/retrieval', label: '检索实验室', icon: Search, adminOnly: false },
  {
    to: '/evaluations',
    label: '评测中心',
    icon: FlaskConical,
    adminOnly: true,
  },
  { to: '/connectors', label: '数据连接器', icon: Cable, adminOnly: true },
  { to: '/traces', label: '检索追踪', icon: Activity, adminOnly: true },
  { to: '/audit-events', label: '操作审计', icon: ShieldCheck, adminOnly: true },
]

export function Layout() {
  const auth = useAuth()
  const location = useLocation()
  const isAdmin = auth.roles.includes('knowledge-admin')
  const availableNavigation = navigation.filter(
    (item) => !item.adminOnly || isAdmin,
  )
  const active = availableNavigation.find(
    (item) =>
      item.to === location.pathname ||
      (item.to !== '/' && location.pathname.startsWith(item.to)),
  )

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Database size={20} />
          </div>
          <div>
            <strong>Infinity</strong>
            <span>Knowledge Console</span>
          </div>
        </div>
        <div className="tenant-card">
          <span>当前租户</span>
          <strong>{auth.tenant}</strong>
        </div>
        <nav>
          {availableNavigation.map(({ to, label, icon: Icon }) => (
            <NavLink
              end={to === '/'}
              key={to}
              to={to}
              className={({ isActive }) => (isActive ? 'active' : '')}
            >
              <Icon size={18} />
              <span>{label}</span>
              <ChevronRight className="nav-arrow" size={15} />
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="user-meta">
            <div className="avatar">{auth.username.slice(0, 1).toUpperCase()}</div>
            <div>
              <strong>{auth.username}</strong>
              <span>
                {auth.roles.includes('knowledge-admin') ? '知识管理员' : '只读成员'}
              </span>
            </div>
          </div>
          <button className="icon-button" onClick={auth.logout} title="退出登录">
            <LogOut size={17} />
          </button>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div>
            <span className="eyebrow">ENTERPRISE KNOWLEDGE RUNTIME</span>
            <h1>{active?.label ?? '知识管理'}</h1>
          </div>
          <div className="runtime-status">
            <span className="status-dot" />
            安全会话已建立
          </div>
        </header>
        <div className="content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
