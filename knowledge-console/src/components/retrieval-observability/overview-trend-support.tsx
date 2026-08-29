import { EmptyState } from '../State'
import { formatMillis, formatPercent } from './metric-formatters'
import type { TrendPoint } from './overview-trend-model'

/** 比率 Tooltip 同时呈现分母，避免脱离样本规模解释百分比。 */
export function MetricTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: Array<{
    name?: string
    value?: number | null
    color?: string
    dataKey?: string
    payload?: TrendPoint
  }>
  label?: string | number
}) {
  if (!active || !payload?.length) return null
  return (
    <div className="observability-chart-tooltip">
      <strong>{label ? formatTime(label) : '时间桶'}</strong>
      {payload.map((item) => {
        const denominator = item.dataKey
          ? item.payload?.denominators[item.dataKey]
          : undefined
        return (
          <span key={item.name} style={{ color: item.color }}>
            {item.name}：{item.dataKey === 'requestCount'
              ? `${item.value ?? 0} 次`
              : formatPercent(item.value ?? null)}
            {denominator === undefined ? '' : ` · n=${denominator}`}
          </span>
        )
      })}
    </div>
  )
}

/** 延迟 Tooltip 明确携带形成终态的样本数。 */
export function LatencyTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: Array<{
    name?: string
    value?: number | null
    color?: string
    payload?: TrendPoint
  }>
  label?: string | number
}) {
  if (!active || !payload?.length) return null
  const sampleCount = payload[0]?.payload?.latencySampleCount
  return (
    <div className="observability-chart-tooltip">
      <strong>{label ? formatTime(label) : '时间桶'}</strong>
      {payload.map((item) => (
        <span key={item.name} style={{ color: item.color }}>
          {item.name}：{formatMillis(item.value ?? null)}
          {sampleCount === undefined ? '' : ` · n=${sampleCount}`}
        </span>
      ))}
    </div>
  )
}

export function ChartEmpty() {
  return (
    <EmptyState
      title="没有可绘制的时间序列"
      description="当前聚合结果没有有效时间桶；指标卡仍保留窗口级事实。"
    />
  )
}

function formatTime(value: string | number) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
