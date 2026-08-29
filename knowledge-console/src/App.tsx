import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { lazy, Suspense, type ReactNode } from 'react'
import {
  BrowserRouter,
  Link,
  Navigate,
  Route,
  Routes,
} from 'react-router-dom'
import { ErrorBoundary } from './components/ErrorBoundary'
import { Layout } from './components/Layout'
import { AuthProvider } from './lib/auth'
import { useAuth } from './lib/auth-context'

const DashboardPage = lazy(() =>
  import('./pages/DashboardPage').then((module) => ({
    default: module.DashboardPage,
  })),
)
const SpacesPage = lazy(() =>
  import('./pages/SpacesPage').then((module) => ({ default: module.SpacesPage })),
)
const SpaceExtractionPage = lazy(() =>
  import('./pages/SpaceExtractionPage').then((module) => ({
    default: module.SpaceExtractionPage,
  })),
)
const DocumentsPage = lazy(() =>
  import('./pages/DocumentsPage').then((module) => ({
    default: module.DocumentsPage,
  })),
)
const DocumentDetailPage = lazy(() =>
  import('./pages/DocumentDetailPage').then((module) => ({
    default: module.DocumentDetailPage,
  })),
)
const WikiPagesPage = lazy(() =>
  import('./pages/WikiPagesPage').then((module) => ({
    default: module.WikiPagesPage,
  })),
)
const GraphPage = lazy(() =>
  import('./pages/GraphPage').then((module) => ({
    default: module.GraphPage,
  })),
)
const RetrievalPage = lazy(() =>
  import('./pages/RetrievalPage').then((module) => ({
    default: module.RetrievalPage,
  })),
)
const EvaluationsPage = lazy(() =>
  import('./pages/EvaluationsPage').then((module) => ({
    default: module.EvaluationsPage,
  })),
)
const ConnectorsPage = lazy(() =>
  import('./pages/ConnectorsPage').then((module) => ({
    default: module.ConnectorsPage,
  })),
)
const TracesPage = lazy(() =>
  import('./pages/TracesPage').then((module) => ({ default: module.TracesPage })),
)
const AuditEventsPage = lazy(() =>
  import('./pages/AuditEventsPage').then((module) => ({
    default: module.AuditEventsPage,
  })),
)

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

export function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}

function AuthGate() {
  const auth = useAuth()
  if (!auth.ready) {
    return (
      <div className="auth-screen">
        <LoaderCircle className="spin" size={30} />
        <strong>正在建立安全会话</strong>
        <span>连接企业身份服务并读取租户上下文…</span>
      </div>
    )
  }
  if (!auth.authenticated) {
    return (
      <div className="auth-screen">
        <strong>无法建立登录会话</strong>
        <span>
          {auth.error ??
            '身份服务未返回有效会话，请检查 Keycloak Client 的回调地址。'}
        </span>
        <small>
          前端来源：{window.location.origin}
          <br />
          OIDC：{import.meta.env.VITE_OIDC_URL ?? 'http://localhost:8180'}
        </small>
        <button className="primary-button" onClick={auth.login}>
          重新登录
        </button>
      </div>
    )
  }
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <ErrorBoundary>
          <Suspense fallback={<RouteLoading />}>
            <Routes>
              <Route element={<Layout />}>
                <Route index element={<HomePage />} />
                <Route path="spaces" element={<AdminPage page={<SpacesPage />} />} />
                <Route
                  path="spaces/:spaceId/extraction"
                  element={<AdminPage page={<SpaceExtractionPage />} />}
                />
                <Route
                  path="documents"
                  element={<AdminPage page={<DocumentsPage />} />}
                />
                <Route
                  path="documents/:documentId"
                  element={<AdminPage page={<DocumentDetailPage />} />}
                />
                <Route
                  path="wiki"
                  element={<AdminPage page={<WikiPagesPage />} />}
                />
                <Route
                  path="graph"
                  element={<AdminPage page={<GraphPage />} />}
                />
                <Route
                  path="retrieval"
                  element={<Navigate replace to="/evaluations/playground" />}
                />
                <Route
                  path="evaluations"
                  element={<Navigate replace to="/evaluations/playground" />}
                />
                <Route
                  path="evaluations/playground"
                  element={<RetrievalPage />}
                />
                <Route
                  path="evaluations/datasets"
                  element={<AdminPage page={<EvaluationsPage />} />}
                />
                <Route
                  path="connectors"
                  element={<AdminPage page={<ConnectorsPage />} />}
                />
                <Route
                  path="traces"
                  element={<AdminPage page={<TracesPage />} />}
                />
                <Route
                  path="audit-events"
                  element={<AdminPage page={<AuditEventsPage />} />}
                />
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

function HomePage() {
  const auth = useAuth()
  return auth.roles.includes('knowledge-admin') ? (
    <DashboardPage />
  ) : (
    <Navigate replace to="/evaluations/playground" />
  )
}

function AdminPage({ page }: { page: ReactNode }) {
  const auth = useAuth()
  return auth.roles.includes('knowledge-admin') ? (
    page
  ) : (
    <Navigate replace to="/evaluations/playground" />
  )
}

function NotFoundPage() {
  return (
    <div className="route-not-found">
      <span className="eyebrow">404 · NOT FOUND</span>
      <h2>没有找到这个管理页面</h2>
      <p>地址可能已经变更，或当前版本尚未提供该功能。</p>
      <Link className="primary-button" to="/">
        返回运行总览
      </Link>
    </div>
  )
}

function RouteLoading() {
  return (
    <div className="loading-state">
      <LoaderCircle className="spin" size={24} />
      <span>正在加载管理模块…</span>
    </div>
  )
}
