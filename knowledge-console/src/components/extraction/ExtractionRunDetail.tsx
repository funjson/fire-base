import { Ban, Download, FileCheck2 } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import type {
  ExtractionItemDiagnostics,
  ExtractionRunDetail as ExtractionRunDetailValue,
  ExtractionRunItem,
} from '../../lib/api'
import { ErrorState, LoadingState, Panel, StatusBadge } from '../State'
import { ExtractionArtifactPreview } from './ExtractionArtifactPreview'
import { ExtractionConfigComparison } from './ExtractionConfigComparison'
import { ExtractionGateReportPanel } from './ExtractionGateReportPanel'

const cancellableStatuses = new Set(['QUEUED', 'RUNNING'])

/** 展示后端返回的逐文件阶段、耗时、产物数量和稳定错误码。 */
export function ExtractionRunDetail({
  run,
  baseline,
  baselinePending,
  baselineError,
  pending,
  error,
  cancelling,
  cancelError,
  onCancel,
  onDownloadSource,
}: {
  run: ExtractionRunDetailValue | undefined
  baseline: ExtractionRunDetailValue | undefined
  baselinePending: boolean
  baselineError: unknown
  pending: boolean
  error: unknown
  cancelling: boolean
  cancelError: unknown
  onCancel: () => void
  onDownloadSource: (item: ExtractionRunItem) => Promise<void>
}) {
  const [downloadingItemId, setDownloadingItemId] = useState<string>()
  const [downloadError, setDownloadError] = useState<unknown>()

  async function download(item: ExtractionRunItem) {
    setDownloadingItemId(item.id)
    setDownloadError(undefined)
    try {
      await onDownloadSource(item)
    } catch (failure) {
      setDownloadError(failure)
    } finally {
      setDownloadingItemId(undefined)
    }
  }

  const publishing = run?.items.some((item) => item.stage === 'PUBLISHING') ?? false

  return (
    <Panel
      title="Run 详情"
      description={run ? `${run.mode} · ${run.id}` : '选择一次运行查看详情'}
      action={
        run && cancellableStatuses.has(run.status) && !publishing ? (
          <button disabled={cancelling} onClick={onCancel}>
            <Ban size={15} />
            {cancelling ? '请求取消中…' : '取消运行'}
          </button>
        ) : undefined
      }
    >
      {pending && <LoadingState label="正在加载运行详情" />}
      {Boolean(error) && <ErrorState error={error} />}
      {Boolean(cancelError) && <ErrorState error={cancelError} />}
      {Boolean(downloadError) && <ErrorState error={downloadError} />}
      {run && (
        <>
          <div className="extraction-run-overview">
            <RunFact label="状态" value={<StatusBadge value={run.status} />} />
            <RunFact label="Gate" value={<StatusBadge value={run.gateStatus} />} />
            <RunFact label="文件" value={`${run.totalItems}`} />
            <RunFact label="成功" value={`${run.succeededItems}`} />
            <RunFact label="重复跳过" value={`${run.skippedDuplicateItems}`} />
            <RunFact label="失败" value={`${run.failedItems}`} />
            <RunFact label="配置版本" value={`v${run.configVersion}`} />
            <RunFact
              label="完成时间"
              value={run.finishedAt ? formatTime(run.finishedAt) : '—'}
            />
          </div>
          {run.status === 'CANCEL_REQUESTED' && (
            <div className="notice-card extraction-cancel-notice">
              <strong>已接收取消请求</strong>
              <span>Worker 会在当前文件边界安全停止，随后状态转为 CANCELLED。</span>
            </div>
          )}
          {publishing && (
            <div className="notice-card extraction-publishing-notice">
              <strong>正在提交正式数据</strong>
              <span>
                当前文件处于不可安全取消的事务发布窗口，完成或失败后才能再次操作。
              </span>
            </div>
          )}
          {run.errorCode && (
            <div className="notice-card extraction-run-error">
              <strong>运行未完整成功</strong>
              <code>{run.errorCode}</code>
            </div>
          )}
          <ExtractionGateReportPanel
            runStatus={run.status}
            gateStatus={run.gateStatus}
            datasetId={run.datasetId}
            report={run.gateReport}
          />
          <ExtractionConfigComparison
            current={run.configSnapshot}
            baseline={baseline?.configSnapshot}
            baselinePending={baselinePending}
            baselineError={baselineError}
          />
          <div className="extraction-item-table">
            <div className="extraction-item-row header">
              <span>文件</span>
              <span>阶段 / 状态</span>
              <span>Parser / 产物</span>
              <span>Parse / Clean / Chunk</span>
              <span>错误码</span>
            </div>
            {run.items.map((item) => (
              <div className="extraction-item-entry" key={item.id}>
                <div className="extraction-item-row">
                  <div className="extraction-item-file">
                    <FileCheck2 size={16} />
                    <span>
                      <strong>{item.fileName}</strong>
                      <small>
                        {item.mediaType} · {formatBytes(item.contentLength)}
                      </small>
                      {run.mode === 'INGEST' && (
                        <span className="extraction-publication-identity">
                          <small title={item.externalId || undefined}>
                            稳定来源标识 {item.externalId || '—'}
                          </small>
                          <small title={item.title || undefined}>
                            检索标题 {item.title || '—'}
                          </small>
                          <small title="来源质量信号，不是访问权限或 ACL">
                            来源权威度 {item.authority ?? '—'}
                          </small>
                        </span>
                      )}
                      {item.documentId && item.revisionId && (
                        <small title={`${item.documentId} / ${item.revisionId}`}>
                          已发布 · Document {shortId(item.documentId)} · Revision{' '}
                          {shortId(item.revisionId)}
                        </small>
                      )}
                      <button
                        type="button"
                        disabled={Boolean(downloadingItemId)}
                        onClick={() => download(item)}
                      >
                        <Download size={12} />
                        {downloadingItemId === item.id ? '下载中…' : '下载原件'}
                      </button>
                    </span>
                  </div>
                  <div>
                    <code>{item.stage}</code>
                    <StatusBadge value={item.status} />
                  </div>
                  <div>
                    <strong>{item.diagnostics?.parserId || '—'}</strong>
                    <small>
                      Element {numberOrDash(item.diagnostics?.elementCount)} · Chunk{' '}
                      {numberOrDash(item.diagnostics?.chunkCount)}
                    </small>
                    <small title={item.diagnostics?.processorVersion || undefined}>
                      处理契约 {shortContract(item.diagnostics?.processorVersion)}
                    </small>
                  </div>
                  <div className="extraction-duration-list">
                    <span>P {duration(item.diagnostics?.parseDurationMs)}</span>
                    <span>C {duration(item.diagnostics?.cleanDurationMs)}</span>
                    <span>K {duration(item.diagnostics?.chunkDurationMs)}</span>
                  </div>
                  <code className={item.errorCode ? 'error-code' : ''}>
                    {item.errorCode || '无'}
                  </code>
                </div>
                {item.diagnostics && (
                  <ItemDiagnosticsPanel diagnostics={item.diagnostics} />
                )}
                {item.preview ? (
                  <ExtractionArtifactPreview preview={item.preview} />
                ) : item.status === 'SUCCEEDED' ? (
                  <div className="extraction-preview-unavailable">
                    本文件没有可展示的持久化预览，不能根据诊断计数推测正文或边界。
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        </>
      )}
    </Panel>
  )
}

/** 分层展示本次真实运行产生的 Clean 与 Chunk 聚合指标。 */
function ItemDiagnosticsPanel({
  diagnostics,
}: {
  diagnostics: ExtractionItemDiagnostics
}) {
  const cleaningReasons = Object.entries(
    diagnostics.cleaning.reasonCodeCounts,
  ).sort(([left], [right]) => left.localeCompare(right))
  const chunking = diagnostics.chunking
  const boundaryMetrics = [
    ['结构硬边界', chunking.structuralHardBreaks],
    ['Baseline 软边界', chunking.baselineSoftBreaks],
    ['语义待判定边界', chunking.semanticCandidateBoundaries],
    ['CUT 建议', chunking.semanticCutSuggestions],
    ['JOIN 建议', chunking.semanticJoinSuggestions],
    ['中性建议', chunking.semanticNeutralSuggestions],
    ['实际新增 CUT', chunking.semanticCutsAdded],
    ['实际删除软边界', chunking.semanticJoinsApplied],
    ['无操作建议', chunking.semanticNoOps],
    ['Token 硬切', chunking.tokenLimitBreaks],
    ['SourceSpan 硬切', chunking.spanLimitBreaks],
    ['拒绝 JOIN', chunking.rejectedSemanticJoins],
    ['最终 Chunk', chunking.finalChunkCount],
    ['最小计数单位', chunking.minimumChunkUnits],
    ['平均计数单位', formatAverage(chunking.averageChunkUnits)],
    ['最大计数单位', chunking.maximumChunkUnits],
  ] as const

  return (
    <details className="extraction-diagnostics" open>
      <summary>本次分层诊断（只含计数与耗时）</summary>
      <div className="extraction-diagnostics-grid">
        <section>
          <header>Parse</header>
          <Metric label="Parser" value={diagnostics.parserId} />
          <Metric label="产出 Element" value={diagnostics.elementCount} />
          <Metric label="耗时" value={duration(diagnostics.parseDurationMs)} />
        </section>
        <section>
          <header>Clean</header>
          <Metric
            label="可检索 Element"
            value={diagnostics.cleaning.indexableElements}
          />
          <Metric
            label="仅治理 Element"
            value={diagnostics.cleaning.metadataOnlyElements}
          />
          <Metric label="耗时" value={duration(diagnostics.cleanDurationMs)} />
          <div className="extraction-reason-codes">
            <span>原因码</span>
            {cleaningReasons.length === 0 ? (
              <small>无</small>
            ) : (
              cleaningReasons.map(([code, count]) => (
                <code key={code}>{code} · {count}</code>
              ))
            )}
          </div>
        </section>
        <section className="extraction-chunk-diagnostics">
          <header>Chunk · {duration(diagnostics.chunkDurationMs)}</header>
          <div className="extraction-chunk-metrics">
            {boundaryMetrics.map(([label, value]) => (
              <Metric key={label} label={label} value={value} />
            ))}
          </div>
        </section>
      </div>
    </details>
  )
}

function Metric({
  label,
  value,
}: {
  label: string
  value: string | number
}) {
  return (
    <div className="extraction-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function RunFact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function numberOrDash(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : value
}

function shortContract(value: string | null | undefined) {
  if (!value) {
    return '—'
  }
  return value.length > 20 ? `${value.slice(0, 20)}…` : value
}

function shortId(value: string) {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}

function duration(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `${value} ms`
}

function formatAverage(value: number) {
  return Number.isInteger(value) ? value : value.toFixed(2)
}

function formatBytes(value: number) {
  if (value < 1_024) return `${value} B`
  if (value < 1_048_576) return `${(value / 1_024).toFixed(1)} KB`
  return `${(value / 1_048_576).toFixed(1)} MB`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}
