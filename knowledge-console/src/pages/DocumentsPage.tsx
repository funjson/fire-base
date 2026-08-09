import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ChevronLeft,
  ChevronRight,
  FileText,
  Plus,
  RefreshCw,
  X,
} from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import type {
  DocumentView,
  MarkdownDocumentInput,
  ProjectionJob,
  ProjectionType,
} from '../lib/api'
import { useApi } from '../lib/use-api'

const pageSize = 25

export function DocumentsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [spaceId, setSpaceId] = useState('')
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<DocumentView>()
  const [uploading, setUploading] = useState(false)
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const documents = useQuery({
    queryKey: ['documents', spaceId, offset],
    queryFn: () =>
      api.documents({ spaceId: spaceId || undefined, limit: pageSize, offset }),
  })
  const chunks = useQuery({
    queryKey: ['chunks', selected?.id],
    queryFn: () => api.chunks(selected!.id),
    enabled: Boolean(selected),
  })
  const projections = useQuery({
    queryKey: ['document-projections', selected?.id],
    queryFn: () => api.projectionJobs(selected!.id),
    enabled: Boolean(selected),
    refetchInterval: (query) =>
      query.state.data?.some((job) =>
        ['PENDING', 'RETRY', 'RUNNING'].includes(job.status),
      )
        ? 2_000
        : false,
  })
  const retryProjection = useMutation({
    mutationFn: ({
      documentId,
      projectionType,
    }: {
      documentId: string
      projectionType: ProjectionType
    }) => api.retryProjection(documentId, projectionType),
    onSuccess: async (_result, variables) => {
      await queryClient.invalidateQueries({
        queryKey: ['document-projections', variables.documentId],
      })
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
  })
  const upload = useMutation({
    mutationFn: api.ingestMarkdown,
    onSuccess: async () => {
      setUploading(false)
      setOffset(0)
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
  })

  if (documents.isPending || spaces.isPending) return <LoadingState />
  if (documents.error || spaces.error) {
    return <ErrorState error={documents.error ?? spaces.error} />
  }

  const firstDocument = documents.data.total === 0 ? 0 : documents.data.offset + 1
  const lastDocument = Math.min(
    documents.data.offset + documents.data.items.length,
    documents.data.total,
  )
  const pageNumber = Math.floor(documents.data.offset / pageSize) + 1
  const pageCount = Math.max(1, Math.ceil(documents.data.total / pageSize))
  const retryBelongsToSelected = Boolean(
    selected && retryProjection.variables?.documentId === selected.id,
  )

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SOURCE TO EVIDENCE</span>
          <h2>查看文档、活动修订和索引投影</h2>
          <p>点击文档可检查实际进入检索系统的 Chunk 和来源定位。</p>
        </div>
        <button className="primary-button" onClick={() => setUploading(true)}>
          <Plus size={17} />
          写入 Markdown
        </button>
      </div>

      <Panel>
        <div className="toolbar">
          <label>
            知识空间
            <select
              value={spaceId}
              onChange={(event) => {
                setSpaceId(event.target.value)
                setOffset(0)
                setSelected(undefined)
              }}
            >
              <option value="">全部空间</option>
              {spaces.data.map((space) => (
                <option value={space.id} key={space.id}>
                  {space.name}
                </option>
              ))}
            </select>
          </label>
          <span className="toolbar-count">
            {documents.isFetching ? '正在刷新 · ' : ''}
            共 {documents.data.total} 篇文档
          </span>
        </div>
        {!documents.data.items.length ? (
          <EmptyState
            title="没有符合条件的文档"
            description="上传 Markdown 或配置外部连接器后，文档会显示在这里。"
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>文档</th>
                  <th>空间</th>
                  <th>来源</th>
                  <th>Chunk</th>
                  <th>关键词</th>
                  <th>向量</th>
                  <th>更新时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {documents.data.items.map((document) => (
                  <tr key={document.id} onClick={() => setSelected(document)}>
                    <td>
                      <div className="document-cell">
                        <FileText size={17} />
                        <div>
                          <strong>{document.title}</strong>
                          <span>权威度 {document.authority}</span>
                        </div>
                      </div>
                    </td>
                    <td className="mono">{document.spaceId}</td>
                    <td>{document.sourceType}</td>
                    <td>{document.chunkCount}</td>
                    <td>
                      <StatusBadge value={document.keywordStatus} />
                    </td>
                    <td>
                      <StatusBadge value={document.vectorStatus} />
                    </td>
                    <td>{formatTime(document.updatedAt)}</td>
                    <td>
                      <ChevronRight size={16} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {documents.data.total > 0 && (
          <div className="pagination">
            <span>
              显示 {firstDocument}–{lastDocument} · 第 {pageNumber}/{pageCount} 页
            </span>
            <div className="button-row">
              <button
                disabled={documents.isFetching || offset === 0}
                onClick={() => setOffset((current) => Math.max(0, current - pageSize))}
              >
                <ChevronLeft size={15} />
                上一页
              </button>
              <button
                disabled={documents.isFetching || lastDocument >= documents.data.total}
                onClick={() => setOffset((current) => current + pageSize)}
              >
                下一页
                <ChevronRight size={15} />
              </button>
            </div>
          </div>
        )}
      </Panel>

      {selected && (
        <div className="drawer-backdrop" onMouseDown={() => setSelected(undefined)}>
          <aside className="drawer" onMouseDown={(event) => event.stopPropagation()}>
            <header>
              <div>
                <span className="eyebrow">DOCUMENT INSPECTOR</span>
                <h2>{selected.title}</h2>
                <p className="mono">{selected.id}</p>
              </div>
              <button className="icon-button" onClick={() => setSelected(undefined)}>
                <X size={18} />
              </button>
            </header>
            <div className="detail-grid">
              <Detail label="状态" value={<StatusBadge value={selected.status} />} />
              <Detail label="来源" value={selected.sourceType} />
              <Detail label="活动修订" value={selected.activeRevisionId?.slice(0, 8)} />
              <Detail label="Chunk" value={selected.chunkCount} />
            </div>
            <h3 className="section-title">外部索引投影</h3>
            {projections.isPending && <LoadingState label="正在读取投影任务" />}
            {projections.error && <ErrorState error={projections.error} />}
            {retryBelongsToSelected && retryProjection.error && (
              <ErrorState error={retryProjection.error} />
            )}
            {projections.data && (
              <ProjectionJobs
                documentId={selected.id}
                jobs={projections.data}
                retryDisabled={retryProjection.isPending}
                retrying={retryBelongsToSelected && retryProjection.isPending}
                retryingType={
                  retryBelongsToSelected
                    ? retryProjection.variables?.projectionType
                    : undefined
                }
                onRetry={(projectionType) =>
                  retryProjection.mutate({
                    documentId: selected.id,
                    projectionType,
                  })
                }
              />
            )}
            <h3 className="section-title">活动修订的知识块</h3>
            {chunks.isPending && <LoadingState label="正在读取知识块" />}
            {chunks.error && <ErrorState error={chunks.error} />}
            <div className="chunk-list">
              {chunks.data?.map((chunk) => (
                <article className="chunk-card" key={chunk.id}>
                  <div>
                    <span>#{chunk.ordinal + 1}</span>
                    <strong>{chunk.sectionPath.join(' / ') || '正文'}</strong>
                  </div>
                  <p>{chunk.content}</p>
                  <footer className="mono">{chunk.contentHash.slice(0, 16)}</footer>
                </article>
              ))}
            </div>
          </aside>
        </div>
      )}

      {uploading && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setUploading(false)
          }}
        >
          <Panel className="modal modal-wide" title="写入 Markdown 文档">
            <form
              className="form-stack"
              onSubmit={(event) => submitUpload(event, upload.mutate)}
              onMouseDown={(event) => event.stopPropagation()}
            >
              <div className="form-grid">
                <label>
                  知识空间
                  <select name="spaceId" required defaultValue={spaceId}>
                    <option value="">请选择</option>
                    {spaces.data.map((space) => (
                      <option value={space.id} key={space.id}>
                        {space.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  外部标识
                  <input name="externalId" required placeholder="handbook/oncall" />
                </label>
                <label>
                  标题
                  <input name="title" required placeholder="生产故障处理手册" />
                </label>
                <label>
                  来源地址
                  <input name="sourceUri" required placeholder="obsidian://open/..." />
                </label>
              </div>
              <label>
                Markdown 正文
                <textarea
                  name="content"
                  required
                  rows={12}
                  placeholder={'# 标题\n\n## 章节\n\n知识正文…'}
                />
              </label>
              {upload.error && <ErrorState error={upload.error} />}
              <div className="form-actions">
                <button type="button" onClick={() => setUploading(false)}>
                  取消
                </button>
                <button className="primary-button" disabled={upload.isPending}>
                  {upload.isPending ? '处理中…' : '解析并写入'}
                </button>
              </div>
            </form>
          </Panel>
        </div>
      )}
    </div>
  )
}

function ProjectionJobs({
  documentId,
  jobs,
  retryDisabled,
  retrying,
  retryingType,
  onRetry,
}: {
  documentId: string
  jobs: ProjectionJob[]
  retryDisabled: boolean
  retrying: boolean
  retryingType: ProjectionType | undefined
  onRetry: (projectionType: ProjectionType) => void
}) {
  if (!jobs.length) {
    return (
      <div className="inline-empty">
        当前文档没有可管理的活动修订投影任务；PostgreSQL 关键词检索无需异步投影。
      </div>
    )
  }
  return (
    <div className="projection-job-list" aria-label={`${documentId} 的投影任务`}>
      {jobs.map((job) => (
        <article className="projection-job-card" key={job.id}>
          <header>
            <div>
              <strong>{job.projectionType}</strong>
              <span>已尝试 {job.attemptCount} 次</span>
            </div>
            <StatusBadge value={job.status} />
          </header>
          <div className="projection-job-meta">
            <span>可执行 {formatTime(job.availableAt)}</span>
            <span>更新 {formatTime(job.updatedAt)}</span>
          </div>
          {job.lastErrorCode && <code>{job.lastErrorCode}</code>}
          {job.status === 'DEAD' && (
            <button
              disabled={retryDisabled}
              onClick={() => onRetry(job.projectionType)}
            >
              <RefreshCw
                className={
                  retrying && retryingType === job.projectionType ? 'spin' : undefined
                }
                size={14}
              />
              {retrying && retryingType === job.projectionType
                ? '重新排队中…'
                : '重试 DEAD 任务'}
            </button>
          )}
        </article>
      ))}
    </div>
  )
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function submitUpload(
  event: FormEvent<HTMLFormElement>,
  mutate: (value: MarkdownDocumentInput) => void,
) {
  event.preventDefault()
  const form = new FormData(event.currentTarget)
  mutate({
    spaceId: String(form.get('spaceId')),
    externalId: String(form.get('externalId')).trim(),
    title: String(form.get('title')).trim(),
    sourceUri: String(form.get('sourceUri')).trim(),
    content: String(form.get('content')),
    language: 'zh-CN',
    authority: 80,
    metadata: { source: 'console' },
  })
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
