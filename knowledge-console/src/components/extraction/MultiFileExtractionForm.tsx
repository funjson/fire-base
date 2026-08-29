import { FilePlus2, Play, Trash2, UploadCloud } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import { ErrorState, Panel } from '../State'
import {
  selectSourceFiles,
  sourceFileSelectionError,
} from './source-file-selection'

/** 直接选择多个原文件，不用 ZIP 隐藏逐文件的校验和错误边界。 */
export function MultiFileExtractionForm({
  files,
  acceptedExtensions,
  disabled,
  pending,
  error,
  onFilesChange,
  onSubmit,
}: {
  files: File[]
  acceptedExtensions: string[]
  disabled: boolean
  pending: boolean
  error: unknown
  onFilesChange: (files: File[]) => void
  onSubmit: (files: File[], language: string) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [language, setLanguage] = useState('zh-CN')
  const [selectionError, setSelectionError] = useState<string>()

  function appendFiles(incoming: File[]) {
    const selection = selectSourceFiles(incoming, acceptedExtensions)
    setSelectionError(
      sourceFileSelectionError(selection, '请直接选择其中的原文件。'),
    )
    const unique = new Map(
      [...files, ...selection.accepted].map((file) => [fileIdentity(file), file]),
    )
    onFilesChange([...unique.values()])
  }

  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!disabled) appendFiles([...event.dataTransfer.files])
  }

  return (
    <Panel
      title="多文件抽取测试"
      description="原文件会逐个展示 Parse → Clean → Chunk 结果；测试模式不写入正式知识索引。"
    >
      <form
        className="extraction-upload-form"
        onSubmit={(event) => {
          event.preventDefault()
          if (files.length && !disabled) onSubmit(files, language)
        }}
      >
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
          <strong>拖放多个原文件到这里</strong>
          <span>不需要、也不接受 ZIP 打包</span>
          <button
            type="button"
            disabled={disabled}
            onClick={() => input.current?.click()}
          >
            <FilePlus2 size={15} />
            选择多个文件
          </button>
          <input
            ref={input}
            aria-label="选择多个源文件"
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
        {files.length > 0 && (
          <div className="extraction-selected-files">
            <div>
              <strong>已选 {files.length} 个文件</strong>
              <button type="button" onClick={() => onFilesChange([])}>
                清空
              </button>
            </div>
            <ul>
              {files.map((file) => (
                <li key={fileIdentity(file)}>
                  <span>
                    <strong>{file.name}</strong>
                    <small>{formatBytes(file.size)}</small>
                  </span>
                  <button
                    aria-label={`移除 ${file.name}`}
                    className="icon-button"
                    type="button"
                    onClick={() =>
                      onFilesChange(
                        files.filter((candidate) => candidate !== file),
                      )
                    }
                  >
                    <Trash2 size={14} />
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

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
            disabled={disabled || pending || files.length === 0 || !language.trim()}
          >
            <Play size={15} />
            {pending ? '创建中…' : '运行抽取测试'}
          </button>
        </div>
        {disabled && (
          <div className="inline-empty">处理配置加载完成后才能创建测试运行。</div>
        )}
        {Boolean(error) && <ErrorState error={error} />}
      </form>
    </Panel>
  )
}

function fileIdentity(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}

function formatBytes(value: number) {
  if (value < 1_024) return `${value} B`
  if (value < 1_048_576) return `${(value / 1_024).toFixed(1)} KB`
  return `${(value / 1_048_576).toFixed(1)} MB`
}
