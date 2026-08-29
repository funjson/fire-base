import { Clock3 } from 'lucide-react'
import type { ExtractionRunSummary } from '../../lib/api'
import { EmptyState, ErrorState, LoadingState, Panel, StatusBadge } from '../State'

/** Space 内共享的抽取试验与正式摄取运行列表。 */
export function ExtractionRunList({
  runs,
  selectedRunId,
  pending,
  error,
  onSelect,
}: {
  runs: ExtractionRunSummary[] | undefined
  selectedRunId: string | undefined
  pending: boolean
  error: unknown
  onSelect: (runId: string) => void
}) {
  return (
    <Panel title="抽取与摄取 Runs" description="TEST_ONLY 与 INGEST 状态均由后端实时返回。">
      {pending && <LoadingState label="正在加载抽取运行" />}
      {Boolean(error) && <ErrorState error={error} />}
      {runs && runs.length > 0 && (
        <div className="extraction-run-list">
          {runs.map((run) => (
            <button
              className={run.id === selectedRunId ? 'active' : ''}
              key={run.id}
              onClick={() => onSelect(run.id)}
            >
              <div>
                <StatusBadge value={run.status} />
                <span className={`extraction-run-mode ${run.mode.toLowerCase()}`}>
                  {run.mode === 'INGEST' ? '正式摄取' : '抽取测试'}
                </span>
                <strong>{formatTime(run.createdAt)}</strong>
              </div>
              <span>
                {run.totalItems} 文件 · 成功 {run.succeededItems} · 失败{' '}
                {run.failedItems} · 去重 {run.skippedDuplicateItems}
              </span>
              <small>
                <Clock3 size={12} /> 配置 v{run.configVersion} ·{' '}
                Gate {run.gateStatus} · {run.id.slice(0, 8)}
              </small>
            </button>
          ))}
        </div>
      )}
      {!pending && !error && runs?.length === 0 && (
        <EmptyState
          title="尚无抽取运行"
          description="可先运行 TEST_ONLY 验证质量，也可创建正式多文件摄取。"
        />
      )}
    </Panel>
  )
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
