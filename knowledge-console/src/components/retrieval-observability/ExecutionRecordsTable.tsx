import { ChevronLeft, ChevronRight, Eye } from 'lucide-react'
import type { RetrievalExecutionPage } from '../../lib/api'
import { StatusBadge } from '../State'
import {
  formatExecutionDuration,
  formatExecutionTime,
  nullableExecutionCount,
  shortExecutionId,
} from './execution-record-formatters'

/** ONLINE 执行表只展示安全标识、状态与计数，不接收查询正文。 */
export function ExecutionRecordsTable({
  value,
  onSelectRequest,
  onChangePage,
}: {
  value: RetrievalExecutionPage
  onSelectRequest: (requestId: string) => void
  onChangePage: (page: number) => void
}) {
  return (
    <>
      <div className="table-wrap">
        <table className="execution-record-table">
          <thead>
            <tr>
              <th>Request / Execution</th>
              <th>开始时间</th>
              <th>Space</th>
              <th>技术状态</th>
              <th>业务终态</th>
              <th>耗时</th>
              <th>尝试 / 结果</th>
              <th>观测</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {value.items.map((item) => (
              <tr key={item.executionId}>
                <td>
                  <code title={item.requestId}>R {shortExecutionId(item.requestId)}</code>
                  <code title={item.executionId}>E {shortExecutionId(item.executionId)}</code>
                </td>
                <td>
                  {formatExecutionTime(item.startedAt)}
                  <small>
                    {item.completedAt
                      ? `完成 ${formatExecutionTime(item.completedAt)}`
                      : `最后观测 ${formatExecutionTime(item.lastObservedAt)}`}
                  </small>
                </td>
                <td>
                  <div className="execution-space-list">
                    {item.spaceIds.length > 0
                      ? item.spaceIds.map((spaceId) => (
                          <code key={spaceId} title={spaceId}>{shortExecutionId(spaceId)}</code>
                        ))
                      : <span>未记录</span>}
                  </div>
                </td>
                <td>
                  <StatusBadge value={item.technicalStatus ?? '进行中'} />
                </td>
                <td>
                  <StatusBadge value={item.terminalStatus ?? '终态缺失'} />
                  {item.stopReason && <small>{item.stopReason}</small>}
                </td>
                <td>{formatExecutionDuration(item.durationMillis)}</td>
                <td>
                  {nullableExecutionCount(item.attemptCount)} / {nullableExecutionCount(item.resultCount)}
                </td>
                <td>
                  <span className={`execution-completeness ${item.completeness.toLowerCase()}`}>
                    {item.completeness}
                  </span>
                  <small>
                    {item.eventCount} 个事件
                    {item.degraded === true ? ' · 已降级' : ''}
                    {item.degraded === null ? ' · 降级状态未形成' : ''}
                  </small>
                </td>
                <td>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`查看 Request ${item.requestId} 的执行详情`}
                    title="查看安全执行详情"
                    onClick={() => onSelectRequest(item.requestId)}
                  >
                    <Eye size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="observability-pagination">
        <span>
          共 {value.totalItems.toLocaleString('zh-CN')} 条 · 第 {value.page + 1}/{Math.max(value.totalPages, 1)} 页
        </span>
        <div>
          <button
            type="button"
            className="secondary-button"
            disabled={value.page <= 0}
            onClick={() => onChangePage(value.page - 1)}
          >
            <ChevronLeft size={14} />上一页
          </button>
          <button
            type="button"
            className="secondary-button"
            disabled={value.page + 1 >= value.totalPages}
            onClick={() => onChangePage(value.page + 1)}
          >
            下一页<ChevronRight size={14} />
          </button>
        </div>
      </div>
    </>
  )
}
