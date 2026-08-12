import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Archive,
  ChevronLeft,
  ChevronRight,
  Download,
  Eye,
  FileText,
  Plus,
  RefreshCw,
  RotateCcw,
  Trash2,
  Upload,
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
  Chunk,
  DocumentRevisionView,
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
  const [status, setStatus] = useState('')
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<DocumentView>()
  const [uploading, setUploading] = useState(false)
  const [uploadingFile, setUploadingFile] = useState(false)
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const documents = useQuery({
    queryKey: ['documents', spaceId, status, offset],
    queryFn: () =>
      api.documents({
        spaceId: spaceId || undefined,
        status: status || undefined,
        limit: pageSize,
        offset,
      }),
  })
  const chunks = useQuery({
    queryKey: ['chunks', selected?.id],
    queryFn: () => api.chunks(selected!.id),
    enabled: Boolean(selected),
  })
  const originalSource = useQuery({
    queryKey: ['document-source', selected?.id],
    queryFn: () => api.sourceMetadata(selected!.id),
    enabled: Boolean(selected?.originalFileName),
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
  const fileUpload = useMutation({
    mutationFn: api.ingestFile,
    onSuccess: async () => {
      setUploadingFile(false)
      setOffset(0)
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
  })
  const sourceContent = useMutation({
    mutationFn: ({ documentId, inline }: { documentId: string; inline: boolean }) =>
      api.sourceContent(documentId, inline),
    onSuccess: (blob, variables) => {
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.target = variables.inline ? '_blank' : '_self'
      link.rel = 'noopener noreferrer'
      if (!variables.inline) {
        link.download = originalSource.data?.originalFileName ?? 'source'
      }
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    },
  })
  const lifecycle = useMutation({
    mutationFn: ({
      document,
      status,
    }: {
      document: DocumentView
      status: 'ACTIVE' | 'ARCHIVED' | 'DELETED'
    }) => api.transitionDocument(document.id, status, document.version),
    onSuccess: async (value) => {
      setSelected((current) =>
        current?.id === value.documentId
          ? {
              ...current,
              status: value.status,
              version: value.version,
              updatedAt: value.updatedAt,
            }
          : current,
      )
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
      await queryClient.invalidateQueries({
        queryKey: ['document-projections', value.documentId],
      })
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
  const lifecycleBelongsToSelected = Boolean(
    selected && lifecycle.variables?.document.id === selected.id,
  )

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SOURCE TO EVIDENCE</span>
          <h2>查看文档、活动修订和索引投影</h2>
          <p>点击文档可检查实际进入检索系统的 Chunk 和来源定位。</p>
        </div>
        <div className="button-row">
          <button onClick={() => setUploading(true)}>
            <Plus size={17} />
            写入 Markdown
          </button>
          <button className="primary-button" onClick={() => setUploadingFile(true)}>
            <Upload size={17} />
            上传文件
          </button>
        </div>
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
          <label>
            文档状态
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                setOffset(0)
                setSelected(undefined)
              }}
            >
              <option value="">默认（不含已删除）</option>
              <option value="ACTIVE">活动</option>
              <option value="ARCHIVED">已归档</option>
              <option value="DELETED">已删除</option>
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
                  <th>状态</th>
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
                    <td>
                      <div className="source-cell">
                        <span>{document.sourceType}</span>
                        {document.sourceMediaType && <small>{document.sourceMediaType}</small>}
                      </div>
                    </td>
                    <td>
                      <StatusBadge value={document.status} />
                    </td>
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
            <div className="document-lifecycle-actions">
              {selected.status === 'ACTIVE' ? (
                <>
                  <button
                    disabled={lifecycle.isPending}
                    onClick={() =>
                      lifecycle.mutate({ document: selected, status: 'ARCHIVED' })
                    }
                  >
                    <Archive size={15} />
                    归档
                  </button>
                  <button
                    className="danger-button"
                    disabled={lifecycle.isPending}
                    onClick={() => {
                      if (window.confirm('确认软删除该文档？原始数据仍保留用于恢复和审计。')) {
                        lifecycle.mutate({ document: selected, status: 'DELETED' })
                      }
                    }}
                  >
                    <Trash2 size={15} />
                    软删除
                  </button>
                </>
              ) : (
                <button
                  className="primary-button"
                  disabled={lifecycle.isPending}
                  onClick={() =>
                    lifecycle.mutate({ document: selected, status: 'ACTIVE' })
                  }
                >
                  <RotateCcw size={15} />
                  恢复为活动文档
                </button>
              )}
            </div>
            {lifecycleBelongsToSelected && lifecycle.error && (
              <ErrorState error={lifecycle.error} />
            )}
            {selected.originalFileName && (
              <>
                <h3 className="section-title">原始文件</h3>
                {originalSource.isPending && (
                  <LoadingState label="正在读取原文元数据" />
                )}
                {originalSource.error && <ErrorState error={originalSource.error} />}
                {sourceContent.error && <ErrorState error={sourceContent.error} />}
                {originalSource.data && (
                  <div className="source-card">
                    <div className="detail-grid">
                      <Detail label="文件名" value={originalSource.data.originalFileName} />
                      <Detail label="媒体类型" value={originalSource.data.mediaType} />
                      <Detail
                        label="文件大小"
                        value={formatBytes(originalSource.data.contentLength)}
                      />
                      <Detail
                        label="保留时间"
                        value={formatTime(originalSource.data.storedAt)}
                      />
                    </div>
                    <div className="button-row">
                      {originalSource.data.previewable && (
                        <button
                          disabled={sourceContent.isPending}
                          onClick={() =>
                            sourceContent.mutate({ documentId: selected.id, inline: true })
                          }
                        >
                          <Eye size={15} />
                          预览原文
                        </button>
                      )}
                      <button
                        disabled={sourceContent.isPending}
                        onClick={() =>
                          sourceContent.mutate({ documentId: selected.id, inline: false })
                        }
                      >
                        <Download size={15} />
                        下载原文
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
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
            <DocumentRevisionHistory
              key={selected.id}
              documentId={selected.id}
            />
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

      {uploadingFile && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setUploadingFile(false)
          }}
        >
          <Panel className="modal" title="上传原始文件">
            <form
              className="form-stack"
              onSubmit={(event) => submitFileUpload(event, fileUpload.mutate)}
              onMouseDown={(event) => event.stopPropagation()}
            >
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
                <input name="externalId" required placeholder="handbook/oncall-pdf" />
              </label>
              <label>
                标题（可选）
                <input name="title" placeholder="未填写时使用原文件名" />
              </label>
              <label>
                文件
                <input
                  name="file"
                  type="file"
                  required
                  accept=".txt,.text,.log,.html,.htm,.xhtml,.pdf,.docx,text/plain,text/html,application/xhtml+xml,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                />
              </label>
              <p className="form-hint">
                支持 TXT、HTML、PDF、DOCX，单文件不超过 25 MB。PDF 与 DOCX 会受到页数、解压大小和压缩比限制。
              </p>
              {fileUpload.error && <ErrorState error={fileUpload.error} />}
              <div className="form-actions">
                <button type="button" onClick={() => setUploadingFile(false)}>
                  取消
                </button>
                <button className="primary-button" disabled={fileUpload.isPending}>
                  {fileUpload.isPending ? '解析并写入中…' : '上传并写入'}
                </button>
              </div>
            </form>
          </Panel>
        </div>
      )}
    </div>
  )
}

function DocumentRevisionHistory({ documentId }: { documentId: string }) {
  const api = useApi()
  const [leftRevisionId, setLeftRevisionId] = useState('')
  const [rightRevisionId, setRightRevisionId] = useState('')
  const revisions = useQuery({
    queryKey: ['document-revisions', documentId],
    queryFn: () => api.documentRevisions(documentId),
  })
  const leftChunks = useQuery({
    queryKey: ['document-revision-chunks', documentId, leftRevisionId],
    queryFn: () => api.revisionChunks(documentId, leftRevisionId),
    enabled: Boolean(leftRevisionId),
  })
  const rightChunks = useQuery({
    queryKey: ['document-revision-chunks', documentId, rightRevisionId],
    queryFn: () => api.revisionChunks(documentId, rightRevisionId),
    enabled: Boolean(rightRevisionId),
  })

  const leftRevision = revisions.data?.find(
    (revision) => revision.revisionId === leftRevisionId,
  )
  const rightRevision = revisions.data?.find(
    (revision) => revision.revisionId === rightRevisionId,
  )

  return (
    <section>
      <h3 className="section-title">修订历史与对比</h3>
      {revisions.isPending && <LoadingState label="正在读取修订历史" />}
      {revisions.error && <ErrorState error={revisions.error} />}
      {revisions.data && !revisions.data.length && (
        <div className="inline-empty">当前文档没有可读取的修订。</div>
      )}
      {revisions.data && revisions.data.length > 0 && (
        <>
          <div className="revision-list">
            {revisions.data.map((revision) => (
              <article className="revision-card" key={revision.revisionId}>
                <div>
                  <strong>版本 {revision.revisionNumber}</strong>
                  {revision.active && <StatusBadge value="ACTIVE" />}
                </div>
                <span>
                  {revision.mediaType} · {revision.language} · {revision.chunkCount} 个
                  Chunk
                </span>
                <small>
                  {revision.parserVersion} · {formatTime(revision.createdAt)} ·{' '}
                  {revision.contentHash.slice(0, 16)}
                </small>
              </article>
            ))}
          </div>
          <div className="revision-selectors">
            <label>
              左侧版本
              <select
                value={leftRevisionId}
                onChange={(event) => setLeftRevisionId(event.target.value)}
              >
                <option value="">按需选择</option>
                {revisions.data.map((revision) => (
                  <RevisionOption revision={revision} key={revision.revisionId} />
                ))}
              </select>
            </label>
            <label>
              右侧版本
              <select
                value={rightRevisionId}
                onChange={(event) => setRightRevisionId(event.target.value)}
              >
                <option value="">按需选择</option>
                {revisions.data.map((revision) => (
                  <RevisionOption revision={revision} key={revision.revisionId} />
                ))}
              </select>
            </label>
          </div>
          {leftRevisionId && rightRevisionId && leftRevisionId === rightRevisionId && (
            <div className="inline-empty">请选择两个不同修订进行对比。</div>
          )}
          {leftRevisionId && rightRevisionId && leftRevisionId !== rightRevisionId && (
            <>
              {(leftChunks.isPending || rightChunks.isPending) && (
                <LoadingState label="正在按需读取修订内容" />
              )}
              {(leftChunks.error || rightChunks.error) && (
                <ErrorState error={leftChunks.error ?? rightChunks.error} />
              )}
              {leftRevision &&
                rightRevision &&
                leftChunks.data &&
                rightChunks.data && (
                  <RevisionComparison
                    leftRevision={leftRevision}
                    rightRevision={rightRevision}
                    leftChunks={leftChunks.data}
                    rightChunks={rightChunks.data}
                  />
                )}
            </>
          )}
        </>
      )}
    </section>
  )
}

function RevisionOption({ revision }: { revision: DocumentRevisionView }) {
  return (
    <option value={revision.revisionId}>
      版本 {revision.revisionNumber}
      {revision.active ? '（活动）' : ''}
    </option>
  )
}

function RevisionComparison({
  leftRevision,
  rightRevision,
  leftChunks,
  rightChunks,
}: {
  leftRevision: DocumentRevisionView
  rightRevision: DocumentRevisionView
  leftChunks: Chunk[]
  rightChunks: Chunk[]
}) {
  const leftByOrdinal = new Map(leftChunks.map((chunk) => [chunk.ordinal, chunk]))
  const rightByOrdinal = new Map(rightChunks.map((chunk) => [chunk.ordinal, chunk]))
  const ordinals = [...new Set([...leftByOrdinal.keys(), ...rightByOrdinal.keys()])]
    .sort((left, right) => left - right)
  const rows = ordinals.map((ordinal) => ({
    ordinal,
    left: leftByOrdinal.get(ordinal),
    right: rightByOrdinal.get(ordinal),
  }))

  return (
    <div className="revision-comparison">
      <header>
        <strong>版本 {leftRevision.revisionNumber}</strong>
        <strong>版本 {rightRevision.revisionNumber}</strong>
      </header>
      {!rows.length && <div className="inline-empty">两个修订都没有 Chunk。</div>}
      {rows.map(({ ordinal, left, right }) => {
        const changed = left?.contentHash !== right?.contentHash
        const leftLabel = !left
          ? '此版无内容'
          : !right
            ? '右侧删除'
            : changed
              ? '有变化'
              : '相同'
        const rightLabel = !right
          ? '此版无内容'
          : !left
            ? '右侧新增'
            : changed
              ? '有变化'
              : '相同'
        return (
          <div className="revision-compare-row" key={ordinal}>
            <RevisionChunk
              chunk={left}
              ordinal={ordinal}
              changed={changed}
              changeLabel={leftLabel}
            />
            <RevisionChunk
              chunk={right}
              ordinal={ordinal}
              changed={changed}
              changeLabel={rightLabel}
            />
          </div>
        )
      })}
    </div>
  )
}

function RevisionChunk({
  chunk,
  ordinal,
  changed,
  changeLabel,
}: {
  chunk: Chunk | undefined
  ordinal: number
  changed: boolean
  changeLabel: string
}) {
  return (
    <article className={`revision-compare-item ${changed ? 'changed' : ''}`}>
      <header>
        <span>#{ordinal + 1}</span>
        <small>{changeLabel}</small>
      </header>
      {chunk ? (
        <>
          <strong>{chunk.sectionPath.join(' / ') || '正文'}</strong>
          <p>{chunk.content}</p>
          <footer className="mono">{chunk.contentHash.slice(0, 16)}</footer>
        </>
      ) : (
        <p className="revision-missing">此版本无对应 Chunk</p>
      )}
    </article>
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

function submitFileUpload(
  event: FormEvent<HTMLFormElement>,
  mutate: (value: FormData) => void,
) {
  event.preventDefault()
  const form = new FormData(event.currentTarget)
  const title = String(form.get('title') ?? '').trim()
  if (!title) form.delete('title')
  form.set('language', 'zh-CN')
  form.set('authority', '80')
  mutate(form)
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
