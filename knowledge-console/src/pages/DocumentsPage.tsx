import { useQuery } from '@tanstack/react-query'
import {
  ChevronLeft,
  ChevronRight,
  FileText,
  Filter,
  RotateCcw,
  Upload,
} from 'lucide-react'
import { useState, type FormEvent, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import { useApi } from '../lib/use-api'

const pageSize = 25

type DocumentFilters = {
  spaceId: string
  status: string
  title: string
  source: string
  keywordStatus: string
  vectorStatus: string
  minimumChunkCount: string
  maximumChunkCount: string
  updatedFrom: string
  updatedTo: string
}

const emptyFilters: DocumentFilters = {
  spaceId: '',
  status: '',
  title: '',
  source: '',
  keywordStatus: '',
  vectorStatus: '',
  minimumChunkCount: '',
  maximumChunkCount: '',
  updatedFrom: '',
  updatedTo: '',
}

/** 文档列表只负责筛选和导航，诊断与生命周期操作由独立详情页承担。 */
export function DocumentsPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [draftFilters, setDraftFilters] = useState<DocumentFilters>(emptyFilters)
  const [appliedFilters, setAppliedFilters] =
    useState<DocumentFilters>(emptyFilters)
  const [filterError, setFilterError] = useState<string>()
  const [offset, setOffset] = useState(0)
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const documents = useQuery({
    queryKey: ['documents', appliedFilters, offset],
    queryFn: () =>
      api.documents({
        spaceId: optionalText(appliedFilters.spaceId),
        status: optionalText(appliedFilters.status),
        title: optionalText(appliedFilters.title),
        source: optionalText(appliedFilters.source),
        keywordStatus: optionalText(appliedFilters.keywordStatus),
        vectorStatus: optionalText(appliedFilters.vectorStatus),
        minimumChunkCount: optionalNonNegativeInteger(
          appliedFilters.minimumChunkCount,
        ),
        maximumChunkCount: optionalNonNegativeInteger(
          appliedFilters.maximumChunkCount,
        ),
        updatedFrom: localDateTimeToInstant(appliedFilters.updatedFrom),
        updatedTo: localDateTimeToInstant(appliedFilters.updatedTo),
        limit: pageSize,
        offset,
      }),
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

  function updateFilter<Key extends keyof DocumentFilters>(
    key: Key,
    value: DocumentFilters[Key],
  ) {
    setDraftFilters((current) => ({ ...current, [key]: value }))
    setFilterError(undefined)
  }

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const error = validateFilterRange(draftFilters)
    if (error) {
      setFilterError(error)
      return
    }
    setFilterError(undefined)
    setOffset(0)
    setAppliedFilters({ ...draftFilters })
  }

  function resetFilters() {
    setDraftFilters(emptyFilters)
    setAppliedFilters(emptyFilters)
    setFilterError(undefined)
    setOffset(0)
  }

  function openDocument(documentId: string) {
    navigate(`/documents/${encodeURIComponent(documentId)}`)
  }

  function openDocumentFromKeyboard(
    event: KeyboardEvent<HTMLTableRowElement>,
    documentId: string,
  ) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      openDocument(documentId)
    }
  }

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SOURCE TO EVIDENCE</span>
          <h2>查看文档、活动修订和索引投影</h2>
          <p>筛选文档后进入独立详情页，检查原文、修订、Chunk 和投影状态。</p>
        </div>
        <button
          className="primary-button"
          disabled={!draftFilters.spaceId}
          title={
            draftFilters.spaceId
              ? '进入该空间的数据抽取与摄取工作台'
              : '请先选择知识空间'
          }
          onClick={() =>
            navigate(
              `/spaces/${encodeURIComponent(draftFilters.spaceId)}/extraction`,
            )
          }
        >
          <Upload size={17} />
          {draftFilters.spaceId ? '多文件摄取' : '先选择空间'}
        </button>
      </div>

      <Panel>
        <form className="toolbar document-filter-toolbar" onSubmit={applyFilters}>
          <label>
            文档名称
            <input
              value={draftFilters.title}
              maxLength={512}
              placeholder="输入标题关键字"
              onChange={(event) => updateFilter('title', event.target.value)}
            />
          </label>
          <label>
            知识空间
            <select
              value={draftFilters.spaceId}
              onChange={(event) => updateFilter('spaceId', event.target.value)}
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
            来源
            <input
              value={draftFilters.source}
              maxLength={512}
              placeholder="类型、文件名或来源地址"
              onChange={(event) => updateFilter('source', event.target.value)}
            />
          </label>
          <label>
            文档状态
            <select
              value={draftFilters.status}
              onChange={(event) => updateFilter('status', event.target.value)}
            >
              <option value="">默认（不含已删除）</option>
              <option value="ACTIVE">活动</option>
              <option value="ARCHIVED">已归档</option>
              <option value="DEPRECATED">已弃用</option>
              <option value="DELETED">已删除</option>
            </select>
          </label>
          <ProjectionStatusFilter
            label="关键词索引"
            value={draftFilters.keywordStatus}
            onChange={(value) => updateFilter('keywordStatus', value)}
          />
          <ProjectionStatusFilter
            label="向量索引"
            value={draftFilters.vectorStatus}
            onChange={(value) => updateFilter('vectorStatus', value)}
          />
          <label>
            Chunk 数下限
            <input
              value={draftFilters.minimumChunkCount}
              type="number"
              min={0}
              step={1}
              placeholder="不限"
              onChange={(event) =>
                updateFilter('minimumChunkCount', event.target.value)
              }
            />
          </label>
          <label>
            Chunk 数上限
            <input
              value={draftFilters.maximumChunkCount}
              type="number"
              min={0}
              step={1}
              placeholder="不限"
              onChange={(event) =>
                updateFilter('maximumChunkCount', event.target.value)
              }
            />
          </label>
          <label>
            更新起始时间
            <input
              value={draftFilters.updatedFrom}
              type="datetime-local"
              onChange={(event) => updateFilter('updatedFrom', event.target.value)}
            />
          </label>
          <label>
            更新截止时间
            <input
              value={draftFilters.updatedTo}
              type="datetime-local"
              onChange={(event) => updateFilter('updatedTo', event.target.value)}
            />
          </label>
          <div className="document-filter-actions">
            <button type="button" onClick={resetFilters}>
              <RotateCcw size={15} />
              重置
            </button>
            <button className="primary-button" disabled={documents.isFetching}>
              <Filter size={15} />
              {documents.isFetching ? '筛选中…' : '应用筛选'}
            </button>
          </div>
          <span className="toolbar-count">
            {documents.isFetching ? '正在刷新 · ' : ''}
            共 {documents.data.total} 篇文档
          </span>
          {filterError && (
            <div className="document-filter-error" role="alert">
              {filterError}
            </div>
          )}
        </form>

        <div className="document-index-help" role="note">
          <strong>索引状态说明：</strong>
          关键词索引和向量索引表示文档<strong>当前活动修订</strong>
          在对应检索通道中的投影状态；它们不是原始文件是否存在的标记。
        </div>

        {!documents.data.items.length ? (
          <EmptyState
            title="没有符合条件的文档"
            description="正式多文件摄取或连接器同步后，文档会显示在这里。"
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
                  <th>Chunk 数</th>
                  <th>关键词索引</th>
                  <th>向量索引</th>
                  <th>更新时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {documents.data.items.map((document) => (
                  <tr
                    className="document-table-row"
                    key={document.id}
                    role="link"
                    tabIndex={0}
                    onClick={() => openDocument(document.id)}
                    onKeyDown={(event) =>
                      openDocumentFromKeyboard(event, document.id)
                    }
                  >
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
                        {document.sourceMediaType && (
                          <small>{document.sourceMediaType}</small>
                        )}
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
                onClick={() =>
                  setOffset((current) => Math.max(0, current - pageSize))
                }
              >
                <ChevronLeft size={15} />
                上一页
              </button>
              <button
                disabled={
                  documents.isFetching || lastDocument >= documents.data.total
                }
                onClick={() => setOffset((current) => current + pageSize)}
              >
                下一页
                <ChevronRight size={15} />
              </button>
            </div>
          </div>
        )}
      </Panel>
    </div>
  )
}

function ProjectionStatusFilter({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label>
      {label}
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">全部状态</option>
        <option value="SUCCEEDED">已完成</option>
        <option value="PENDING">等待中</option>
        <option value="FAILED">失败</option>
        <option value="SKIPPED">未创建或已跳过</option>
      </select>
    </label>
  )
}

function validateFilterRange(filters: DocumentFilters) {
  const minimum = optionalNonNegativeInteger(filters.minimumChunkCount)
  const maximum = optionalNonNegativeInteger(filters.maximumChunkCount)
  if (minimum !== undefined && maximum !== undefined && minimum > maximum) {
    return 'Chunk 数下限不能大于上限。'
  }
  if (
    filters.updatedFrom &&
    filters.updatedTo &&
    new Date(filters.updatedFrom).getTime() > new Date(filters.updatedTo).getTime()
  ) {
    return '更新起始时间不能晚于截止时间。'
  }
  return undefined
}

function optionalText(value: string) {
  const normalized = value.trim()
  return normalized || undefined
}

function optionalNonNegativeInteger(value: string) {
  if (!value) return undefined
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : undefined
}

/** datetime-local 使用浏览器本地时区，API 统一传递带时区的 UTC Instant。 */
function localDateTimeToInstant(value: string) {
  if (!value) return undefined
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
