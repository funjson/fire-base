import { FlaskConical, RotateCcw } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import type { SpaceDocumentProcessingConfig } from '../../lib/api'
import { Panel } from '../State'
import { ChunkerConfigurationSection } from '../document-processing/ChunkerConfigurationSection'
import { CleanerConfigurationSection } from '../document-processing/CleanerConfigurationSection'
import {
  sameDocumentProcessingConfigDraft,
  toDocumentProcessingConfigDraft,
  validateDocumentProcessingConfigDraft,
  type DocumentProcessingConfigDraft,
} from '../document-processing/document-processing-config-model'
import { ParserConfigurationSection } from '../document-processing/ParserConfigurationSection'

/**
 * 编辑当前页面用于 TEST_ONLY 请求的临时配置。
 * 本组件不执行持久化操作，正式摄取也不会读取这里的参数。
 */
export function ExtractionTestConfigDialog({
  config,
  initialDraft,
  onApply,
  onClose,
}: {
  config: SpaceDocumentProcessingConfig
  initialDraft: DocumentProcessingConfigDraft
  onApply: (draft: DocumentProcessingConfigDraft | undefined) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState(initialDraft)
  const validationErrors = useMemo(
    () => validateDocumentProcessingConfigDraft(config, draft),
    [config, draft],
  )
  const spaceDraft = useMemo(
    () => toDocumentProcessingConfigDraft(config),
    [config],
  )
  const overridesSpace = !sameDocumentProcessingConfigDraft(draft, spaceDraft)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (validationErrors.length > 0) return
    onApply(overridesSpace ? draft : undefined)
  }

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <Panel
        className="modal modal-wide"
        title="设置本次测试配置"
        description={`基于 Space 配置 v${config.version} 调整；只随测试请求发送，不保存到 Space。`}
      >
        <div
          className="document-processing-config-content"
          onMouseDown={(event) => event.stopPropagation()}
        >
          <form
            aria-label="本次测试配置"
            className="document-processing-config-form"
            onSubmit={submit}
          >
            <div className="extraction-test-config-safety-notice">
              <FlaskConical size={17} />
              <div>
                <strong>只覆盖测试请求</strong>
                <span>
                  页面会把完整配置随多文件抽取测试或 Dataset 实验发送。
                  Space 固定配置、正式摄取和已经创建的运行都不会改变。
                </span>
              </div>
            </div>

            <ParserConfigurationSection
              selections={draft.parserSelections}
              availableParsers={config.availableParsers}
              disabled={false}
              onChange={(parserSelections) =>
                setDraft({ ...draft, parserSelections })
              }
            />
            <CleanerConfigurationSection
              cleaning={draft.cleaning}
              disabled={false}
              onChange={(cleaning) => setDraft({ ...draft, cleaning })}
            />
            <ChunkerConfigurationSection
              chunker={draft.chunker}
              availableChunkers={config.availableChunkers}
              availableTokenizers={config.availableTokenizers}
              availableEmbeddingProfiles={config.availableEmbeddingProfiles}
              parserSelections={draft.parserSelections}
              availableParsers={config.availableParsers}
              disabled={false}
              onChange={(chunker) => setDraft({ ...draft, chunker })}
            />

            {validationErrors.length > 0 && (
              <div className="processing-config-validation" role="alert">
                {validationErrors.map((message) => (
                  <span key={message}>{message}</span>
                ))}
              </div>
            )}

            <footer className="processing-config-dialog-footer">
              <div>
                <span>
                  {overridesSpace
                    ? `本次测试将覆盖 Space 配置 v${config.version}`
                    : `当前与 Space 配置 v${config.version} 一致`}
                </span>
              </div>
              <div className="form-actions">
                <button
                  type="button"
                  onClick={() =>
                    setDraft(toDocumentProcessingConfigDraft(config))
                  }
                >
                  <RotateCcw size={14} />
                  恢复 Space 配置
                </button>
                <button type="button" onClick={onClose}>
                  取消
                </button>
                <button
                  className="primary-button"
                  disabled={validationErrors.length > 0}
                >
                  用于本次测试
                </button>
              </div>
            </footer>
          </form>
        </div>
      </Panel>
    </div>
  )
}
