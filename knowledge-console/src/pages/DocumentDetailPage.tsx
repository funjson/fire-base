import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Archive,
  ArrowLeft,
  Download,
  Eye,
  RefreshCw,
  RotateCcw,
  Trash2,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
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
  ProjectionJob,
  ProjectionType,
} from '../lib/api'
import { useApi } from '../lib/use-api'

/**
 * 文档二级详情页承载生命周期、原文、投影与修订诊断。
 *
 * 独立路由保证页面可刷新、可收藏，也为后续增加原文与抽取结果对比留出空间。
 */
export function DocumentDetailPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const { documentId = '' } = useParams()
  const document = useQuery({
    queryKey: ['document', documentId],
    queryFn: () => api.document(documentId),
    enabled: Boolean(documentId),
  })
  const chunks = useQuery({
    queryKey: ['chunks', documentId],
    queryFn: () => api.chunks(documentId),
    enabled: Boolean(documentId),
  })
  const originalSource = useQuery({
    queryKey: ['document-source', documentId],
    queryFn: () => api.sourceMetadata(documentId),
    enabled: Boolean(document.data?.originalFileName),
  })
  const projections = useQuery({
    queryKey: ['document-projections', documentId],
    queryFn: () => api.projectionJobs(documentId),
    enabled: Boolean(documentId),
    refetchInterval: (query) =>
      query.state.data?.some((job) =>
        ['PENDING', 'RETRY', 'RUNNING'].includes(job.status),
      )
        ? 2_000
        : false,
  })
  const retryProjection = useMutation({
    mutationFn: (projectionType: ProjectionType) =>
      api.retryProjection(documentId, projectionType),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ['document-projections', documentId],
      })
      await queryClient.invalidateQueries({ queryKey: ['document', documentId] })
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
  })
  const sourceContent = useMutation({
    mutationFn: (inline: boolean) => api.sourceContent(documentId, inline),
    onSuccess: (blob, inline) => {
      const url = URL.createObjectURL(blob)
      const link = window.document.createElement('a')
      link.href = url
      link.target = inline ? '_blank' : '_self'
      link.rel = 'noopener noreferrer'
      if (!inline) {
        link.download = originalSource.data?.originalFileName ?? 'source'
      }
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    },
  })
  const lifecycle = useMutation({
    mutationFn: ({
      value,
      status,
    }: {
      value: DocumentView
      status: 'ACTIVE' | 'ARCHIVED' | 'DELETED'
    }) => api.transitionDocument(value.id, status, value.version),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['document', documentId] })
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['overview'] })
      await queryClient.invalidateQueries({
        queryKey: ['document-projections', documentId],
      })
    },
  })

  if (!documentId) {
    return <ErrorState error={new Error('缺少文档标识')} />
  }
  if (document.isPending) return <LoadingState label="正在读取文档详情" />
  if (document.error) return <ErrorState error={document.error} />

  const value = document.data
  return (
    <div className="page-stack document-detail-page">
      <div className="page-intro">
        <div>
          <span className="eyebrow">DOCUMENT DETAIL</span>
          <h2>{value.title}</h2>
          <p className="mono">{value.id}</p>
        </div>
        <Link className="secondary-button" to="/documents">
          <ArrowLeft size={16} />
          返回文档管理
        </Link>
      </div>

      <Panel title="文档摘要" description="当前活动修订及其检索投影状态">
        <div className="detail-grid document-summary-grid">
          <Detail label="状态" value={<StatusBadge value={value.status} />} />
          <Detail label="知识空间" value={value.spaceId} />
          <Detail label="来源类型" value={value.sourceType} />
          <Detail label="权威度" value={value.authority} />
          <Detail
            label="活动修订"
            value={value.activeRevisionId?.slice(0, 8) ?? '无'}
          />
          <Detail label="Chunk 数量" value={value.chunkCount} />
          <Detail
            label="关键词索引"
            value={<StatusBadge value={value.keywordStatus} />}
          />
          <Detail
            label="向量索引"
            value={<StatusBadge value={value.vectorStatus} />}
          />
          <Detail label="更新时间" value={formatTime(value.updatedAt)} />
        </div>
        <div className="document-lifecycle-actions">
          {value.status === 'ACTIVE' ? (
            <>
              <button
                disabled={lifecycle.isPending}
                onClick={() =>
                  lifecycle.mutate({ value, status: 'ARCHIVED' })
                }
              >
                <Archive size={15} />
                归档
              </button>
              <button
                className="danger-button"
                disabled={lifecycle.isPending}
                onClick={() => {
                  if (
                    window.confirm(
                      '确认软删除该文档？原始数据仍保留用于恢复和审计。',
                    )
                  ) {
                    lifecycle.mutate({ value, status: 'DELETED' })
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
              onClick={() => lifecycle.mutate({ value, status: 'ACTIVE' })}
            >
              <RotateCcw size={15} />
              恢复为活动文档
            </button>
          )}
        </div>
        {lifecycle.error && <ErrorState error={lifecycle.error} />}
      </Panel>

      <Panel
        title="原始文件"
        description="预览始终回到摄取时保留的原件，不使用检索上下文化文本"
      >
        {!value.originalFileName ? (
          <EmptyState
            title="当前来源没有可下载原件"
            description="API 或连接器来源可能只保留标准化后的修订内容。"
          />
        ) : (
          <>
            {originalSource.isPending && (
              <LoadingState label="正在读取原文元数据" />
            )}
            {originalSource.error && <ErrorState error={originalSource.error} />}
            {sourceContent.error && <ErrorState error={sourceContent.error} />}
            {originalSource.data && (
              <div className="source-card">
                <div className="detail-grid">
                  <Detail
                    label="文件名"
                    value={originalSource.data.originalFileName}
                  />
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
                      onClick={() => sourceContent.mutate(true)}
                    >
                      <Eye size={15} />
                      预览原文
                    </button>
                  )}
                  <button
                    disabled={sourceContent.isPending}
                    onClick={() => sourceContent.mutate(false)}
                  >
                    <Download size={15} />
                    下载原文
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </Panel>

      <Panel
        title="外部索引投影"
        description="关键词与向量状态都针对当前活动修订"
      >
        {projections.isPending && <LoadingState label="正在读取投影任务" />}
        {projections.error && <ErrorState error={projections.error} />}
        {retryProjection.error && <ErrorState error={retryProjection.error} />}
        {projections.data && (
          <ProjectionJobs
            documentId={value.id}
            jobs={projections.data}
            retryDisabled={retryProjection.isPending}
            retryingType={retryProjection.variables}
            onRetry={retryProjection.mutate}
          />
        )}
      </Panel>

      <Panel title="修订历史与对比" description="按需加载两个修订的 Chunk 内容">
        <DocumentRevisionHistory documentId={value.id} />
      </Panel>

      <Panel
        title="活动修订的知识块"
        description="展示检索正文和来源章节路径"
      >
        {chunks.isPending && <LoadingState label="正在读取知识块" />}
        {chunks.error && <ErrorState error={chunks.error} />}
        {chunks.data && !chunks.data.length && (
          <EmptyState
            title="当前活动修订没有 Chunk"
            description="请检查抽取运行与投影状态。"
          />
        )}
        <div className="chunk-list document-detail-chunks">
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
      </Panel>
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
                  {revision.mediaType} · {revision.language} · {revision.chunkCount}{' '}
                  个 Chunk
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
  const rightByOrdinal = new Map(
    rightChunks.map((chunk) => [chunk.ordinal, chunk]),
  )
  const ordinals = [
    ...new Set([...leftByOrdinal.keys(), ...rightByOrdinal.keys()]),
  ].sort((left, right) => left - right)

  return (
    <div className="revision-comparison">
      <header>
        <strong>版本 {leftRevision.revisionNumber}</strong>
        <strong>版本 {rightRevision.revisionNumber}</strong>
      </header>
      {!ordinals.length && <div className="inline-empty">两个修订都没有 Chunk。</div>}
      {ordinals.map((ordinal) => {
        const left = leftByOrdinal.get(ordinal)
        const right = rightByOrdinal.get(ordinal)
        const changed = left?.contentHash !== right?.contentHash
        return (
          <div className="revision-compare-row" key={ordinal}>
            <RevisionChunk
              chunk={left}
              ordinal={ordinal}
              changed={changed}
              changeLabel={revisionChangeLabel(left, right, false)}
            />
            <RevisionChunk
              chunk={right}
              ordinal={ordinal}
              changed={changed}
              changeLabel={revisionChangeLabel(right, left, true)}
            />
          </div>
        )
      })}
    </div>
  )
}

function revisionChangeLabel(
  current: Chunk | undefined,
  opposite: Chunk | undefined,
  right: boolean,
) {
  if (!current) return '此版无内容'
  if (!opposite) return right ? '右侧新增' : '右侧删除'
  return current.contentHash === opposite.contentHash ? '相同' : '有变化'
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
  retryingType,
  onRetry,
}: {
  documentId: string
  jobs: ProjectionJob[]
  retryDisabled: boolean
  retryingType: ProjectionType | undefined
  onRetry: (projectionType: ProjectionType) => void
}) {
  if (!jobs.length) {
    return (
      <div className="inline-empty">
        当前文档没有可管理的活动修订投影任务；PostgreSQL
        关键词检索无需异步投影。
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
            <button disabled={retryDisabled} onClick={() => onRetry(job.projectionType)}>
              <RefreshCw
                className={retryingType === job.projectionType ? 'spin' : undefined}
                size={14}
              />
              {retryingType === job.projectionType ? '重新排队中…' : '重试 DEAD 任务'}
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

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
