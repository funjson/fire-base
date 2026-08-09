import { useQuery } from '@tanstack/react-query'
import { Activity, ChevronRight, X } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { ErrorState, LoadingState, Panel, StatusBadge } from '../components/State'
import { useApi } from '../lib/use-api'

export function TracesPage() {
  const api = useApi()
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = searchParams.get('traceId') ?? undefined
  const traces = useQuery({ queryKey: ['traces'], queryFn: api.traces })
  const detail = useQuery({
    queryKey: ['trace', selectedId],
    queryFn: () => api.trace(selectedId!),
    enabled: Boolean(selectedId),
  })

  if (traces.isPending) return <LoadingState />
  if (traces.error) return <ErrorState error={traces.error} />

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SAFE OBSERVABILITY</span>
          <h2>追踪检索阶段，不记录企业知识正文</h2>
          <p>Trace 仅保存身份、哈希、数量、耗时和状态，支持性能与质量诊断。</p>
        </div>
      </div>
      <Panel>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Trace</th>
                <th>Request</th>
                <th>主体</th>
                <th>耗时</th>
                <th>证据</th>
                <th>时间</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {traces.data.map((trace) => (
                <tr
                  key={trace.id}
                  onClick={() => setSearchParams({ traceId: trace.id })}
                >
                  <td className="mono">{trace.id.slice(0, 8)}</td>
                  <td className="mono">{trace.requestId.slice(0, 8)}</td>
                  <td>{trace.principalId}</td>
                  <td>{trace.totalDurationMs} ms</td>
                  <td>{trace.resultCount}</td>
                  <td>{formatTime(trace.createdAt)}</td>
                  <td>
                    <ChevronRight size={16} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      {selectedId && (
        <div className="drawer-backdrop" onMouseDown={() => setSearchParams({})}>
          <aside className="drawer" onMouseDown={(event) => event.stopPropagation()}>
            <header>
              <div>
                <span className="eyebrow">TRACE WATERFALL</span>
                <h2>{selectedId.slice(0, 12)}</h2>
                <p>
                  {detail.data ? `${detail.data.totalDurationMs} ms total` : '正在读取 Trace'}
                </p>
              </div>
              <button className="icon-button" onClick={() => setSearchParams({})}>
                <X size={18} />
              </button>
            </header>
            {detail.isPending && <LoadingState />}
            {detail.error && <ErrorState error={detail.error} />}
            <div className="waterfall">
              {detail.data?.steps.map((step) => (
                <div className="waterfall-row" key={step.ordinal}>
                  <div className="trace-icon">
                    <Activity size={15} />
                  </div>
                  <div className="waterfall-content">
                    <header>
                      <strong>{step.name}</strong>
                      <StatusBadge value={step.status} />
                    </header>
                    <div className="waterfall-track">
                      <i
                        style={{
                          width: `${Math.min(
                            100,
                            Math.max(
                              4,
                              (step.durationMs /
                                Math.max(detail.data.totalDurationMs, 1)) *
                                100,
                            ),
                          )}%`,
                        }}
                      />
                    </div>
                    <footer>
                      <span>{step.durationMs} ms</span>
                      <span>
                        {step.inputCount} → {step.outputCount}
                      </span>
                    </footer>
                  </div>
                </div>
              ))}
            </div>
          </aside>
        </div>
      )}
    </div>
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
