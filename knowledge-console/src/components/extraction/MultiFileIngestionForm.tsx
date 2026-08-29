import { DatabaseZap, FilePlus2, Send, Trash2, UploadCloud } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import type { IngestionManifestItem } from '../../lib/api'
import { ErrorState, Panel } from '../State'
import {
  selectSourceFiles,
  sourceFileSelectionError,
} from './source-file-selection'

/** 浏览器文件与正式发布属性的同序快照。 */
export type IngestionUploadEntry = IngestionManifestItem & { file: File }

/**
 * 正式多文件摄取表单。
 *
 * 每个文件显式携带逻辑来源键、检索标题和权威等级；上传入口不允许临时覆盖
 * Space 的 Parser、Cleaner、Chunker 或 Tokenizer 配置。
 */
export function MultiFileIngestionForm({
  entries,
  acceptedExtensions,
  disabled,
  pending,
  error,
  onEntriesChange,
  onSubmit,
}: {
  entries: IngestionUploadEntry[]
  acceptedExtensions: string[]
  disabled: boolean
  pending: boolean
  error: unknown
  onEntriesChange: (entries: IngestionUploadEntry[]) => void
  onSubmit: (entries: IngestionUploadEntry[], language: string) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [language, setLanguage] = useState('zh-CN')
  const [selectionError, setSelectionError] = useState<string>()

  function appendFiles(incoming: File[]) {
    const selection = selectSourceFiles(incoming, acceptedExtensions)
    const current = new Map(entries.map((entry) => [fileIdentity(entry.file), entry]))
    selection.accepted.forEach((file) => {
      const identity = fileIdentity(file)
      if (!current.has(identity)) {
        current.set(identity, {
          file,
          externalId: file.name,
          title: titleFromFileName(file.name),
          authority: 80,
        })
      }
    })
    setSelectionError(
      sourceFileSelectionError(
        selection,
        '正式摄取必须逐个保留原文件身份。',
      ),
    )
    onEntriesChange([...current.values()])
  }

  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!disabled) appendFiles([...event.dataTransfer.files])
  }

  const validationError = entries.length > 0 ? validate(entries) : undefined

  return (
    <Panel
      title="正式多文件摄取"
      description="原件先永久保留到对象存储，再异步 Parse → Clean → Chunk → 发布；重复内容不会覆盖已有文档。"
    >
      <form
        className="extraction-upload-form ingestion-upload-form"
        onSubmit={(event) => {
          event.preventDefault()
          if (!disabled && !validationError) onSubmit(entries, language)
        }}
      >
        <div className="notice-card ingestion-upload-notice">
          <DatabaseZap size={18} />
          <span>
            <strong>正式发布</strong>
            成功后会创建 Document、Revision 与投影任务；失败或取消不会删除 OSS 原件。
          </span>
        </div>
        <div
          className={`extraction-dropzone ${dragging ? 'dragging' : ''}`}
          onDragEnter={(event) => {
            event.preventDefault()
            if (!disabled) setDragging(true)
          }}
          onDragOver={(event) => event.preventDefault()}
          onDragLeave={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node)) {
              setDragging(false)
            }
          }}
          onDrop={drop}
        >
          <UploadCloud size={28} />
          <strong>拖放多个正式原文件</strong>
          <span>每个文件独立判重、失败和下载，不接受 ZIP</span>
          <button type="button" disabled={disabled} onClick={() => input.current?.click()}>
            <FilePlus2 size={15} />
            选择多个文件
          </button>
          <input
            ref={input}
            aria-label="选择多个正式摄取文件"
            className="visually-hidden"
            type="file"
            multiple
            accept={acceptedExtensions.join(',') || undefined}
            disabled={disabled}
            onChange={(event) => {
              appendFiles([...(event.target.files ?? [])])
              event.target.value = ''
            }}
          />
        </div>

        {selectionError && <ErrorState error={new Error(selectionError)} />}
        {entries.length > 0 && (
          <div className="ingestion-manifest-editor">
            <div className="ingestion-manifest-heading">
              <strong>逐文件发布属性 · {entries.length} 项</strong>
              <button type="button" onClick={() => onEntriesChange([])}>清空</button>
            </div>
            {entries.map((entry, index) => (
              <div className="ingestion-manifest-row" key={fileIdentity(entry.file)}>
                <div className="ingestion-manifest-file">
                  <strong>{entry.file.name}</strong>
                  <small>{formatBytes(entry.file.size)}</small>
                </div>
                <label>
                  稳定 externalId
                  <input
                    value={entry.externalId}
                    maxLength={512}
                    disabled={disabled}
                    onChange={(event) =>
                      updateEntry(entries, index, { externalId: event.target.value }, onEntriesChange)
                    }
                  />
                  <small>同一 Space 内标识逻辑来源；相同键的新内容会报冲突，不会覆盖。</small>
                </label>
                <label>
                  检索标题
                  <input
                    value={entry.title}
                    maxLength={512}
                    disabled={disabled}
                    onChange={(event) =>
                      updateEntry(entries, index, { title: event.target.value }, onEntriesChange)
                    }
                  />
                  <small>标题会参与关键词与向量检索，请使用人类可读的业务名称。</small>
                </label>
                <label className="ingestion-authority-field">
                  权威等级
                  <input
                    type="number"
                    min={0}
                    max={100}
                    step={1}
                    value={entry.authority}
                    disabled={disabled}
                    onChange={(event) =>
                      updateEntry(
                        entries,
                        index,
                        { authority: Number(event.target.value) },
                        onEntriesChange,
                      )
                    }
                  />
                  <small>用于来源质量排序，不表示访问权限。</small>
                </label>
                <button
                  aria-label={`移除 ${entry.file.name}`}
                  className="icon-button ingestion-remove-file"
                  type="button"
                  onClick={() => onEntriesChange(entries.filter((_, itemIndex) => itemIndex !== index))}
                >
                  <Trash2 size={14} />
                </button>
              </div>
            ))}
          </div>
        )}

        {validationError && <ErrorState error={new Error(validationError)} />}
        <div className="extraction-upload-actions">
          <label>
            文档语言
            <input
              value={language}
              maxLength={32}
              disabled={disabled}
              onChange={(event) => setLanguage(event.target.value)}
            />
          </label>
          <button
            className="primary-button"
            disabled={
              disabled
              || pending
              || entries.length === 0
              || Boolean(validationError)
              || !language.trim()
            }
          >
            <Send size={15} />
            {pending ? '创建正式任务中…' : '开始正式摄取'}
          </button>
        </div>
        {disabled && (
          <div className="inline-empty">同一 Space 有活动任务时不能创建新的正式摄取。</div>
        )}
        {Boolean(error) && <ErrorState error={error} />}
      </form>
    </Panel>
  )
}

