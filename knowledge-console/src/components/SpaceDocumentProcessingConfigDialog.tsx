import { useQuery } from '@tanstack/react-query'
import { LockKeyhole } from 'lucide-react'
import type { Space } from '../lib/api'
import { useApi } from '../lib/use-api'
import { ChunkerConfigurationSection } from './document-processing/ChunkerConfigurationSection'
import { CleanerConfigurationSection } from './document-processing/CleanerConfigurationSection'
import { toDocumentProcessingConfigDraft } from './document-processing/document-processing-config-model'
import { ParserConfigurationSection } from './document-processing/ParserConfigurationSection'
import { ProcessingContractDetails } from './document-processing/ProcessingContractDetails'
import { ErrorState, LoadingState, Panel } from './State'

const documentProcessingConfigQueryKey = (spaceId: string) => [
  'space-document-processing-config',
  spaceId,
]

/**
 * 展示 Space 创建时已经固定的文档处理配置。
 * 页面故意不提供保存动作；需要改变索引处理语义时必须创建新的 Space。
 */
export function SpaceDocumentProcessingConfigDialog({
  space,
  onClose,
}: {
  space: Space
  onClose: () => void
}) {
  const api = useApi()
  const configQuery = useQuery({
    queryKey: documentProcessingConfigQueryKey(space.id),
    queryFn: () => api.spaceDocumentProcessingConfig(space.id),
  })
  const draft = configQuery.data
    ? toDocumentProcessingConfigDraft(configQuery.data)
    : undefined

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <Panel
        className="modal modal-wide"
        title={`${space.name} · 文档处理配置`}
        description="查看 Space 创建时固定的解析、内容治理与知识切分策略"
      >
        <div
          className="document-processing-config-content"
          onMouseDown={(event) => event.stopPropagation()}
        >
          {configQuery.isPending && (
            <LoadingState label="正在读取文档处理配置" />
          )}
          {configQuery.error && <ErrorState error={configQuery.error} />}
          {!configQuery.data && (
            <div className="form-actions">
              {configQuery.error && (
                <button
                  type="button"
                  disabled={configQuery.isFetching}
                  onClick={() => configQuery.refetch()}
                >
                  {configQuery.isFetching ? '重试中…' : '重新加载'}
                </button>
              )}
              <button type="button" onClick={onClose}>
                关闭
              </button>
            </div>
          )}
          {configQuery.data && draft && (
            <form
              aria-label="Space 文档处理配置（只读）"
              className="document-processing-config-form"
            >
              <div className="processing-config-lock-notice">
                <LockKeyhole size={17} />
                <div>
                  <strong>处理配置在 Space 创建时已固定</strong>
                  <span>
                    需要调整 Parser、Cleaner、Chunker、Tokenizer 或模型参数时，
                    请使用目标配置创建新的 Space。测试配置只影响对应的测试运行。
                  </span>
                </div>
              </div>

              <ParserConfigurationSection
                selections={draft.parserSelections}
                availableParsers={configQuery.data.availableParsers}
                disabled
                onChange={() => undefined}
              />
              <CleanerConfigurationSection
                cleaning={draft.cleaning}
                disabled
                onChange={() => undefined}
              />
              <ChunkerConfigurationSection
                chunker={draft.chunker}
                availableChunkers={configQuery.data.availableChunkers}
                availableTokenizers={configQuery.data.availableTokenizers}
                availableEmbeddingProfiles={
                  configQuery.data.availableEmbeddingProfiles
                }
                parserSelections={draft.parserSelections}
                availableParsers={configQuery.data.availableParsers}
                disabled
                onChange={() => undefined}
              />

              <ProcessingContractDetails
                contract={configQuery.data.processingContract}
                runtimeContractMatched={configQuery.data.runtimeContractMatched}
              />

              <footer className="processing-config-dialog-footer">
                <div>
                  <span>配置版本 {configQuery.data.version}</span>
                  <span>
                    创建于 {formatTime(configQuery.data.updatedAt)} ·{' '}
                    {configQuery.data.updatedBy}
                  </span>
                </div>
                <div className="form-actions">
                  <button type="button" onClick={onClose}>
                    关闭
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

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
