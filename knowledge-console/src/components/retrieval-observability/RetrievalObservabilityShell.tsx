import { Activity, BarChart3, Layers3, ListTree, RefreshCw } from 'lucide-react'
import { type FormEvent, type ReactNode, useMemo, useState } from 'react'
import { NavLink, useSearchParams } from 'react-router-dom'
import type { RetrievalObservabilityFilter, Space } from '../../lib/api'

export type RetrievalObservabilityView = 'overview' | 'stages' | 'executions'

type TimeRange = '1h' | '6h' | '24h' | '7d' | '30d'

const ranges: Array<{ value: TimeRange; label: string; durationMillis: number }> = [
  { value: '1h', label: '最近 1 小时', durationMillis: 60 * 60 * 1_000 },
  { value: '6h', label: '最近 6 小时', durationMillis: 6 * 60 * 60 * 1_000 },
  { value: '24h', label: '最近 24 小时', durationMillis: 24 * 60 * 60 * 1_000 },
  { value: '7d', label: '最近 7 天', durationMillis: 7 * 24 * 60 * 60 * 1_000 },
  { value: '30d', label: '最近 30 天', durationMillis: 30 * 24 * 60 * 60 * 1_000 },
]

const tabs = [
  {
    view: 'overview' as const,
    label: '在线概览',
    description: '运行健康与代理质量趋势',
    icon: BarChart3,
  },
  {
    view: 'stages' as const,
    label: '分层诊断',
    description: '定位阶段、分支与 Chain 问题',
    icon: Layers3,
  },
  {
    view: 'executions' as const,
    label: '执行记录',
    description: '按请求下钻安全观测事实',
    icon: ListTree,
  },
]

export type RetrievalObservabilityShellValue = {
  filter: RetrievalObservabilityFilter
  rangeLabel: string
  refreshKey: number
}

type RetrievalObservabilityShellProps = {
  view: RetrievalObservabilityView
  spaces?: Space[]
  spacesLoading: boolean
  children: (value: RetrievalObservabilityShellValue) => ReactNode
}

/**
 * 在线观测筛选只产生低基数聚合维度。原始查询和候选标识留在授权下钻中，
 * 不会被拼进指标查询或图表标签。
 */
