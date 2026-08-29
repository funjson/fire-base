import { X } from 'lucide-react'
import type { RetrievalObservationReport } from '../../lib/api'
import { RetrievalObservationPanel } from '../retrieval/RetrievalObservationPanel'
import { shortExecutionId } from './execution-record-formatters'

/** 单次执行详情沿用安全观测报告，不在抽屉中引入查询或候选正文。 */
export function ExecutionDetailDrawer({
  requestId,
  report,
  loading,
  refreshing,
  error,
  onClose,
  onRetry,
}: {
  requestId: string
  report?: RetrievalObservationReport
  loading: boolean
  refreshing: boolean
  error: unknown
  onClose: () => void
  onRetry: () => void
}) {
  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside
        className="drawer observability-execution-drawer"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <div>
            <span className="eyebrow">ONLINE EXECUTION DETAIL</span>
            <h2>Request {shortExecutionId(requestId)}</h2>
            <p className="mono" title={requestId}>{requestId}</p>
          </div>
          <button
            type="button"
            className="icon-button"
            aria-label="关闭执行详情"
            onClick={onClose}
          >
            <X size={18} />
          </button>
        </header>
        <RetrievalObservationPanel
          report={report}
          loading={loading}
          refreshing={refreshing}
          error={error}
          expectedPurpose="ONLINE"
          onRetry={onRetry}
        />
      </aside>
    </div>
  )
}
