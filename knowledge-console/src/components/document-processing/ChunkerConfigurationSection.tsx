import { Split } from 'lucide-react'
import type {
  AvailableChunker,
  AvailableEmbeddingProfile,
  AvailableParser,
  AvailableTokenizer,
  SpaceChunkerConfiguration,
  SpaceParserSelection,
} from '../../lib/api'
import {
  SEMANTIC_REFINEMENT_PROVIDER_ID,
  STRUCTURAL_PROVIDER_ID,
  capabilityUnavailableReason,
  isSemanticChunker,
  missingChunkerParserCapabilities,
  parserCapabilityLabel,
} from './document-processing-config-model'
import { ChunkSizingFields } from './ChunkSizingFields'
import { SemanticRefinementFields } from './SemanticRefinementFields'

/** 选择已安装 Chunker Provider，并编辑当前开放的通用切分参数。 */
export function ChunkerConfigurationSection({
  chunker,
  availableChunkers,
  availableTokenizers,
  availableEmbeddingProfiles,
  parserSelections,
  availableParsers,
  disabled,
  onChange,
}: {
  chunker: SpaceChunkerConfiguration
  availableChunkers: AvailableChunker[]
  availableTokenizers: AvailableTokenizer[]
  availableEmbeddingProfiles: AvailableEmbeddingProfile[]
  parserSelections: SpaceParserSelection[]
  availableParsers: AvailableParser[]
  disabled: boolean
  onChange: (chunker: SpaceChunkerConfiguration) => void
}) {
  const selectedProvider = availableChunkers.find(
    (item) => item.id === chunker.providerId,
  )
  const unavailableReason = selectedProviderReason(selectedProvider)
  const selectedProviderMissingCapabilities = missingChunkerParserCapabilities(
    selectedProvider,
    parserSelections,
    availableParsers,
  )
  return (
    <section className="processing-config-section">
      <header>
        <Split size={18} />
        <div>
          <h3>Chunker 实现</h3>
          <p>
            当前部署已发现 {availableChunkers.length} 个 Chunker。
            结构 Provider 负责安全基线；语义细化可以在可调整位置新增断点，
            也可以删除基线软断点，但不能跨越标题、表格、代码等硬边界。
          </p>
        </div>
      </header>
      <ChunkerCapabilityCatalog chunkers={availableChunkers} />
      <div className="form-grid chunker-fields">
        <label>
          Chunker Provider
          <select
            value={chunker.providerId}
            disabled={disabled}
            onChange={(event) => {
              const selected = availableChunkers.find(
                (item) => item.id === event.target.value,
              )
              if (!selected) return
              onChange({
                ...chunker,
                providerId: selected.id,
                providerConfig: { ...selected.defaultProviderConfig },
              })
            }}
          >
            {!availableChunkers.some(
              (item) => item.id === chunker.providerId,
            ) && (
              <option value={chunker.providerId} disabled>
                {chunkerLabel(chunker.providerId)}（当前部署不可用）
              </option>
            )}
            {availableChunkers.map((item) => {
              const incompatible =
                missingChunkerParserCapabilities(
                  item,
                  parserSelections,
                  availableParsers,
                ).length > 0
              return (
                <option
                  value={item.id}
                  disabled={!item.available || incompatible}
                  key={item.id}
                >
                  {chunkerLabel(item.id)} · {item.version}
                  {!item.available
                    ? '（不可用）'
                    : incompatible
                      ? '（Parser 能力不兼容）'
                      : ''}
                </option>
              )
            })}
          </select>
          {unavailableReason && <small>{unavailableReason}</small>}
          {selectedProviderMissingCapabilities.length > 0 && (
            <small>
              当前 Parser 映射缺少：
              {selectedProviderMissingCapabilities
                .map(parserCapabilityLabel)
                .join('、')}
            </small>
          )}
          {selectedProvider && (
            <div className="parser-capability-list" aria-label="Chunker 前置能力">
              <span title={selectedProvider.id}>{selectedProvider.id}</span>
              {selectedProvider.requiredParserCapabilities.map((capability) => (
                <span title={capability} key={capability}>
                  需要{parserCapabilityLabel(capability)}
                </span>
              ))}
            </div>
          )}
        </label>
        <ChunkSizingFields
          chunker={chunker}
          availableTokenizers={availableTokenizers}
          disabled={disabled}
          onChange={onChange}
        />
        {isSemanticChunker(chunker.providerId) && (
          <SemanticRefinementFields
            chunker={chunker}
            availableEmbeddingProfiles={availableEmbeddingProfiles}
            disabled={disabled}
            onChange={onChange}
          />
        )}
      </div>
    </section>
  )
}

function ChunkerCapabilityCatalog({
  chunkers,
}: {
  chunkers: AvailableChunker[]
}) {
  return (
    <div
      className="processing-capability-catalog"
      aria-label="Chunker 部署能力"
    >
      <div className="processing-capability-catalog-title">
        Chunker 部署能力（{chunkers.length}）
      </div>
      <div className="processing-capability-cards chunker-capability-cards">
        {chunkers.map((chunker) => (
          <article className={chunker.available ? '' : 'unavailable'} key={chunker.id}>
            <div>
              <strong>{chunkerLabel(chunker.id)}</strong>
              <span>{chunker.available ? '可用' : '不可用'}</span>
            </div>
            <code>{chunker.id}</code>
            <small>
              {chunker.available
                ? `版本 ${chunker.version}`
                : capabilityUnavailableReason(chunker.unavailableReason)}
            </small>
          </article>
        ))}
      </div>
    </div>
  )
}

function selectedProviderReason(capability: AvailableChunker | undefined) {
  return capability && !capability.available
    ? capabilityUnavailableReason(capability.unavailableReason)
    : undefined
}

function chunkerLabel(providerId: string) {
  if (providerId === SEMANTIC_REFINEMENT_PROVIDER_ID) return '结构内双向语义细化'
  if (providerId === STRUCTURAL_PROVIDER_ID) return '确定性结构切分'
  return providerId
}