function updateEntry(
  entries: IngestionUploadEntry[],
  index: number,
  update: Partial<IngestionManifestItem>,
  onEntriesChange: (entries: IngestionUploadEntry[]) => void,
) {
  onEntriesChange(entries.map((entry, itemIndex) =>
    itemIndex === index ? { ...entry, ...update } : entry,
  ))
}

function validate(entries: IngestionUploadEntry[]) {
  if (entries.length === 0) return '请至少选择一个正式原文件。'
  const externalIds = new Set<string>()
  for (const entry of entries) {
    const externalId = entry.externalId.trim()
    if (!externalId) return `${entry.file.name} 缺少 externalId。`
    if (!externalIds.add(externalId)) return `externalId “${externalId}” 在本批次中重复。`
    if (!entry.title.trim()) return `${entry.file.name} 缺少检索标题。`
    if (!Number.isInteger(entry.authority) || entry.authority < 0 || entry.authority > 100) {
      return `${entry.file.name} 的权威等级必须是 0 到 100 的整数。`
    }
  }
  return undefined
}

function fileIdentity(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}

function titleFromFileName(fileName: string) {
  const extensionAt = fileName.lastIndexOf('.')
  return extensionAt > 0 ? fileName.slice(0, extensionAt) : fileName
}

function formatBytes(value: number) {
  if (value < 1_024) return `${value} B`
  if (value < 1_048_576) return `${(value / 1_024).toFixed(1)} KB`
  return `${(value / 1_048_576).toFixed(1)} MB`
}
