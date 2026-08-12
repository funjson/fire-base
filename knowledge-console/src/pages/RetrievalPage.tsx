import { useMutation, useQuery } from '@tanstack/react-query'
import { CheckCircle2, ExternalLink, Search, ShieldAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ErrorState, Panel, StatusBadge } from '../components/State'
import { useApi } from '../lib/use-api'

export function RetrievalPage() {
  const api = useApi()
  const [selectedSpaces, setSelectedSpaces] = useState<string[]>([])
  const spaces = useQuery({
    queryKey: ['accessible-spaces'],
    queryFn: api.accessibleSpaces,
  })
  const query = useMutation({ mutationFn: api.query })

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">RETRIEVAL PLAYGROUND</span>
          <h2>检查每一次召回、融合和引用</h2>
          <p>使用当前用户的真实权限执行，不提供绕过 ACL 的调试后门。</p>
        </div>
      </div>

      <div className="retrieval-layout">
        <Panel title="查询条件" description="选择知识空间并执行真实混合检索">
          <form
            className="form-stack"
            onSubmit={(event) => submit(event, selectedSpaces, query.mutate)}
          >
            <label>
              查询
              <textarea
                name="query"
                rows={5}
                required
                placeholder="例如：订单服务超时时应该检查哪些依赖？"
              />
            </label>
            <fieldset>
              <legend>知识空间</legend>
              {spaces.error && <ErrorState error={spaces.error} />}
              <div className="check-list">
                {spaces.data?.map((space) => (
                  <label key={space.id}>
                    <input
                      type="checkbox"
                      checked={selectedSpaces.includes(space.id)}
                      onChange={(event) =>
                        setSelectedSpaces((current) =>
                          event.target.checked
                            ? [...current, space.id]
                            : current.filter((id) => id !== space.id),
                        )
                      }
                    />
                    <span>
                      <strong>{space.name}</strong>
                      <small>{space.documentCount} 篇文档</small>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <label>
              返回数量
              <input
                name="topK"
                type="number"
                min={1}
                max={30}
                defaultValue={8}
                required
              />
            </label>
            <div className="form-grid">
              <label>
                语言（BCP 47）
                <input name="language" placeholder="例如 zh-CN" maxLength={32} />
              </label>
              <label>
                来源类型
                <select name="sourceType" defaultValue="">
                  <option value="">全部来源</option>
                  <option value="API">Markdown API</option>
                  <option value="UPLOAD">文件上传</option>
                  <option value="OBSIDIAN">Obsidian</option>
                  <option value="FILESYSTEM">文件系统</option>
                  <option value="GIT">Git</option>
                  <option value="COMPILED">编译知识</option>
                </select>
              </label>
            </div>
            <button
              className="primary-button"
              disabled={query.isPending || !selectedSpaces.length}
            >
              <Search size={17} />
              {query.isPending ? '检索中…' : '运行检索'}
            </button>
          </form>
        </Panel>

        <div className="result-column">
          {!query.data && !query.error && (
            <div className="retrieval-placeholder">
              <Search size={30} />
              <strong>等待查询</strong>
              <span>结果会显示通道、相关度、引用和安全 Trace。</span>
            </div>
          )}
          {query.error && <ErrorState error={query.error} />}
          {query.data && (
            <>
              <div className="result-summary">
                <div
                  className={
                    query.data.sufficient ? 'summary-icon success' : 'summary-icon warning'
                  }
                >
                  {query.data.sufficient ? (
                    <CheckCircle2 size={21} />
                  ) : (
                    <ShieldAlert size={21} />
                  )}
                </div>
                <div>
                  <strong>
                    {query.data.sufficient ? '证据充分' : '证据可能不足'}
                  </strong>
                  <span>
                    {query.data.evidences.length} 条证据 · Trace{' '}
                    <span className="mono">{query.data.traceId.slice(0, 8)}</span>
                  </span>
                </div>
                {query.data.warnings.length > 0 && (
                  <div className="warning-pills">
                    {query.data.warnings.map((warning) => (
                      <StatusBadge value={warning} key={warning} />
                    ))}
                  </div>
                )}
              </div>
              <div className="evidence-list">
                {query.data.evidences.map((evidence, index) => (
                  <article className="evidence-card" key={evidence.id}>
                    <header>
                      <div className="rank">{index + 1}</div>
                      <div>
                        <strong>{evidence.citation.title}</strong>
                        <span>{evidence.citation.sectionPath.join(' / ') || '正文'}</span>
                      </div>
                      <div className="score">
                        <strong>{Math.round(evidence.relevance * 100)}%</strong>
                        <span>融合相关度</span>
                      </div>
                    </header>
                    <p>{evidence.content}</p>
                    <footer>
                      <div className="channel-pills">
                        {evidence.channels.map((channel) => (
                          <span key={channel}>{channel}</span>
                        ))}
                      </div>
                      {safeSourceUri(evidence.citation.sourceUri) ? (
                        <a
                          href={evidence.citation.sourceUri}
                          target="_blank"
                          rel="noreferrer"
                        >
                          查看来源 <ExternalLink size={13} />
                        </a>
                      ) : (
                        <span title="来源协议未被控制台允许">来源不可打开</span>
                      )}
                    </footer>
                  </article>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function safeSourceUri(value: string): boolean {
  try {
    return ['http:', 'https:', 'obsidian:'].includes(new URL(value).protocol)
  } catch {
    return false
  }
}

function submit(
  event: FormEvent<HTMLFormElement>,
  spaceIds: string[],
  mutate: (value: {
    query: string
    spaceIds: string[]
    topK: number
    filters: Record<string, string>
  }) => void,
) {
  event.preventDefault()
  const form = new FormData(event.currentTarget)
  const filters = Object.fromEntries(
    ['language', 'sourceType']
      .map((name) => [name, String(form.get(name) ?? '').trim()] as const)
      .filter(([, value]) => value.length > 0),
  )
  mutate({
    query: String(form.get('query')).trim(),
    spaceIds,
    topK: Number(form.get('topK') ?? 8),
    filters,
  })
}
