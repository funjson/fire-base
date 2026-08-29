import { useQuery } from '@tanstack/react-query'
import { Blocks, LockKeyhole } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import type { CreateSpaceRequest } from '../lib/api'
import { useApi } from '../lib/use-api'
import { ChunkerConfigurationSection } from './document-processing/ChunkerConfigurationSection'
import { CleanerConfigurationSection } from './document-processing/CleanerConfigurationSection'
import {
  toDocumentProcessingConfigDraft,
  validateDocumentProcessingConfigDraft,
  type DocumentProcessingConfigDraft,
} from './document-processing/document-processing-config-model'
import { ParserConfigurationSection } from './document-processing/ParserConfigurationSection'
import { ErrorState, LoadingState, Panel } from './State'

/**
 * 在创建 Space 的同一个表单中选择完整处理配置。
 * 处理配置随创建请求原子提交；创建成功后页面只提供查看，避免同一 Space 混用不同索引语义。
 */
export function CreateSpaceDialog({
  pending,
  error,
  onSubmit,
  onClose,
}: {
  pending: boolean
  error: unknown
  onSubmit: (request: CreateSpaceRequest) => void
  onClose: () => void
}) {
  const api = useApi()
  const capabilities = useQuery({
    queryKey: ['document-processing-capabilities'],
    queryFn: api.documentProcessingCapabilities,
  })
  const [generatedSpaceId] = useState(() => `space-${crypto.randomUUID()}`)
  const [draftOverride, setDraft] =
    useState<DocumentProcessingConfigDraft>()
  const draft = capabilities.data
    ? draftOverride ??
      toDocumentProcessingConfigDraft(capabilities.data.defaultConfig)
    : undefined
  const validationErrors = useMemo(
    () =>
      capabilities.data && draft
        ? validateDocumentProcessingConfigDraft(capabilities.data, draft)
        : [],
    [capabilities.data, draft],
  )

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draft || validationErrors.length > 0 || pending) return
    const data = new FormData(event.currentTarget)
    onSubmit({
      spaceId: String(data.get('spaceId')).trim(),
      name: String(data.get('name')).trim(),
      description: String(data.get('description')).trim(),
      documentProcessingConfig: {
        parserSelections: draft.parserSelections,
        cleaning: draft.cleaning,
        chunker: draft.chunker,
      },
    })
  }

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !pending) onClose()
      }}
    >
      <Panel
        className="modal modal-wide"
        title="新建知识空间"
        description="同时确认解析、内容治理和切分配置；创建成功后该处理配置不可修改"
      >
        <div
          className="document-processing-config-content"
          onMouseDown={(event) => event.stopPropagation()}
        >
          {capabilities.isPending && (
            <LoadingState label="正在加载默认配置与处理能力" />
          )}
          {capabilities.error && <ErrorState error={capabilities.error} />}
          {!capabilities.data && (
            <div className="form-actions">
              {capabilities.error && (
                <button
                  type="button"
                  disabled={capabilities.isFetching}
                  onClick={() => capabilities.refetch()}
                >
                  {capabilities.isFetching ? '重试中…' : '重新加载'}
                </button>
              )}
              <button type="button" onClick={onClose}>
                取消
              </button>
            </div>
          )}
          {capabilities.data && draft && (
            <form
              aria-label="新建知识空间与文档处理配置"
              className="document-processing-config-form"
              onSubmit={submit}
            >
              <input name="spaceId" type="hidden" value={generatedSpaceId} />
              <section className="processing-config-section">
                <header>
                  <Blocks size={18} />
                  <div>
                    <h3>空间身份</h3>
                    <p>名称供用户识别，描述供 Space Router 判断知识范围。</p>
                  </div>
                </header>
                <div className="form-grid create-space-identity-fields">
                  <label>
                    显示名称
                    <input
                      name="name"
                      required
                      maxLength={256}
                      placeholder="研发知识库"
                      disabled={pending}
                    />
                  </label>
                  <label className="create-space-description-field">
                    空间描述
                    <textarea
                      name="description"
                      required
                      maxLength={2000}
                      rows={3}
                      placeholder="说明该空间包含的业务范围、适用问题和不包含的内容，供检索路由使用"
                      disabled={pending}
                    />
                    <small>描述会参与 Space 路由，请使用清晰的业务语言。</small>
                  </label>
                  <div className="generated-space-id-note">
                    <span>系统技术标识</span>
                    <code>{generatedSpaceId}</code>
                    <small>自动生成并保持稳定，业务人员无需维护。</small>
                  </div>
                </div>
              </section>

              <ParserConfigurationSection
                selections={draft.parserSelections}
                availableParsers={capabilities.data.availableParsers}
                disabled={pending}
                onChange={(parserSelections) =>
                  setDraft({ ...draft, parserSelections })
                }
              />
              <CleanerConfigurationSection
                cleaning={draft.cleaning}
                disabled={pending}
                onChange={(cleaning) => setDraft({ ...draft, cleaning })}
              />
              <ChunkerConfigurationSection
                chunker={draft.chunker}
                availableChunkers={capabilities.data.availableChunkers}
                availableTokenizers={capabilities.data.availableTokenizers}
                availableEmbeddingProfiles={
                  capabilities.data.availableEmbeddingProfiles
                }
                parserSelections={draft.parserSelections}
                availableParsers={capabilities.data.availableParsers}
                disabled={pending}
                onChange={(chunker) => setDraft({ ...draft, chunker })}
              />

              <div className="processing-config-impact-notice">
                <LockKeyhole size={17} />
                <span>
                  创建后如需更换 Parser、Chunker、Tokenizer 或其他索引处理参数，
                  请使用目标配置创建新的 Space 并重新摄取数据。
                </span>
              </div>

              {validationErrors.length > 0 && (
                <div className="processing-config-validation" role="alert">
                  {validationErrors.map((message) => (
                    <span key={message}>{message}</span>
                  ))}
                </div>
              )}
              {Boolean(error) && <ErrorState error={error} />}

              <footer className="processing-config-dialog-footer">
                <div>
                  <span>当前选项来自部署能力目录和系统默认配置。</span>
                  <span>创建操作会一次性保存 Space 与完整处理配置。</span>
                </div>
                <div className="form-actions">
                  <button type="button" disabled={pending} onClick={onClose}>
                    取消
                  </button>
                  <button
                    className="primary-button"
                    disabled={pending || validationErrors.length > 0}
                  >
                    {pending ? '创建中…' : '确认配置并创建'}
                  </button>
                </div>
              </footer>
            </form>
          )}
        </div>
      </Panel>
    </div>
  )
}
