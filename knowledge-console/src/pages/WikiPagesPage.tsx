import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, ChevronRight, GitCompare, Plus, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import type {
  WikiPageDetail,
  WikiPageSummary,
  WikiPageStatus,
} from '../lib/api'
import { useApi } from '../lib/use-api'

type WikiAction = 'submit-review' | 'reject' | 'publish' | 'archive'
type SelectedSource = {
  documentId: string
  revisionId: string
  chunkId: string
}

export function WikiPagesPage() {
  const api = useApi()
  const client = useQueryClient()
  const [spaceId, setSpaceId] = useState('')
  const [selectedPageId, setSelectedPageId] = useState<string>()
  const [creating, setCreating] = useState(false)
  const [sourceDocumentId, setSourceDocumentId] = useState('')
  const [selectedSources, setSelectedSources] = useState<Map<string, SelectedSource>>(
    new Map(),
  )
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const pages = useQuery({
    queryKey: ['wiki-pages', spaceId],
    queryFn: () => api.wikiPages(spaceId || undefined),
  })
  const documents = useQuery({
    queryKey: ['wiki-source-documents', spaceId],
    queryFn: () => api.documents({
      spaceId: spaceId || undefined,
      status: 'ACTIVE',
      limit: 100,
    }),
    enabled: creating,
  })
  const chunks = useQuery({
    queryKey: ['wiki-source-chunks', sourceDocumentId],
    queryFn: () => api.chunks(sourceDocumentId),
    enabled: Boolean(sourceDocumentId),
  })
  const detail = useQuery({
    queryKey: ['wiki-page', selectedPageId],
    queryFn: () => api.wikiPage(selectedPageId!),
    enabled: Boolean(selectedPageId),
  })
  const compile = useMutation({
    mutationFn: api.compileWikiPage,
    onSuccess: async (value) => {
      setCreating(false)
      setSelectedPageId(value.page.id)
      resetSourceSelection()
      await client.invalidateQueries({ queryKey: ['wiki-pages'] })
      client.setQueryData(['wiki-page', value.page.id], value)
    },
  })
  const transition = useMutation({
    mutationFn: ({ page, action }: { page: WikiPageSummary; action: WikiAction }) =>
      api.transitionWikiPage(page.id, action, page.version),
    onSuccess: async (value) => {
      client.setQueryData(['wiki-page', value.page.id], value)
      await client.invalidateQueries({ queryKey: ['wiki-pages'] })
    },
  })

  if (spaces.isPending || pages.isPending) return <LoadingState />
  if (spaces.error || pages.error) return <ErrorState error={spaces.error ?? pages.error} />

  const selectedDocument = documents.data?.items.find(
    (document) => document.id === sourceDocumentId,
  )

  function resetSourceSelection() {
    setSourceDocumentId('')
    setSelectedSources(new Map())
  }

  function changeSpace(next: string) {
    setSpaceId(next)
    resetSourceSelection()
  }

  function openCreate() {
    resetSourceSelection()
    compile.reset()
    setCreating(true)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedSources.size) return
    const form = new FormData(event.currentTarget)
    compile.mutate({
      spaceId: String(form.get('spaceId')),
      slug: String(form.get('slug')).trim(),
      title: String(form.get('title')).trim(),
      sources: [...selectedSources.values()],
    })
  }

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">COMPILED KNOWLEDGE</span>
          <h2>将可信来源编译为可审核、可发布的知识页</h2>
          <p>草稿不会进入正式检索；发布后，Agent 仍然拿到原始文档 Chunk 的可追溯证据。</p>
        </div>
        <button className="primary-button" onClick={openCreate}>
          <Plus size={17} /> 编译知识页
        </button>
      </div>

      <Panel>
        <div className="toolbar">
          <label>
            知识空间
            <select value={spaceId} onChange={(event) => changeSpace(event.target.value)}>
              <option value="">全部可访问空间</option>
              {spaces.data.map((space) => (
                <option key={space.id} value={space.id}>{space.name}</option>
              ))}
            </select>
          </label>
          <span className="toolbar-count">共 {pages.data.length} 个知识页</span>
        </div>
        {!pages.data.length ? (
          <EmptyState
            title="还没有知识页"
            description="从已生效文档中选择来源 Chunk，编译草稿并完成审核发布。"
            action={<button className="primary-button" onClick={openCreate}>开始编译</button>}
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>知识页</th>
                  <th>空间</th>
                  <th>状态</th>
                  <th>来源</th>
                  <th>版本</th>
                  <th>更新时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {pages.data.map((page) => (
                  <tr key={page.id} onClick={() => setSelectedPageId(page.id)}>
                    <td>
                      <div className="document-cell">
                        <BookOpenCheck size={17} />
                        <div><strong>{page.title}</strong><span>{page.slug}</span></div>
                      </div>
                    </td>
                    <td className="mono">{page.spaceId}</td>
                    <td><StatusBadge value={page.status} /></td>
                    <td>{page.sourceCount}</td>
                    <td>v{page.version}</td>
                    <td>{formatTime(page.updatedAt)}</td>
                    <td><ChevronRight size={16} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {selectedPageId && (
        <div className="drawer-backdrop" onMouseDown={() => setSelectedPageId(undefined)}>
          <aside className="drawer wiki-drawer" onMouseDown={(event) => event.stopPropagation()}>
            <header>
              <div>
                <span className="eyebrow">WIKI PAGE REVIEW</span>
                <h2>{detail.data?.page.title ?? '知识页详情'}</h2>
                <p className="mono">{selectedPageId}</p>
              </div>
              <button className="icon-button" onClick={() => setSelectedPageId(undefined)}>
                <X size={18} />
              </button>
            </header>
            {detail.isPending && <LoadingState label="正在读取知识页" />}
            {detail.error && <ErrorState error={detail.error} />}
            {transition.error && <ErrorState error={transition.error} />}
            {detail.data && (
              <WikiDetail
                detail={detail.data}
                pending={transition.isPending}
                onAction={(action) => transition.mutate({ page: detail.data.page, action })}
              />
            )}
          </aside>
        </div>
      )}

      {creating && (
        <div className="modal-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setCreating(false)
        }}>
          <Panel className="modal modal-wide" title="编译知识页">
            <form className="form-stack" onSubmit={submit}>
              <div className="form-grid">
                <label>
                  知识空间
                  <select
                    name="spaceId"
                    required
                    value={spaceId}
                    onChange={(event) => changeSpace(event.target.value)}
                  >
                    <option value="">请选择</option>
                    {spaces.data.map((space) => (
                      <option key={space.id} value={space.id}>{space.name}</option>
                    ))}
                  </select>
                </label>
                <label>页面标识<input name="slug" required placeholder="order-service" /></label>
                <label>页面标题<input name="title" required placeholder="订单服务知识页" /></label>
                <label>
                  来源文档
                  <select
                    required
                    value={sourceDocumentId}
                    onChange={(event) => {
                      setSourceDocumentId(event.target.value)
                    }}
                  >
                    <option value="">请选择</option>
                    {documents.data?.items
                      .filter((document) => document.activeRevisionId)
                      .map((document) => (
                        <option key={document.id} value={document.id}>{document.title}</option>
                      ))}
                  </select>
                </label>
              </div>
              {documents.isPending && <LoadingState label="正在读取来源文档" />}
              {documents.error && <ErrorState error={documents.error} />}
              {chunks.isPending && <LoadingState label="正在读取来源 Chunk" />}
              {chunks.error && <ErrorState error={chunks.error} />}
              {chunks.data && (
                <div className="wiki-source-picker">
                  {chunks.data.map((chunk) => (
                    <label key={chunk.id}>
                      <input
                        type="checkbox"
                        checked={selectedSources.has(chunk.id)}
                        onChange={(event) => setSelectedSources((current) => {
                          const next = new Map(current)
                          if (event.target.checked && selectedDocument?.activeRevisionId) {
                            next.set(chunk.id, {
                              documentId: selectedDocument.id,
                              revisionId: selectedDocument.activeRevisionId,
                              chunkId: chunk.id,
                            })
                          } else {
                            next.delete(chunk.id)
                          }
                          return next
                        })}
                      />
                      <span><strong>{chunk.sectionPath.join(' / ') || `Chunk #${chunk.ordinal + 1}`}</strong>{chunk.content}</span>
                    </label>
                  ))}
                </div>
              )}
              {compile.error && <ErrorState error={compile.error} />}
              <div className="form-actions">
                <span className="toolbar-count">已选 {selectedSources.size} 个来源</span>
                <button type="button" onClick={() => setCreating(false)}>取消</button>
                <button className="primary-button" disabled={compile.isPending || !selectedSources.size}>
                  {compile.isPending ? '编译中…' : '生成草稿'}
                </button>
              </div>
            </form>
          </Panel>
        </div>
      )}
    </div>
  )
}

function WikiDetail({
  detail,
  pending,
  onAction,
}: {
  detail: WikiPageDetail
  pending: boolean
  onAction: (action: WikiAction) => void
}) {
  const actions = allowedActions(detail.page.status, detail.unpublishedChanges)
  return (
    <>
      <div className="detail-grid">
        <div><span>状态</span><strong><StatusBadge value={detail.page.status} /></strong></div>
        <div><span>当前版本</span><strong>v{detail.page.version}</strong></div>
        <div><span>活动修订</span><strong>{short(detail.page.activeRevisionId)}</strong></div>
        <div><span>最新修订</span><strong>{short(detail.page.latestRevisionId)}</strong></div>
      </div>
      <div className="button-row wiki-actions">
        {actions.map((action) => (
          <button
            key={action}
            className={action === 'publish' ? 'primary-button' : undefined}
            disabled={pending}
            onClick={() => onAction(action)}
          >
            {actionLabel(action)}
          </button>
        ))}
      </div>
      <h3 className="section-title"><GitCompare size={16} /> 内容与差异</h3>
      {detail.unpublishedChanges && detail.activeRevision ? (
        <div className="wiki-diff">
          <article><strong>当前已发布</strong><pre>{detail.activeRevision.markdown}</pre></article>
          <article><strong>待审核草稿</strong><pre>{detail.latestRevision.markdown}</pre></article>
        </div>
      ) : (
        <pre className="wiki-markdown">{detail.latestRevision.markdown}</pre>
      )}
      <h3 className="section-title">来源与追溯</h3>
      <div className="wiki-source-list">
        {detail.latestRevision.sources.map((source) => (
          <article key={`${source.revisionId}:${source.chunkId}`}>
            <strong>{source.sectionPath.join(' / ') || '正文'}</strong>
            <span className="mono">Document {short(source.documentId)} · Revision {short(source.revisionId)} · Chunk {short(source.chunkId)}</span>
            <span>权威等级 {source.authority} · {source.contentHash.slice(0, 16)}</span>
          </article>
        ))}
      </div>
    </>
  )
}

function allowedActions(status: WikiPageStatus, changed: boolean): WikiAction[] {
  if (status === 'DRAFT') return ['submit-review', 'archive']
  if (status === 'IN_REVIEW') return ['reject', 'publish']
  if (status === 'PUBLISHED') return changed ? ['submit-review', 'archive'] : ['archive']
  return []
}

function actionLabel(action: WikiAction) {
  return {
    'submit-review': '提交审核',
    reject: '驳回草稿',
    publish: '发布最新修订',
    archive: '归档',
  }[action]
}

function short(value: string | null) {
  return value ? value.slice(0, 8) : '—'
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
