import {
  Activity,
  AlertTriangle,
  BarChart3,
  Clock3,
  Database,
  Fingerprint,
  LoaderCircle,
  RefreshCw,
  Route,
} from 'lucide-react'
import { StatusBadge } from '../State'
import {
  ApiError,
  type RetrievalObservationEvent,
  type RetrievalObservationPurpose,
  type RetrievalObservationReport,
} from '../../lib/api'

type RetrievalObservationPanelProps = {
  report?: RetrievalObservationReport
  loading: boolean
  refreshing: boolean
  error: unknown
  expectedPurpose: 'TEST_PLAZA' | 'ONLINE'
  onRetry: () => void
}

/**
 * 展示一次执行的安全观测事实。协议没有查询正文和模型原始响应字段，本组件也不尝试推断它们。
 */
export function RetrievalObservationPanel({
  report,
  loading,
  refreshing,
  error,
  expectedPurpose,
  onRetry,
}: RetrievalObservationPanelProps) {
  if (loading && !report) {
    return (
      <section
        className="retrieval-observation-panel retrieval-observation-state"
        aria-label="本次执行观测"
        aria-busy="true"
        aria-live="polite"
      >
        <LoaderCircle className="spin" size={20} />
        <div>
          <strong>正在同步本次执行观测</strong>
          <span>检索结果已返回，事件和指标可能稍后写入查询存储。</span>
        </div>
      </section>
    )
  }

  if (error && !report) {
    const pendingStorage = error instanceof ApiError && error.status === 404
    return (
      <section
        className="retrieval-observation-panel retrieval-observation-state warning"
        aria-label="本次执行观测"
        role={pendingStorage ? 'status' : 'alert'}
      >
        <AlertTriangle size={20} />
        <div>
          <strong>
            {pendingStorage ? '观测事件仍在同步' : '暂时无法读取本次执行观测'}
          </strong>
          <span>
            {pendingStorage
              ? '已完成有限次数自动重试。检索结果不受影响，可稍后手动刷新。'
              : error instanceof Error
                ? error.message
                : '观测查询失败，检索结果不受影响。'}
          </span>
        </div>
        <button type="button" onClick={onRetry}>
          <RefreshCw size={13} />
          重新读取
        </button>
      </section>
    )
  }

  if (!report) return null

  const events = [...report.events].sort(
    (left, right) => left.sequence - right.sequence,
  )
  const purposeMismatch = report.purpose !== expectedPurpose

  return (
    <section
      className="retrieval-observation-panel"
      aria-labelledby="retrieval-observation-title"
      aria-busy={refreshing}
    >
      <header className="retrieval-observation-header">
        <div className="retrieval-observation-title">
          <Activity size={18} />
          <div>
            <span>EXECUTION OBSERVABILITY</span>
            <h3 id="retrieval-observation-title">本次执行观测</h3>
            <p>仅展示阶段、计数、配置身份、耗时和指标，不展示查询正文或模型原始响应。</p>
          </div>
        </div>
        <div className="retrieval-observation-actions">
          <span className={`retrieval-purpose ${purposeTone(report.purpose)}`}>
            {report.purpose}
          </span>
          <span
            className={`retrieval-completeness ${report.completeness.toLowerCase()}`}
          >
            {report.completeness}
          </span>
          <button
            type="button"
            onClick={onRetry}
            disabled={refreshing}
            aria-label="刷新本次执行观测"
            title="刷新本次执行观测"
          >
            <RefreshCw className={refreshing ? 'spin' : ''} size={14} />
          </button>
        </div>
      </header>

      <div
        className={`retrieval-observation-isolation${purposeMismatch ? ' warning' : ''}`}
        role={purposeMismatch ? 'alert' : undefined}
      >
        {purposeMismatch ? <AlertTriangle size={15} /> : <Database size={15} />}
        <div>
          <strong>
            {purposeMismatch
              ? `用途不一致：请求期望 ${expectedPurpose}，服务端记录为 ${report.purpose}`
              : isolationTitle(report.purpose)}
          </strong>
          <span>
            {purposeMismatch
              ? '请先检查服务端 purpose 映射，避免测试流量进入在线指标。'
              : isolationDescription(report.purpose)}
          </span>
        </div>
      </div>

      <div className="retrieval-observation-summary">
        <div>
          <Activity size={14} />
          <span>Execution</span>
          <code title={report.executionId}>{shortId(report.executionId)}</code>
        </div>
        <div>
          <Route size={14} />
          <span>Space visits</span>
          <strong>{report.visitedConfigurations.length}</strong>
        </div>
        <div>
          <Clock3 size={14} />
          <span>事件</span>
          <strong>{report.eventCount}</strong>
        </div>
        <div>
          <BarChart3 size={14} />
          <span>运行指标</span>
          <strong>{report.metrics.length}</strong>
        </div>
      </div>

      {report.incompleteReasons.length > 0 && (
        <div className="retrieval-observation-incomplete" role="status">
          <AlertTriangle size={15} />
          <div>
            <strong>事件集合不完整</strong>
            <span>{report.incompleteReasons.join(' · ')}</span>
          </div>
        </div>
      )}

      <div className="retrieval-observation-content">
        <section className="retrieval-observation-visits" aria-labelledby="visit-title">
          <header>
            <Route size={15} />
            <h4 id="visit-title">Space 与配置访问</h4>
          </header>
          {report.visitedConfigurations.length > 0 ? (
            <ol>
              {report.visitedConfigurations.map((visit) => (
                <li key={`${visit.visitIndex}:${visit.spaceId}`}>
                  <span className="retrieval-visit-index">{visit.visitIndex}</span>
                  <div>
                    <strong>{visit.spaceId}</strong>
                    <span>来源修订 {visit.sourceRevision}</span>
                    <code title={visit.fingerprint}>{visit.fingerprint}</code>
                  </div>
                </li>
              ))}
            </ol>
          ) : (
            <p className="retrieval-observation-empty">尚无已解析的 Space 配置。</p>
          )}
        </section>

        <section className="retrieval-observation-timeline" aria-labelledby="timeline-title">
          <header>
            <Activity size={15} />
            <h4 id="timeline-title">分层执行时间线</h4>
          </header>
          {events.length > 0 ? (
            <ol>
              {events.map((event) => (
                <ObservationEventRow event={event} key={event.sequence} />
              ))}
            </ol>
          ) : (
            <p className="retrieval-observation-empty">本次执行尚无可展示事件。</p>
          )}
        </section>
      </div>

      <section className="retrieval-observation-metrics" aria-labelledby="metrics-title">
        <header>
          <BarChart3 size={15} />
          <h4 id="metrics-title">运行指标</h4>
          <span>按 purpose 与配置指纹等维度隔离</span>
        </header>
        {report.metrics.length > 0 ? (
          <div className="retrieval-observation-metric-grid">
            {report.metrics.map((metric, index) => (
              <article key={`${metric.metricKey}:${metric.aggregation}:${index}`}>
                <header>
                  <code>{metric.metricKey}</code>
                  <span>{metric.aggregation}</span>
                </header>
                <strong>{formatMetric(metric.value)}</strong>
                <div className="retrieval-metric-dimensions">
                  {definedDimensions(metric.dimensions).map(([key, value]) => (
                    <span key={key}>
                      {key}=<code>{value}</code>
                    </span>
                  ))}
                  {definedDimensions(metric.dimensions).length === 0 && (
                    <span>无附加维度</span>
                  )}
                </div>
                <time dateTime={metric.observedAt}>
                  {formatDateTime(metric.observedAt)}
                </time>
              </article>
            ))}
          </div>
        ) : (
          <p className="retrieval-observation-empty">指标投影尚未产生运行事实。</p>
        )}
      </section>
    </section>
  )
}

