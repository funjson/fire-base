import {
  Blend,
  BrainCircuit,
  GitBranch,
  ListTree,
  Route,
  SearchCode,
} from 'lucide-react'
import type { ChangeEvent } from 'react'
import type {
  RetrievalBranchConfiguration,
  RetrievalChainNodeId,
  RetrievalChannelId,
  RetrievalConfiguration,
} from '../../lib/api'
import {
  RETRIEVAL_CHAIN_NODES,
  RETRIEVAL_CHANNELS,
  retrievalChainNodeLabels,
  retrievalChannelLabels,
} from './retrieval-configuration-model'

export function RetrievalConfigurationFields({
  value,
  disabled = false,
  onChange,
}: {
  value: RetrievalConfiguration
  disabled?: boolean
  onChange: (value: RetrievalConfiguration) => void
}) {
  function updateBranch(
    channel: RetrievalChannelId,
    patch: Partial<RetrievalBranchConfiguration>,
  ) {
    onChange({
      ...value,
      branches: {
        ...value.branches,
        channels: {
          ...value.branches.channels,
          [channel]: { ...value.branches.channels[channel], ...patch },
        },
      },
    })
  }

  function updateChainNode(node: RetrievalChainNodeId, enabled: boolean) {
    onChange({
      ...value,
      chainNodeEnables: { ...value.chainNodeEnables, [node]: enabled },
      crossSpace:
        node === 'NEXT_SPACE'
          ? {
              ...value.crossSpace,
              enabled,
              maximumSpaces: enabled
                ? Math.max(2, value.crossSpace.maximumSpaces)
                : value.crossSpace.maximumSpaces,
            }
          : value.crossSpace,
    })
  }

  function updateCrossSpace(enabled: boolean) {
    onChange({
      ...value,
      chainNodeEnables: { ...value.chainNodeEnables, NEXT_SPACE: enabled },
      crossSpace: {
        ...value.crossSpace,
        enabled,
        maximumSpaces: enabled
          ? Math.max(2, value.crossSpace.maximumSpaces)
          : value.crossSpace.maximumSpaces,
      },
    })
  }

  return (
    <div className="retrieval-config-sections">
      <section className="processing-config-section">
        <header>
          <SearchCode size={17} />
          <div>
            <h3>首轮查询</h3>
            <p>原查询 Q0 始终参与召回；这里只控制受控术语增强。</p>
          </div>
        </header>
        <div className="retrieval-config-fields">
          <ToggleField
            checked={value.firstRound.termExpansionEnabled}
            disabled={disabled}
            label="启用术语增强"
            description="在 Q0 之外增加受限的术语扩展 Query Variant"
            onChange={(termExpansionEnabled) =>
              onChange({
                ...value,
                firstRound: { ...value.firstRound, termExpansionEnabled },
              })
            }
          />
          <div className="form-grid">
            <label>
              术语资源标识
              <input
                value={value.firstRound.terminologyResourceId}
                disabled={disabled}
                maxLength={128}
                onChange={(event) =>
                  onChange({
                    ...value,
                    firstRound: {
                      ...value.firstRound,
                      terminologyResourceId: event.currentTarget.value,
                    },
                  })
                }
              />
            </label>
            <NumberField
              label="最多扩展术语"
              value={value.firstRound.maximumExpansionTerms}
              min={0}
              max={64}
              step={1}
              disabled={disabled}
              onChange={(maximumExpansionTerms) =>
                onChange({
                  ...value,
                  firstRound: { ...value.firstRound, maximumExpansionTerms },
                })
              }
            />
          </div>
        </div>
      </section>

      <section className="processing-config-section">
        <header>
          <Blend size={17} />
          <div>
            <h3>召回分支与 RRF</h3>
            <p>控制 Variant 放大、物理分支预算、候选深度和融合权重。</p>
          </div>
        </header>
        <div className="retrieval-config-fields">
          <div className="retrieval-budget-grid">
            <NumberField
              label="单次 Variant 上限"
              value={value.branches.maximumVariantsPerAttempt}
              min={1}
              max={16}
              step={1}
              disabled={disabled}
              onChange={(maximumVariantsPerAttempt) =>
                onChange({
                  ...value,
                  branches: { ...value.branches, maximumVariantsPerAttempt },
                })
              }
            />
            <NumberField
              label="物理分支上限"
              value={value.branches.maximumRetrievalBranches}
              min={1}
              max={64}
              step={1}
              disabled={disabled}
              onChange={(maximumRetrievalBranches) =>
                onChange({
                  ...value,
                  branches: { ...value.branches, maximumRetrievalBranches },
                })
              }
            />
            <NumberField
              label="RRF 平滑常数"
              value={value.branches.rrfConstant}
              min={1}
              max={10_000}
              step={1}
              disabled={disabled}
              onChange={(rrfConstant) =>
                onChange({
                  ...value,
                  branches: { ...value.branches, rrfConstant },
                })
              }
            />
          </div>
          <div className="retrieval-branch-table" aria-label="召回通道配置">
            <div className="retrieval-branch-row header" aria-hidden="true">
              <span>通道</span>
              <span>状态</span>
              <span>TopK</span>
              <span>RRF 权重</span>
            </div>
            {RETRIEVAL_CHANNELS.map((channel) => {
              const branch = value.branches.channels[channel]
              return (
                <div className="retrieval-branch-row" key={channel}>
                  <div>
                    <strong>{retrievalChannelLabels[channel]}</strong>
                    <code>{channel}</code>
                  </div>
                  <label className="retrieval-compact-toggle">
                    <input
                      type="checkbox"
                      checked={branch.enabled}
                      disabled={disabled}
                      aria-label={`启用${retrievalChannelLabels[channel]}`}
                      onChange={(event) =>
                        updateBranch(channel, { enabled: event.currentTarget.checked })
                      }
                    />
                    <span>{branch.enabled ? '启用' : '关闭'}</span>
                  </label>
                  <input
                    type="number"
                    aria-label={`${retrievalChannelLabels[channel]} TopK`}
                    value={branch.topK}
                    min={1}
                    max={1_000}
                    step={1}
                    disabled={disabled}
                    onChange={(event) =>
                      updateBranch(channel, { topK: numberValue(event) })
                    }
                  />
                  <input
                    type="number"
                    aria-label={`${retrievalChannelLabels[channel]} RRF 权重`}
                    value={branch.rrfWeight}
                    min={0}
                    max={100}
                    step={0.1}
                    disabled={disabled}
                    onChange={(event) =>
                      updateBranch(channel, { rrfWeight: numberValue(event) })
                    }
                  />
                </div>
              )
            })}
          </div>
        </div>
      </section>

      <section className="processing-config-section">
        <header>
          <BrainCircuit size={17} />
          <div>
            <h3>精排</h3>
            <p>限制送入 Reranker 的候选集；失败时运行时回退到 RRF 顺序。</p>
          </div>
        </header>
        <div className="retrieval-config-fields">
          <ToggleField
            checked={value.reranker.enabled}
            disabled={disabled}
            label="启用 Reranker"
            description="调用结构化、有限候选的精排实现"
            onChange={(enabled) =>
              onChange({ ...value, reranker: { ...value.reranker, enabled } })
            }
          />
          <div className="form-grid retrieval-model-grid">
            <TextField
              label="Provider 标识"
              value={value.reranker.providerId}
              maxLength={64}
              disabled={disabled}
              onChange={(providerId) =>
                onChange({ ...value, reranker: { ...value.reranker, providerId } })
              }
            />
            <TextField
              label="模型标识"
              value={value.reranker.modelId}
              maxLength={128}
              disabled={disabled}
              onChange={(modelId) =>
                onChange({ ...value, reranker: { ...value.reranker, modelId } })
              }
            />
            <NumberField
              label="候选窗口"
              value={value.reranker.candidateLimit}
              min={1}
              max={1_000}
              step={1}
              disabled={disabled}
              onChange={(candidateLimit) =>
                onChange({
                  ...value,
                  reranker: { ...value.reranker, candidateLimit },
                })
              }
            />
            <NumberField
              label="输出 TopK"
              value={value.reranker.outputTopK}
              min={1}
              max={1_000}
              step={1}
              disabled={disabled}
              onChange={(outputTopK) =>
                onChange({ ...value, reranker: { ...value.reranker, outputTopK } })
              }
            />
          </div>
        </div>
      </section>

      <section className="processing-config-section">
        <header>
          <GitBranch size={17} />
          <div>
            <h3>Coverage 与尝试预算</h3>
            <p>判断证据是否充分，并为后续优化 Chain 设置有限记忆和总尝试次数。</p>
          </div>
        </header>
        <div className="retrieval-config-fields">
          <ToggleField
            checked={value.coverage.enabled}
            disabled={disabled}
            label="启用 Evidence Coverage"
            description="使用结构化 Judge 判断证据缺口和终止条件"
            onChange={(enabled) =>
              onChange({ ...value, coverage: { ...value.coverage, enabled } })
            }
          />
          <div className="form-grid retrieval-model-grid">
            <TextField
              label="Judge Provider"
              value={value.coverage.providerId}
              maxLength={64}
              disabled={disabled}
              onChange={(providerId) =>
                onChange({ ...value, coverage: { ...value.coverage, providerId } })
              }
            />
            <TextField
              label="Judge 模型"
              value={value.coverage.modelId}
              maxLength={128}
              disabled={disabled}
              onChange={(modelId) =>
                onChange({ ...value, coverage: { ...value.coverage, modelId } })
              }
            />
            <TextField
              label="Prompt 版本"
              value={value.coverage.promptVersion}
              maxLength={128}
              disabled={disabled}
              onChange={(promptVersion) =>
                onChange({
                  ...value,
                  coverage: { ...value.coverage, promptVersion },
                })
              }
            />
            <NumberField
              label="记忆候选上限"
              value={value.coverage.memoryLimit}
              min={1}
              max={100}
              step={1}
              disabled={disabled}
              onChange={(memoryLimit) =>
                onChange({ ...value, coverage: { ...value.coverage, memoryLimit } })
              }
            />
            <NumberField
              label="充分性阈值"
              value={value.coverage.sufficiencyThreshold}
              min={0}
              max={1}
              step={0.01}
              disabled={disabled}
              onChange={(sufficiencyThreshold) =>
                onChange({
                  ...value,
                  coverage: { ...value.coverage, sufficiencyThreshold },
                })
              }
            />
            <NumberField
              label="最大检索尝试数"
              value={value.maximumRetrievalAttempts}
              min={1}
              max={16}
              step={1}
              disabled={disabled}
              onChange={(maximumRetrievalAttempts) =>
                onChange({ ...value, maximumRetrievalAttempts })
              }
            />
          </div>
        </div>
      </section>

      <section className="processing-config-section">
        <header>
          <ListTree size={17} />
          <div>
            <h3>固定优化 Chain</h3>
            <p>执行顺序由系统固定，Space 只控制节点是否参与，不能自由重排。</p>
          </div>
        </header>
        <div className="retrieval-config-fields">
          <div className="retrieval-chain-grid" aria-label="检索 Chain 节点开关">
            {RETRIEVAL_CHAIN_NODES.map((node, index) => (
              <label key={node} className="retrieval-chain-node">
                <span className="retrieval-chain-order">{index + 1}</span>
                <input
                  type="checkbox"
                  checked={value.chainNodeEnables[node]}
                  disabled={disabled}
                  onChange={(event) =>
                    updateChainNode(node, event.currentTarget.checked)
                  }
                />
                <span>
                  <strong>{retrievalChainNodeLabels[node]}</strong>
                  <code>{node}</code>
                </span>
              </label>
            ))}
          </div>
          <div className="retrieval-cross-space">
            <Route size={17} />
            <ToggleField
              checked={value.crossSpace.enabled}
              disabled={disabled}
              label="允许跨 Space 顺序重试"
              description="同步控制固定 Chain 末尾的 NEXT_SPACE 节点"
              onChange={updateCrossSpace}
            />
            <NumberField
              label="最多访问 Space"
              value={value.crossSpace.maximumSpaces}
              min={1}
              max={16}
              step={1}
              disabled={disabled}
              onChange={(maximumSpaces) =>
                onChange({
                  ...value,
                  crossSpace: { ...value.crossSpace, maximumSpaces },
                })
              }
            />
          </div>
        </div>
      </section>
    </div>
  )
}

function ToggleField({
  checked,
  disabled,
  label,
  description,
  onChange,
}: {
  checked: boolean
  disabled: boolean
  label: string
  description: string
  onChange: (checked: boolean) => void
}) {
  return (
    <label className="retrieval-toggle-field">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.currentTarget.checked)}
      />
      <span>
        <strong>{label}</strong>
        <small>{description}</small>
      </span>
    </label>
  )
}

function TextField({
  label,
  value,
  maxLength,
  disabled,
  onChange,
}: {
  label: string
  value: string
  maxLength: number
  disabled: boolean
  onChange: (value: string) => void
}) {
  return (
    <label>
      {label}
      <input
        value={value}
        maxLength={maxLength}
        disabled={disabled}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
    </label>
  )
}

function NumberField({
  label,
  value,
  min,
  max,
  step,
  disabled,
  onChange,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  disabled: boolean
  onChange: (value: number) => void
}) {
  return (
    <label>
      {label}
      <input
        type="number"
        value={value}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        onChange={(event) => onChange(numberValue(event))}
      />
    </label>
  )
}

function numberValue(event: ChangeEvent<HTMLInputElement>) {
  const value = event.currentTarget.valueAsNumber
  return Number.isFinite(value) ? value : 0
}
