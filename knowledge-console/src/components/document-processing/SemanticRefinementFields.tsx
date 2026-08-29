import type {
  AvailableEmbeddingProfile,
  SpaceChunkerConfiguration,
} from '../../lib/api'
import { semanticRefinementProviderConfig } from './document-processing-config-model'

/** 编辑内置双向语义细化参数；模型身份只能从部署能力目录选择。 */
export function SemanticRefinementFields({
  chunker,
  availableEmbeddingProfiles,
  disabled,
  onChange,
}: {
  chunker: SpaceChunkerConfiguration
  availableEmbeddingProfiles: AvailableEmbeddingProfile[]
  disabled: boolean
  onChange: (chunker: SpaceChunkerConfiguration) => void
}) {
  const semantic = semanticRefinementProviderConfig(chunker.providerConfig)
  if (!semantic) {
    return (
      <div className="processing-config-validation" role="alert">
        当前语义 Provider 配置不是控制台支持的规范结构，请重新选择 Provider。
      </div>
    )
  }
  const selectedProfile = availableEmbeddingProfiles.find(
    (item) => item.id === semantic.embeddingProfileId,
  )
  function update(values: Partial<typeof semantic>) {
    onChange({
      ...chunker,
      providerConfig: { ...semantic, ...values },
    })
  }
  return (
    <>
      <label>
        Embedding Profile
        <select
          value={semantic.embeddingProfileId}
          disabled={disabled}
          onChange={(event) =>
            update({ embeddingProfileId: event.target.value })
          }
        >
          {!selectedProfile && (
            <option value={semantic.embeddingProfileId} disabled>
              {semantic.embeddingProfileId}（当前部署不可用）
            </option>
          )}
          {availableEmbeddingProfiles.map((profile) => (
            <option value={profile.id} key={profile.id}>
              {profile.id}
            </option>
          ))}
        </select>
        {selectedProfile && <small>{selectedProfile.description}</small>}
      </label>
      <label>
        边界上下文片段数
        <select
          value={semantic.contextSlices}
          disabled={disabled}
          onChange={(event) =>
            update({ contextSlices: Number(event.target.value) })
          }
        >
          <option value={0}>0 · 仅比较边界两侧片段</option>
          <option value={1}>1 · 两侧各补充 1 个相邻片段</option>
          <option value={2}>2 · 两侧各补充 2 个相邻片段</option>
        </select>
        <small>只扩大判断窗口，不允许越过结构硬边界。</small>
      </label>
      <label>
        拆分相似度阈值
        <input
          type="number"
          min={-1}
          max={1}
          step={0.01}
          value={semantic.splitSimilarityThreshold}
          disabled={disabled}
          onChange={(event) =>
            update({ splitSimilarityThreshold: Number(event.target.value) })
          }
        />
        <small>相似度不高于该值时，在可调整位置新增断点。</small>
      </label>
      <label>
        合并相似度阈值
        <input
          type="number"
          min={-1}
          max={1}
          step={0.01}
          value={semantic.mergeSimilarityThreshold}
          disabled={disabled}
          onChange={(event) =>
            update({ mergeSimilarityThreshold: Number(event.target.value) })
          }
        />
        <small>相似度不低于该值时，删除软断点；中间区间保留结构基线。</small>
      </label>
    </>
  )
}
