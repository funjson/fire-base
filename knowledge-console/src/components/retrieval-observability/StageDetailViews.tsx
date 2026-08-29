import { Activity, BrainCircuit, GitMerge, Search } from 'lucide-react'
import type {
  RetrievalBranchDiagnostic,
  RetrievalChainDiagnostic,
  RetrievalObservabilityPercentiles,
  RetrievalObservabilityRate,
  RetrievalStageDiagnostics,
} from '../../lib/api'
import { formatDecimal, formatMillis, formatPercent } from './metric-formatters'
import { InlineEmpty } from './StageExecutionViews'

/** 召回分支按策略、通道、组件和索引版本保持独立口径。 */
export function BranchDiagnosticsTable({
  rows,
}: {
  rows: RetrievalBranchDiagnostic[]
}) {
  if (rows.length === 0) {
    return <InlineEmpty text="当前窗口没有召回分支事实" />
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>策略</th>
            <th>召回通道</th>
            <th>组件 / 模型</th>
            <th>索引版本</th>
            <th>执行</th>
            <th>成功率</th>
            <th>空结果率</th>
            <th>平均候选</th>
            <th>P95</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${row.strategy}-${row.channel}-${row.componentModel}-${row.dataIndexVersion}-${index}`}>
              <td><strong>{strategyLabel(row.strategy)}</strong></td>
              <td>{strategyLabel(row.channel)}</td>
              <td className="mono">{row.componentModel || '—'}</td>
              <td className="mono">{row.dataIndexVersion || '—'}</td>
              <td>{row.executionCount} 次</td>
              <RateCell metric={row.successRate} />
              <RateCell metric={row.emptyRate} warning />
              <td>{formatDecimal(row.averageCandidateCount)}</td>
              <td>{percentileCell(row.latencyMillis)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** 融合、重排序和 Coverage 使用各自的稳定事实集合呈现。 */
export function SpecialStageCards({ value }: { value: RetrievalStageDiagnostics }) {
  return (
    <div className="observability-stage-cards">
      <StageFactCard
        icon={GitMerge}
        title="RRF 融合"
        samples={value.fusion.executionCount}
        facts={[
          ['重复率', rateWithSample(value.fusion.duplicateRate)],
          ['平均输入候选', formatDecimal(value.fusion.averageInputCandidateCount)],
          ['平均唯一候选', formatDecimal(value.fusion.averageUniqueCandidateCount)],
          ['P95 耗时', formatMillis(value.fusion.latencyMillis.p95)],
        ]}
      />
      <StageFactCard
        icon={BrainCircuit}
        title="Reranker"
        samples={value.rerank.executionCount}
        facts={[
          ['执行率', rateWithSample(value.rerank.executedRate)],
          ['回退率', rateWithSample(value.rerank.fallbackRate)],
          ['候选变化', `${formatDecimal(value.rerank.averageInputCandidateCount)} → ${formatDecimal(value.rerank.averageOutputCandidateCount)}`],
          ['模型请求', `${value.rerank.modelRequestCount} 次`],
        ]}
      />
      <StageFactCard
        icon={Search}
        title="Coverage Judge"
        truth="proxy"
        samples={value.coverage.measuredCount}
        facts={[
          ['证据充分率', rateWithSample(value.coverage.sufficientRate)],
          ['平均代理分数', formatDecimal(value.coverage.averageScore, 3)],
          ['平均保留候选', formatDecimal(value.coverage.averageRetainedCandidateCount)],
          ['模型请求', `${value.coverage.modelRequestCount} 次`],
        ]}
      />
    </div>
  )
}

/** 优化 Chain 只展示节点自身的执行样本与代理增益。 */
export function ChainDiagnosticsTable({
  rows,
}: {
  rows: RetrievalChainDiagnostic[]
}) {
  if (rows.length === 0) {
    return <InlineEmpty text="当前窗口没有执行 Chain 节点" />
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>节点</th>
            <th>策略</th>
            <th>执行</th>
            <th>事件</th>
            <th>正向代理增益</th>
            <th>平均 Coverage 增量</th>
            <th>模型请求</th>
            <th>P95</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${row.node}-${row.strategy}-${index}`}>
              <td><strong>{chainNodeLabel(row.node)}</strong></td>
              <td>{strategyLabel(row.strategy)}</td>
              <td>{row.executionCount}</td>
              <td>{row.eventCount}</td>
              <RateCell metric={row.positiveGainRate} proxy />
              <td>{formatSigned(row.averageCoverageDelta)}</td>
              <td>{row.modelRequestCount}</td>
              <td>{formatMillis(row.latencyMillis.p95)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function StageFactCard({
  icon: Icon,
  title,
  samples,
  facts,
  truth = 'runtime',
}: {
  icon: typeof Activity
  title: string
  samples: number
  facts: Array<[string, string]>
  truth?: 'runtime' | 'proxy'
}) {
  return (
    <article className="observability-stage-card">
      <header>
        <div><Icon size={17} /><strong>{title}</strong></div>
        <span className={`truth-badge ${truth}`}>{truth === 'runtime' ? '运行事实' : '代理质量'}</span>
      </header>
      <small>{samples} 个有效样本</small>
      <div>
        {facts.map(([label, value]) => (
          <span key={label}><small>{label}</small><strong>{value}</strong></span>
        ))}
      </div>
    </article>
  )
}

function RateCell({
  metric,
  warning = false,
  proxy = false,
}: {
  metric: RetrievalObservabilityRate
  warning?: boolean
  proxy?: boolean
}) {
  return (
    <td>
      <span className={`metric-table-value${warning ? ' warning' : ''}${proxy ? ' proxy' : ''}`}>
        {formatPercent(metric.value)}
      </span>
      <small>{metric.numerator}/{metric.denominator}</small>
    </td>
  )
}

function rateWithSample(metric: RetrievalObservabilityRate) {
  return `${formatPercent(metric.value)} · ${metric.numerator}/${metric.denominator}`
}

function percentileCell(metric: RetrievalObservabilityPercentiles) {
  return `${formatMillis(metric.p95)} · n=${metric.sampleCount}`
}

function formatSigned(value: number | null) {
  if (value === null) return '暂无样本'
  return `${value > 0 ? '+' : ''}${value.toFixed(3)}`
}

function strategyLabel(value: string) {
  if (!value) return '未标记'
  return value.replaceAll('_', ' ')
}

function chainNodeLabel(value: string) {
  const labels: Record<string, string> = {
    GAP_QUERY: '缺口查询',
    PRF: '伪相关反馈',
    RELAX_CONSTRAINTS: '放宽约束',
    NARROW_CONSTRAINTS: '收紧约束',
    STEP_BACK: '上位问题',
    HYDE: '假设文档',
    NEXT_SPACE: '切换 Space',
  }
  return labels[value] ?? value
}
