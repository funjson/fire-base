import { Route } from 'lucide-react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { RetrievalStageDiagnostic } from '../../lib/api'
import { StatusBadge } from '../State'
import { formatDecimal, formatMillis, formatPercent } from './metric-formatters'

/** 只使用实际执行阶段绘制耗时分布，不为未执行阶段补零。 */
export function StageLatencyChart({ rows }: { rows: RetrievalStageDiagnostic[] }) {
  const chartRows = rows
    .filter((row) => row.latencyMillis.sampleCount > 0)
    .map((row) => ({
      stage: stageLabel(row.stage),
      p50: row.latencyMillis.p50,
      p95: row.latencyMillis.p95,
    }))
  if (chartRows.length === 0) {
    return <InlineEmpty text="没有有效的阶段耗时样本" />
  }
  return (
    <div className="observability-chart" aria-label="阶段耗时分布图">
      <ResponsiveContainer width="100%" height={Math.max(300, chartRows.length * 36)}>
        <BarChart data={chartRows} layout="vertical" margin={{ left: 28, right: 18 }}>
          <CartesianGrid stroke="#1b2a43" strokeDasharray="4 4" />
          <XAxis type="number" stroke="#71839f" fontSize={9} unit=" ms" />
          <YAxis type="category" dataKey="stage" width={110} stroke="#71839f" fontSize={9} />
          <Tooltip
            contentStyle={{
              background: '#111c31',
              border: '1px solid #263654',
              borderRadius: 10,
              fontSize: 10,
            }}
          />
          <Legend wrapperStyle={{ fontSize: 10 }} />
          <Bar dataKey="p50" name="P50" fill="#77a8ff" radius={[0, 4, 4, 0]} />
          <Bar dataKey="p95" name="P95" fill="#f4b860" radius={[0, 4, 4, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** 阶段矩阵按每个可选阶段自身的执行样本计算成功率。 */
export function StageMatrix({ rows }: { rows: RetrievalStageDiagnostic[] }) {
  if (rows.length === 0) return <InlineEmpty text="没有阶段执行事实" />
  return (
    <div className="stage-matrix">
      {rows.map((row) => (
        <article key={row.stage}>
          <div>
            <strong>{stageLabel(row.stage)}</strong>
            <span>
              {row.executionCount} 次执行 · {row.eventCount} 个事件
              {row.metricDefinitionVersion === null
                ? ' · 定义版本未记录'
                : ` · 指标定义 v${row.metricDefinitionVersion}`}
            </span>
          </div>
          <StatusBadge value={formatPercent(row.successRate.value)} />
          <small>
            成功 {row.successRate.numerator}/{row.successRate.denominator} · P95 {formatMillis(row.latencyMillis.p95)} · 平均 I/O {formatDecimal(row.averageInputCount)} → {formatDecimal(row.averageOutputCount)}
          </small>
        </article>
      ))}
    </div>
  )
}

export function InlineEmpty({ text }: { text: string }) {
  return <div className="observability-inline-empty"><Route size={17} />{text}</div>
}

function stageLabel(value: string) {
  const labels: Record<string, string> = {
    EXECUTION_STARTED: '执行启动',
    SPACE_ROUTING: 'Space 路由',
    CONFIGURATION_RESOLVED: '配置解析',
    QUERY_ANALYSIS: '查询分析',
    QUERY_PLANNING: '查询规划',
    RETRIEVAL_PLAN: '召回计划',
    RETRIEVAL_BRANCH: '召回分支',
    FUSION: 'RRF 融合',
    RERANK: '重排序',
    COVERAGE_CHECK: 'Coverage 检查',
    CHAIN_NODE_EVALUATED: 'Chain 节点评估',
    CHAIN_NODE_COMPLETED: 'Chain 节点完成',
    SPACE_CHANGED: 'Space 切换',
    EVIDENCE_BUILD: '证据构建',
    EXECUTION_TERMINAL: '执行终态',
    STAGE_FAILURE: '阶段失败',
  }
  return labels[value] ?? value
}