function ObservationEventRow({ event }: { event: RetrievalObservationEvent }) {
  return (
    <li className="retrieval-observation-event">
      <span className="retrieval-event-sequence" aria-label={`序号 ${event.sequence}`}>
        {event.sequence}
      </span>
      <div className="retrieval-event-body">
        <header>
          <strong>{event.stage}</strong>
          <StatusBadge value={event.status} />
          <span>{formatDuration(event.durationMillis)}</span>
        </header>
        <div className="retrieval-event-facts">
          <span>visit {event.visitIndex}</span>
          <span>attempt {event.attemptIndex}</span>
          <span aria-label={`输入 ${event.inputCount}，输出 ${event.outputCount}`}>
            I/O {event.inputCount} → {event.outputCount}
          </span>
          {event.reasonCode !== 'NONE' && <code>{event.reasonCode}</code>}
        </div>
        <div className="retrieval-event-footer">
          <span>
            <Fingerprint size={12} />
            <code title={event.configFingerprint}>
              {event.configFingerprint === 'UNRESOLVED'
                ? 'UNRESOLVED'
                : shortFingerprint(event.configFingerprint)}
            </code>
          </span>
          <time dateTime={event.startedAt}>{formatDateTime(event.startedAt)}</time>
        </div>
      </div>
    </li>
  )
}

function purposeTone(purpose: RetrievalObservationPurpose): string {
  if (purpose === 'TEST_PLAZA' || purpose === 'EVALUATION') return 'test'
  if (purpose === 'ONLINE') return 'online'
  return 'neutral'
}

function isolationTitle(purpose: RetrievalObservationPurpose): string {
  return purpose === 'TEST_PLAZA'
    ? 'TEST_PLAZA 与 ONLINE 指标严格隔离'
    : purpose === 'ONLINE'
      ? 'ONLINE 与测试广场指标严格隔离'
      : `${purpose} 使用独立用途维度`
}

function isolationDescription(purpose: RetrievalObservationPurpose): string {
  return purpose === 'TEST_PLAZA'
    ? '本次原始事件和派生指标仅进入测试广场用途，不并入在线请求聚合。'
    : purpose === 'ONLINE'
      ? '本次事实进入在线用途；测试广场的调优流量不会并入该口径。'
      : '聚合和外部可观测适配器必须保留 purpose 维度。'
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}

function shortFingerprint(value: string): string {
  return value.length > 16 ? `${value.slice(0, 12)}…${value.slice(-4)}` : value
}

function formatDuration(value: number): string {
  if (value < 1) return `${value.toFixed(2)} ms`
  if (value < 1_000) return `${value.toFixed(0)} ms`
  return `${(value / 1_000).toFixed(2)} s`
}

function formatMetric(value: number): string {
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: 4,
  }).format(value)
}

function definedDimensions(
  dimensions: Record<string, string | null>,
): Array<[string, string]> {
  return Object.entries(dimensions).filter(
    (entry): entry is [string, string] => entry[1] !== null,
  )
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}
