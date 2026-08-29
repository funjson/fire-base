import type {
  AvailableTokenizer,
  SpaceChunkerConfiguration,
} from '../../lib/api'
import { capabilityUnavailableReason } from './document-processing-config-model'

/** 编辑所有 Chunker 共用的 Token 预算，并解释当前计数器的真实精度。 */
export function ChunkSizingFields({
  chunker,
  availableTokenizers,
  disabled,
  onChange,
}: {
  chunker: SpaceChunkerConfiguration
  availableTokenizers: AvailableTokenizer[]
  disabled: boolean
  onChange: (chunker: SpaceChunkerConfiguration) => void
}) {
  const selectedTokenizer = availableTokenizers.find(
    (item) => item.id === chunker.tokenizerId,
  )
  return (
    <>
      <label>
        Token Counter
        <select
          value={chunker.tokenizerId}
          disabled={disabled}
          onChange={(event) =>
            onChange({ ...chunker, tokenizerId: event.target.value })
          }
        >
          {!selectedTokenizer && (
            <option value={chunker.tokenizerId} disabled>
              {chunker.tokenizerId}（当前部署不可用）
            </option>
          )}
          {availableTokenizers.map((item) => (
            <option value={item.id} key={item.id} disabled={!item.available}>
              {item.id} · {item.version}
              {!item.available
                ? ` · ${capabilityUnavailableReason(item.unavailableReason)}`
                : ''}
            </option>
          ))}
        </select>
        {selectedTokenizer && (
          <small>
            {selectedTokenizer.description}
            {!selectedTokenizer.available &&
              ` 当前不可用：${capabilityUnavailableReason(selectedTokenizer.unavailableReason)}`}
            {selectedTokenizer.exactModelTokens &&
              selectedTokenizer.available &&
              selectedTokenizer.modelProfileId &&
              ` 已由运维固定绑定模型配置 ${selectedTokenizer.modelProfileId}。`}
            {!selectedTokenizer.exactModelTokens &&
              ' 当前仅为稳定预算估算，不是模型精确 Token 数；接入精确 Tokenizer Adapter 前不能把它解释为模型硬上限。'}
          </small>
        )}
      </label>
      <label>
        最小 Token 数
        <input
          type="number"
          min={1}
          max={chunker.targetTokens}
          step={1}
          value={chunker.minimumTokens}
          disabled={disabled}
          onChange={(event) =>
            onChange({
              ...chunker,
              minimumTokens: Number(event.target.value),
            })
          }
        />
        <small>软边界下优先合并过小的相邻内容，不会越过硬结构边界。</small>
      </label>
      <label>
        目标 Token 数
        <input
          type="number"
          min={chunker.minimumTokens}
          max={chunker.maximumTokens}
          step={1}
          value={chunker.targetTokens}
          disabled={disabled}
          onChange={(event) =>
            onChange({ ...chunker, targetTokens: Number(event.target.value) })
          }
        />
        <small>普通 Chunk 尽量接近该预算。</small>
      </label>
      <label>
        最大 Token 数
        <input
          type="number"
          min={chunker.targetTokens}
          max={65_536}
          step={1}
          value={chunker.maximumTokens}
          disabled={disabled}
          onChange={(event) =>
            onChange({ ...chunker, maximumTokens: Number(event.target.value) })
          }
        />
        <small>
          最终装箱会按所选 Counter 执行该上限；非精确 Counter 不能等价为模型 Token 硬上限。
        </small>
      </label>
      <label>
        Overlap Token 数
        <input
          type="number"
          min={0}
          max={Math.max(0, chunker.minimumTokens - 1)}
          step={1}
          value={chunker.overlapTokens}
          disabled={disabled}
          onChange={(event) =>
            onChange({ ...chunker, overlapTokens: Number(event.target.value) })
          }
        />
        <small>最终边界确定后添加；必须小于最小 Token 数。</small>
      </label>
    </>
  )
}
