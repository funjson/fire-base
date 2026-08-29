import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Gauge,
  Route,
  Timer,
} from 'lucide-react'
import type {
  OnlineRetrievalOverview,
  RetrievalDimensionCount,
  RetrievalObservabilityPercentiles,
  RetrievalObservabilityRate,
} from '../../lib/api'
import { formatMillis, formatPercent } from './metric-formatters'

/** 在线运行指标摘要，保持运行事实与模型代理质量的视觉边界。 */
export function RuntimeMetricSummary({
  value,
  rangeLabel,
}: {
  value: OnlineRetrievalOverview
  rangeLabel: string
}) {
  return (
    <section aria-labelledby="runtime-facts-title">
      <div className="observability-section-heading">
        <div>
          <span className="truth-badge runtime">运行事实</span>
          <h3 id="runtime-facts-title">在线健康</h3>
        </div>
        <p>{rangeLabel} · {value.requestCount} 个已观测执行</p>
      </div>
      <div className="observability-metric-grid">
        <MetricCard
          icon={Activity}
          label="请求量"
          value={value.requestCount.toLocaleString('zh-CN')}
          detail={`${value.requestCount} 个已观测执行 · 包含近期和不完整执行`}
          tone="blue"
        />
        <RateMetric
          icon={CheckCircle2}
          label="技术成功率"
          metric={value.technicalSuccessRate}
          sampleLabel="已形成终态执行"
        />
        <RateMetric
          icon={AlertTriangle}
          label="降级率"
          metric={value.degradedRate}
          sampleLabel="已形成终态执行"
          inverse
        />
        <LatencyMetric metric={value.endToEndLatencyMillis} />
        <RateMetric
          icon={Route}
          label="终态形成率"
          metric={value.terminalObservationRate}
          sampleLabel="成熟执行"
        />
        <RateMetric
          icon={Gauge}
          label="观测完整率"
          metric={value.observationCompleteRate}
          sampleLabel="成熟执行"
        />
      </div>
      <div className="observability-maturity-note">
        <Timer size={14} />
        <span>
          最新请求立即进入请求量；只有超过检索超时和 30 秒事件投递宽限的成熟执行，
          才进入终态形成率与观测完整率分母。
        </span>
      </div>
    </section>
  )
}

/** Coverage Judge 仅作为在线代理质量展示，不能替代 Gold 指标。 */
export function ProxyMetricSummary({ value }: { value: OnlineRetrievalOverview }) {
  return (
    <section aria-labelledby="proxy-quality-title">
      <div className="observability-section-heading">
        <div>
          <span className="truth-badge proxy">代理质量</span>
          <h3 id="proxy-quality-title">证据充分趋势</h3>
        </div>
        <p>模型 Judge 信号，只用于诊断趋势，不等同于 Gold Recall</p>
      </div>
      <div className="observability-metric-grid proxy-grid">
        <RateMetric
          icon={Route}
          label="首轮证据充分率"
          metric={value.firstCoverageSufficientRate}
          truth="proxy"
        />
        <RateMetric
          icon={CheckCircle2}
          label="最终证据充分率"
          metric={value.finalCoverageSufficientRate}
          truth="proxy"
        />
        <RateMetric
          icon={Activity}
          label="优化恢复率"
          metric={value.coverageRecoveryRate}
          truth="proxy"
        />
        <RateMetric
          icon={AlertTriangle}
          label="预算耗尽率"
          metric={value.budgetExhaustedRate}
          truth="proxy"
          inverse
        />
      </div>
    </section>
  )
}

/** 明确提示同一统计窗口是否混入不同配置或索引版本。 */
export function VersionScopeNotice({
  requestCount,
  configFingerprints,
  dataIndexVersions,
}: {
  requestCount: number
  configFingerprints: RetrievalDimensionCount[]
  dataIndexVersions: RetrievalDimensionCount[]
}) {
  const mixed = configFingerprints.length > 1 || dataIndexVersions.length > 1
  const versionsMissing =
    requestCount > 0 &&
    (configFingerprints.length === 0 || dataIndexVersions.length === 0)
  if (requestCount === 0) return null
  return (
    <section className={`observability-version-scope${mixed || versionsMissing ? ' mixed' : ''}`}>
      <AlertTriangle size={16} />
      <div>
        <strong>
          {versionsMissing
            ? '当前执行的版本身份不完整'
            : mixed
              ? '当前曲线包含多个配置或索引版本'
              : '当前版本口径'}
        </strong>
        <span>
          {versionsMissing
            ? '无法确认窗口内配置和索引是否一致，请先检查观测事件完整性，再解释指标变化。'
            : mixed
              ? '混合曲线只用于运行排障；评估能力变化前，请在高级筛选中限定单一配置与索引版本。'
              : '当前窗口未发生跨版本混合。'}
        </span>
        <div>
          {configFingerprints.map((item) => (
            <code key={`config-${item.value}`} title={item.value}>
              配置 {shortVersion(item.value)} · {item.count}
            </code>
          ))}
          {dataIndexVersions.map((item) => (
            <code key={`index-${item.value}`} title={item.value}>
              索引 {shortVersion(item.value)} · {item.count}
            </code>
          ))}
        </div>
      </div>
    </section>
  )
}

function RateMetric({
  icon,
  label,
  metric,
  truth = 'runtime',
  inverse = false,
  sampleLabel = '有效样本',
}: {
  icon: typeof Activity
  label: string
  metric: RetrievalObservabilityRate
  truth?: 'runtime' | 'proxy'
  inverse?: boolean
  sampleLabel?: string
}) {
  return (
    <MetricCard
      icon={icon}
      label={label}
      value={formatPercent(metric.value)}
      detail={`${metric.numerator.toLocaleString('zh-CN')} / ${metric.denominator.toLocaleString('zh-CN')} 个${sampleLabel}`}
      tone={metric.value === null ? 'neutral' : inverse ? 'warning' : truth === 'proxy' ? 'proxy' : 'accent'}
    />
  )
}

function LatencyMetric({ metric }: { metric: RetrievalObservabilityPercentiles }) {
  return (
    <MetricCard
      icon={Timer}
      label="端到端 P95"
      value={formatMillis(metric.p95)}
      detail={`${metric.sampleCount.toLocaleString('zh-CN')} 个已形成终态的执行样本 · P50 ${formatMillis(metric.p50)}`}
      tone="blue"
    />
  )
}

function MetricCard({
  icon: Icon,
  label,
  value,
  detail,
  tone,
}: {
  icon: typeof Activity
  label: string
  value: string
  detail: string
  tone: 'accent' | 'blue' | 'warning' | 'proxy' | 'neutral'
}) {
  return (
    <article className={`observability-metric-card ${tone}`}>
      <div>
        <Icon size={16} />
        <span>{label}</span>
      </div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  )
}

function shortVersion(value: string) {
  return value.length > 14 ? value.slice(0, 14) : value
}
