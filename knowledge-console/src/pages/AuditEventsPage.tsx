import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { EmptyState, ErrorState, LoadingState, Panel, StatusBadge } from '../components/State'
import { useApi } from '../lib/use-api'

const pageSize = 50

export function AuditEventsPage() {
  const api = useApi()
  const [offset, setOffset] = useState(0)
  const events = useQuery({
    queryKey: ['audit-events', offset],
    queryFn: () => api.auditEvents(pageSize, offset),
  })

  if (events.isPending) return <LoadingState />
  if (events.error) return <ErrorState error={events.error} />

  const page = events.data
  const first = page.total === 0 ? 0 : page.offset + 1
  const last = Math.min(page.offset + page.items.length, page.total)

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SAFE MUTATION AUDIT</span>
          <h2>租户操作审计</h2>
          <p>只记录认证主体、路由模板和执行结果，不保存请求正文、Token 或知识内容。</p>
        </div>
      </div>

      <Panel>
        {!page.items.length ? (
          <EmptyState
            title="暂无操作审计记录"
            description="对业务 API 的写操作会在这里留下安全、可追溯的元数据。"
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>时间</th>
                  <th>主体</th>
                  <th>操作</th>
                  <th>结果</th>
                  <th>状态码</th>
                  <th>耗时</th>
                  <th>Request ID</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((event) => (
                  <tr key={event.id}>
                    <td>{formatTime(event.createdAt)}</td>
                    <td className="mono">{event.principalId}</td>
                    <td>
                      <div className="document-cell">
                        <ShieldCheck size={16} />
                        <div>
                          <strong>{event.httpMethod}</strong>
                          <span className="mono">{event.routePattern}</span>
                        </div>
                      </div>
                    </td>
                    <td><StatusBadge value={event.outcome} /></td>
                    <td>{event.responseStatus}</td>
                    <td>{event.durationMs} ms</td>
                    <td className="mono">{event.requestId.slice(0, 8)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="pagination-bar">
          <span>{first}–{last} / {page.total}</span>
          <div className="button-row">
            <button
              className="icon-button"
              disabled={page.offset === 0}
              onClick={() => setOffset(Math.max(0, page.offset - pageSize))}
              title="上一页"
            >
              <ChevronLeft size={17} />
            </button>
            <button
              className="icon-button"
              disabled={page.offset + page.items.length >= page.total}
              onClick={() => setOffset(page.offset + pageSize)}
              title="下一页"
            >
              <ChevronRight size={17} />
            </button>
          </div>
        </div>
      </Panel>
    </div>
  )
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(new Date(value))
}
