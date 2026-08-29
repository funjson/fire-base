import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  AlertTriangle,
  Blocks,
  BookOpen,
  Cable,
  FileStack,
  FlaskConical,
  Timer,
} from 'lucide-react'
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import { ErrorState, LoadingState, Panel, StatusBadge } from '../components/State'
import { useApi } from '../lib/use-api'

export function DashboardPage() {
  const api = useApi()
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: api.overview,
    refetchInterval: 15_000,
  })
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const traces = useQuery({
    queryKey: ['traces'],
    queryFn: api.traces,
    refetchInterval: 15_000,
  })

  if (overview.isPending) return <LoadingState />
  if (overview.error) return <ErrorState error={overview.error} />

  const value = overview.data
  const projectionData = [
    { name: '处理中', value: value.pendingProjections, color: '#f4b860' },
    { name: '死信', value: value.deadProjections, color: '#ef6a76' },
  ]
  const averageLatency = traces.data?.length
    ? Math.round(
        traces.data.reduce((sum, item) => sum + item.totalDurationMs, 0) /
          traces.data.length,
      )
    : traces.data
      ? 0
      : '—'

  return (
    <div className="page-stack">
      <div className="hero-card">
        <div>
          <span className="eyebrow">KNOWLEDGE OPERATIONS</span>
          <h2>让企业知识保持可用、可信、可评测</h2>
          <p>
            统一观察知识摄取、索引投影、检索质量和评测结果。所有数据均限定在当前租户。
          </p>
        </div>
        <div className="hero-signal">
          <div className="signal-ring">
            <Activity size={28} />
          </div>
          <div>
            <strong>{value.deadProjections === 0 ? '运行健康' : '需要处理'}</strong>
            <span>
              {value.deadProjections === 0
                ? '当前没有死信投影'
                : `${value.deadProjections} 个投影进入死信`}
            </span>
          </div>
        </div>
      </div>

      <div className="metric-grid">
        <Metric icon={Blocks} label="知识空间" value={value.spaces} />
        <Metric icon={BookOpen} label="活动文档" value={value.activeDocuments} />
        <Metric icon={FileStack} label="知识块" value={value.chunks} />
        <Metric icon={Cable} label="外部数据源" value={value.connectors} />
        <Metric
          icon={FlaskConical}
          label="评测运行"
          value={value.evaluationRuns}
        />
        <Metric icon={Timer} label="平均检索延迟" value={`${averageLatency} ms`} />
      </div>

      <div className="dashboard-grid">
        <Panel title="索引投影健康" description="异步投影队列与死信状态">
          <div className="chart-row">
            <div className="donut">
              <ResponsiveContainer width="100%" height={210}>
                <PieChart>
                  <Pie
                    data={projectionData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={58}
                    outerRadius={82}
                    paddingAngle={3}
                  >
                    {projectionData.map((entry) => (
                      <Cell key={entry.name} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      background: '#111c31',
                      border: '1px solid #263654',
                      borderRadius: 10,
                    }}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="donut-center">
                <strong>{value.pendingProjections + value.deadProjections}</strong>
                <span>待关注</span>
              </div>
            </div>
            <div className="legend-list">
              {projectionData.map((entry) => (
                <div key={entry.name}>
                  <i style={{ background: entry.color }} />
                  <span>{entry.name}</span>
                  <strong>{entry.value}</strong>
                </div>
              ))}
            </div>
          </div>
        </Panel>

        <Panel title="最近检索" description="最近 100 条安全 Trace 的最新记录">
          {traces.isPending && <LoadingState label="正在加载检索 Trace" />}
          {traces.error && <ErrorState error={traces.error} />}
          <div className="compact-list">
            {traces.data?.slice(0, 6).map((trace) => (
              <div className="compact-row" key={trace.id}>
                <div className="trace-icon">
                  <Activity size={16} />
                </div>
                <div>
                  <strong>{trace.requestId.slice(0, 8)}</strong>
                  <span>{trace.principalId}</span>
                </div>
                <div className="row-end">
                  <strong>{trace.totalDurationMs} ms</strong>
                  <span>{trace.resultCount} 条证据</span>
                </div>
              </div>
            ))}
            {!traces.isPending && !traces.error && !traces.data?.length && (
              <div className="inline-empty">尚无检索 Trace</div>
            )}
          </div>
        </Panel>
      </div>

      <Panel title="知识空间状态" description="当前租户下的主要知识边界">
        {spaces.isPending && <LoadingState label="正在加载知识空间" />}
        {spaces.error && <ErrorState error={spaces.error} />}
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>空间</th>
                <th>标识</th>
                <th>文档</th>
                <th>状态</th>
                <th>最近更新</th>
              </tr>
            </thead>
            <tbody>
              {spaces.data?.slice(0, 8).map((space) => (
                <tr key={space.id}>
                  <td>
                    <strong>{space.name}</strong>
                  </td>
                  <td className="mono">{space.id}</td>
                  <td>{space.documentCount}</td>
                  <td>
                    <StatusBadge value={space.status} />
                  </td>
                  <td>{formatTime(space.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  )
}

function Metric({
  icon: Icon,
  label,
  value,
}: {
  icon: typeof AlertTriangle
  label: string
  value: number | string
}) {
  return (
    <div className="metric-card">
      <div className="metric-icon">
        <Icon size={19} />
      </div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
