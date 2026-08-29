import { FlaskConical, RotateCcw } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import type { RetrievalConfigurationOverride } from '../../lib/api'
import { Panel } from '../State'
import { RetrievalConfigurationOverrideFields } from './RetrievalConfigurationOverrideFields'
import {
  normalizeRetrievalConfigurationOverride,
  retrievalConfigurationOverrideFieldCount,
  validateRetrievalConfigurationOverride,
} from './retrieval-configuration-model'

/** 编辑一次测试请求携带的局部强类型覆盖，不读取或写回任何 Space 配置。 */
export function RetrievalConfigurationOverrideDialog({
  initialOverride,
  onApply,
  onClose,
}: {
  initialOverride: RetrievalConfigurationOverride | undefined
  onApply: (configuration: RetrievalConfigurationOverride | undefined) => void
  onClose: () => void
}) {
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <Panel
        className="modal modal-retrieval-config"
        title="本次检索参数覆盖"
        description="只发送明确填写的字段；空白项由每个 Space 继承自己的当前配置"
      >
        <div
          className="document-processing-config-content"
          onMouseDown={(event) => event.stopPropagation()}
        >
          <RetrievalOverrideForm
            initialOverride={initialOverride}
            onApply={onApply}
            onClose={onClose}
          />
        </div>
      </Panel>
    </div>
  )
}

function RetrievalOverrideForm({
  initialOverride,
  onApply,
  onClose,
}: {
  initialOverride: RetrievalConfigurationOverride | undefined
  onApply: (configuration: RetrievalConfigurationOverride | undefined) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<RetrievalConfigurationOverride | undefined>(
    () => normalizeRetrievalConfigurationOverride(initialOverride),
  )
  const errors = useMemo(
    () => validateRetrievalConfigurationOverride(draft),
    [draft],
  )
  const fieldCount = retrievalConfigurationOverrideFieldCount(draft)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (errors.length > 0) return
    onApply(normalizeRetrievalConfigurationOverride(draft))
  }

  return (
    <form
      aria-label="本次检索参数覆盖"
      className="document-processing-config-form"
      onSubmit={submit}
    >
      <div className="extraction-test-config-safety-notice">
        <FlaskConical size={17} />
        <div>
          <strong>只覆盖本次检索请求</strong>
          <span>
            当前明确覆盖 {fieldCount} 项。没有填写的字段不会出现在请求中，后端会按
            Space 分别继承并校验；配置不会保存，测试指标与在线请求隔离。
          </span>
        </div>
      </div>

      <RetrievalConfigurationOverrideFields value={draft} onChange={setDraft} />

      {errors.length > 0 && (
        <div className="processing-config-validation" role="alert">
          {errors.map((message) => (
            <span key={message}>{message}</span>
          ))}
        </div>
      )}

      <footer className="processing-config-dialog-footer">
        <div>
          <span>
            {fieldCount > 0
              ? `本次只发送 ${fieldCount} 项局部覆盖。`
              : '未设置覆盖，提交后完全继承各 Space 当前配置。'}
          </span>
        </div>
        <div className="form-actions">
          {fieldCount > 0 && (
            <button type="button" onClick={() => setDraft(undefined)}>
              <RotateCcw size={14} />
              清空全部覆盖
            </button>
          )}
          <button type="button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={errors.length > 0}>
            用于本次检索
          </button>
        </div>
      </footer>
    </form>
  )
}