export function RetrievalObservabilityShell({
  view,
  spaces,
  spacesLoading,
  children,
}: RetrievalObservabilityShellProps) {
  const [searchParams, setSearchParams] = useSearchParams()
  const [anchorTime, setAnchorTime] = useState(() => Date.now())
  const [refreshKey, setRefreshKey] = useState(0)
  const requestedRange = searchParams.get('range')
  const range =
    ranges.find((candidate) => candidate.value === requestedRange) ?? ranges[2]
  const spaceId = searchParams.get('spaceId') || undefined
  const configFingerprint =
    searchParams.get('configFingerprint')?.trim() || undefined
  const dataIndexVersion =
    searchParams.get('dataIndexVersion')?.trim() || undefined
  const filter = useMemo<RetrievalObservabilityFilter>(
    () => ({
      from: new Date(anchorTime - range.durationMillis).toISOString(),
      to: new Date(anchorTime).toISOString(),
      spaceId,
      configFingerprint,
      dataIndexVersion,
    }),
    [
      anchorTime,
      configFingerprint,
      dataIndexVersion,
      range.durationMillis,
      spaceId,
    ],
  )

  const updateFilter = (
    key: 'range' | 'spaceId' | 'configFingerprint' | 'dataIndexVersion',
    value: string,
  ) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('page')
    next.delete('requestId')
    setSearchParams(next)
    setAnchorTime(Date.now())
  }

  const refresh = () => {
    setAnchorTime(Date.now())
    setRefreshKey((value) => value + 1)
  }

  const applyVersionFilters = (
    nextConfigFingerprint: string,
    nextDataIndexVersion: string,
  ) => {
    const next = new URLSearchParams(searchParams)
    if (nextConfigFingerprint.trim()) {
      next.set('configFingerprint', nextConfigFingerprint.trim())
    } else {
      next.delete('configFingerprint')
    }
    if (nextDataIndexVersion.trim()) {
      next.set('dataIndexVersion', nextDataIndexVersion.trim())
    } else {
      next.delete('dataIndexVersion')
    }
    next.delete('page')
    next.delete('requestId')
    setSearchParams(next)
    setAnchorTime(Date.now())
  }

  return (
    <div className="page-stack retrieval-observability-page">
      <div className="page-intro retrieval-observability-intro">
        <div>
          <span className="eyebrow">ONLINE RETRIEVAL OBSERVABILITY</span>
          <h2>用在线运行事实定位检索问题</h2>
          <p>
            线上数据用于观察稳定性和质量趋势；Coverage 属于代理判断，真实能力结论仍以固定
            Gold Dataset 的离线评测为准。
          </p>
        </div>
        <div className="observability-purpose-card">
          <Activity size={18} />
          <div>
            <strong>ONLINE 数据已隔离</strong>
            <span>不包含测试广场、评测、影子或回放流量</span>
          </div>
        </div>
      </div>

      <nav className="retrieval-observability-nav" aria-label="检索观测视图">
        {tabs.map(({ view: tabView, label, description, icon: Icon }) => (
          <NavLink
            className={view === tabView ? 'active' : undefined}
            key={tabView}
            to={{
              pathname: `/observability/retrieval/${tabView}`,
              search: searchParams.toString(),
            }}
          >
            <Icon size={17} />
            <span>
              <strong>{label}</strong>
              <small>{description}</small>
            </span>
          </NavLink>
        ))}
      </nav>

      <section className="observability-filter-bar" aria-label="在线观测筛选">
        <label>
          <span>统计窗口</span>
          <select
            aria-label="统计窗口"
            value={range.value}
            onChange={(event) => updateFilter('range', event.target.value)}
          >
            {ranges.map((candidate) => (
              <option key={candidate.value} value={candidate.value}>
                {candidate.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>知识空间</span>
          <select
            aria-label="知识空间"
            disabled={spacesLoading}
            value={spaceId ?? ''}
            onChange={(event) => updateFilter('spaceId', event.target.value)}
          >
            <option value="">全部 Space</option>
            {spaces?.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name} · {space.id}
              </option>
            ))}
          </select>
        </label>
        <div className="observability-filter-window">
          <span>当前窗口</span>
          <strong>{formatWindow(filter.from, filter.to)}</strong>
        </div>
        <button type="button" className="secondary-button" onClick={refresh}>
          <RefreshCw size={14} />
          刷新窗口
        </button>
        <VersionFilters
          key={`${configFingerprint ?? ''}:${dataIndexVersion ?? ''}`}
          configFingerprint={configFingerprint}
          dataIndexVersion={dataIndexVersion}
          onApply={applyVersionFilters}
        />
        <div className={`observability-cohort-note${spaceId ? ' selected' : ''}`}>
          <Activity size={14} />
          <span>
            {spaceId
              ? 'Space 筛选采用“涉及该 Space 的 execution cohort”：同一次跨 Space 执行可能进入多个 Space 面板，因此跨 Space 面板不可相加；分层诊断阶段按实际 visit 归属。'
              : '全部 Space 下，每个 execution 的请求量只计一次；分层诊断阶段按实际 visit 归属，不能用阶段行反推全局请求量。'}
          </span>
        </div>
      </section>

      {children({ filter, rangeLabel: range.label, refreshKey })}
    </div>
  )
}

function VersionFilters({
  configFingerprint,
  dataIndexVersion,
  onApply,
}: {
  configFingerprint?: string
  dataIndexVersion?: string
  onApply: (configFingerprint: string, dataIndexVersion: string) => void
}) {
  const [configValue, setConfigValue] = useState(configFingerprint ?? '')
  const [indexValue, setIndexValue] = useState(dataIndexVersion ?? '')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    onApply(configValue, indexValue)
  }
  return (
    <details className="observability-advanced-filter">
      <summary>
        高级版本筛选
        {(configFingerprint || dataIndexVersion) && <i>已启用</i>}
      </summary>
      <form onSubmit={submit}>
        <label>
          <span>配置指纹</span>
          <input
            aria-label="配置指纹"
            placeholder="完整 config fingerprint"
            value={configValue}
            onChange={(event) => setConfigValue(event.target.value)}
          />
        </label>
        <label>
          <span>索引版本</span>
          <input
            aria-label="索引版本"
            placeholder="完整 data index version"
            value={indexValue}
            onChange={(event) => setIndexValue(event.target.value)}
          />
        </label>
        <button type="submit" className="secondary-button">应用版本筛选</button>
        <p>限定单一版本后再判断前后变化；未限定时曲线可能包含多次配置或索引发布。</p>
      </form>
    </details>
  )
}

function formatWindow(from: string, to: string) {
  const formatter = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
  return `${formatter.format(new Date(from))} — ${formatter.format(new Date(to))}`
}
