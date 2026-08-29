import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import type { RetrievalObservabilityFilter } from '../../lib/api'
import { useApi } from '../../lib/use-api'
import { EmptyState, ErrorState, LoadingState, Panel } from '../State'
import { ExecutionDetailDrawer } from './ExecutionDetailDrawer'
import { ExecutionRecordsTable } from './ExecutionRecordsTable'
import { ExecutionStatusFilters } from './ExecutionStatusFilters'

type ExecutionRecordsProps = {
  filter: RetrievalObservabilityFilter
  rangeLabel: string
  refreshKey: number
}

/** 执行列表只编排安全读请求和 URL 状态；正文不会进入列表或 URL。 */
export function ExecutionRecords({
  filter,
  rangeLabel,
  refreshKey,
}: ExecutionRecordsProps) {
  const api = useApi()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = nonNegativeInteger(searchParams.get('page'))
  const terminalStatus = searchParams.get('terminalStatus') || undefined
  const stopReason = searchParams.get('stopReason') || undefined
  const selectedRequestId = searchParams.get('requestId') || undefined
  const executionFilter = {
    ...filter,
    page,
    size: 25,
    terminalStatus,
    stopReason,
  }
  const executions = useQuery({
    queryKey: [
      'retrieval-observability',
      'online',
      'executions',
      executionFilter,
      refreshKey,
    ],
    queryFn: () => api.retrievalExecutions(executionFilter),
  })
  const detail = useQuery({
    queryKey: ['retrieval-observation', 'request', selectedRequestId],
    queryFn: () => api.retrievalObservationByRequest(selectedRequestId!),
    enabled: Boolean(selectedRequestId),
  })

  const applyStatusFilters = (
    nextTerminalStatus: string,
    nextStopReason: string,
  ) => {
    const next = new URLSearchParams(searchParams)
    if (nextTerminalStatus.trim()) {
      next.set('terminalStatus', nextTerminalStatus.trim())
    } else {
      next.delete('terminalStatus')
    }
    if (nextStopReason.trim()) next.set('stopReason', nextStopReason.trim())
    else next.delete('stopReason')
    next.delete('page')
    next.delete('requestId')
    setSearchParams(next)
  }
  const changePage = (nextPage: number) => {
    const next = new URLSearchParams(searchParams)
    if (nextPage > 0) next.set('page', String(nextPage))
    else next.delete('page')
    next.delete('requestId')
    setSearchParams(next)
  }
  const selectRequest = (requestId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (requestId) next.set('requestId', requestId)
    else next.delete('requestId')
    setSearchParams(next)
  }

  return (
    <>
      <div className="observability-section-heading">
        <div>
          <span className="truth-badge runtime">运行事实</span>
          <h3>ONLINE 执行记录</h3>
        </div>
        <p>{rangeLabel} · 每条请求只计算一次</p>
      </div>

      <Panel>
        <ExecutionStatusFilters
          key={`${terminalStatus ?? ''}:${stopReason ?? ''}`}
          terminalStatus={terminalStatus}
          stopReason={stopReason}
          onApply={applyStatusFilters}
        />

        {executions.isPending && <LoadingState label="正在加载在线执行记录" />}
        {executions.error && <ErrorState error={executions.error} />}
        {executions.data && executions.data.items.length === 0 && (
          <EmptyState
            title="没有符合条件的执行记录"
            description="可以清除终态筛选、扩大时间窗口或切换到全部 Space。"
          />
        )}
        {executions.data && executions.data.items.length > 0 && (
          <ExecutionRecordsTable
            value={executions.data}
            onSelectRequest={(requestId) => selectRequest(requestId)}
            onChangePage={changePage}
          />
        )}
      </Panel>

      {selectedRequestId && (
        <ExecutionDetailDrawer
          requestId={selectedRequestId}
          report={detail.data}
          loading={detail.isPending}
          refreshing={detail.isFetching && !detail.isPending}
          error={detail.error}
          onClose={() => selectRequest()}
          onRetry={() => void detail.refetch()}
        />
      )}
    </>
  )
}

function nonNegativeInteger(value: string | null) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0
}
