import {
  Blend,
  BrainCircuit,
  GitBranch,
  ListTree,
  Route,
  SearchCode,
  X,
} from 'lucide-react'
import type { ChangeEvent, ReactNode } from 'react'
import type {
  RetrievalBranchConfiguration,
  RetrievalChainNodeId,
  RetrievalChannelId,
  RetrievalConfiguration,
  RetrievalConfigurationOverride,
} from '../../lib/api'
import {
  RETRIEVAL_CHAIN_NODES,
  RETRIEVAL_CHANNELS,
  normalizeRetrievalConfigurationOverride,
  retrievalChainNodeLabels,
  retrievalChannelLabels,
} from './retrieval-configuration-model'

/**
 * 单次检索的局部覆盖编辑器。
 *
 * 空白值表示由每个 Space 分别继承自己的物化配置；组件不会创建完整配置快照，也不会
 * 从任意一个 Space 复制值。这样同一请求选择多个 Space 时不会发生配置串用。
 */
export function RetrievalConfigurationOverrideFields({
  value,
  onChange,
}: {
  value: RetrievalConfigurationOverride | undefined
  onChange: (value: RetrievalConfigurationOverride | undefined) => void
}) {
  function updateFirstRound(
    field: keyof RetrievalConfiguration['firstRound'],
    next: string | number | boolean | undefined,
  ) {
    emit({
      ...value,
      firstRound: { ...value?.firstRound, [field]: next },
    })
  }

  function updateBranches(
    field: 'maximumVariantsPerAttempt' | 'maximumRetrievalBranches' | 'rrfConstant',
    next: number | undefined,
  ) {
    emit({ ...value, branches: { ...value?.branches, [field]: next } })
  }

  function updateChannel(
    channel: RetrievalChannelId,
    field: keyof RetrievalBranchConfiguration,
    next: number | boolean | undefined,
  ) {
    emit({
      ...value,
      branches: {
        ...value?.branches,
        channels: {
          ...value?.branches?.channels,
          [channel]: {
            ...value?.branches?.channels?.[channel],
            [field]: next,
          },
        },
      },
    })
  }

  function updateReranker(
    field: keyof RetrievalConfiguration['reranker'],
    next: string | number | boolean | undefined,
  ) {
    emit({ ...value, reranker: { ...value?.reranker, [field]: next } })
  }

  function updateCoverage(
    field: keyof RetrievalConfiguration['coverage'],
    next: string | number | boolean | undefined,
  ) {
    emit({ ...value, coverage: { ...value?.coverage, [field]: next } })
  }

  function updateChainNode(
    node: RetrievalChainNodeId,
    next: boolean | undefined,
  ) {
    emit({
      ...value,
      chainNodeEnables: { ...value?.chainNodeEnables, [node]: next },
    })
  }

  function updateCrossSpace(
    field: keyof RetrievalConfiguration['crossSpace'],
    next: number | boolean | undefined,
  ) {
    emit({ ...value, crossSpace: { ...value?.crossSpace, [field]: next } })
  }

  function emit(next: RetrievalConfigurationOverride) {
    onChange(normalizeRetrievalConfigurationOverride(next))
  }

  return (
    <div className="retrieval-config-sections retrieval-partial-config">
      <ConfigurationSection
        icon={<SearchCode size={17} />}
        title="首轮查询"
        description="只填写要改变的字段；原查询 Q0 始终参与召回。"
      >
        <OptionalBooleanField
          label="启用术语增强"
          value={value?.firstRound?.termExpansionEnabled}
          onChange={(next) => updateFirstRound('termExpansionEnabled', next)}
        />
        <OptionalTextField
          label="术语资源标识"
          value={value?.firstRound?.terminologyResourceId}
          maxLength={128}
          onChange={(next) => updateFirstRound('terminologyResourceId', next)}
        />
        <OptionalNumberField
          label="最多扩展术语"
          value={value?.firstRound?.maximumExpansionTerms}
          min={0}
          max={64}
          step={1}
          onChange={(next) => updateFirstRound('maximumExpansionTerms', next)}
        />
      </ConfigurationSection>

      <ConfigurationSection
        icon={<Blend size={17} />}
        title="召回分支与 RRF"
        description="各通道和预算按字段覆盖，未填写项继续使用各 Space 自己的值。"
      >
        <OptionalNumberField
          label="单次 Variant 上限"
          value={value?.branches?.maximumVariantsPerAttempt}
          min={1}
          max={16}
          step={1}
          onChange={(next) => updateBranches('maximumVariantsPerAttempt', next)}
        />
        <OptionalNumberField
          label="物理分支上限"
          value={value?.branches?.maximumRetrievalBranches}
          min={1}
          max={64}
          step={1}
          onChange={(next) => updateBranches('maximumRetrievalBranches', next)}
        />
        <OptionalNumberField
          label="RRF 平滑常数"
          value={value?.branches?.rrfConstant}
          min={1}
          max={10_000}
          step={1}
          onChange={(next) => updateBranches('rrfConstant', next)}
        />
        <div className="retrieval-partial-channel-list">
          {RETRIEVAL_CHANNELS.map((channel) => (
            <section key={channel} className="retrieval-partial-channel">
              <header>
                <strong>{retrievalChannelLabels[channel]}</strong>
                <code>{channel}</code>
              </header>
              <OptionalBooleanField
                compact
                label="通道状态"
                value={value?.branches?.channels?.[channel]?.enabled}
                onChange={(next) => updateChannel(channel, 'enabled', next)}
              />
              <OptionalNumberField
                compact
                label="TopK"
                value={value?.branches?.channels?.[channel]?.topK}
                min={1}
                max={1_000}
                step={1}
                onChange={(next) => updateChannel(channel, 'topK', next)}
              />
              <OptionalNumberField
                compact
                label="RRF 权重"
                value={value?.branches?.channels?.[channel]?.rrfWeight}
                min={0}
                max={100}
                step={0.1}
                onChange={(next) => updateChannel(channel, 'rrfWeight', next)}
              />
            </section>
          ))}
        </div>
      </ConfigurationSection>

      <ConfigurationSection
        icon={<BrainCircuit size={17} />}
        title="精排"
        description="只覆盖本次请求需要测试的 Reranker 字段。"
      >
        <OptionalBooleanField
          label="启用 Reranker"
          value={value?.reranker?.enabled}
          onChange={(next) => updateReranker('enabled', next)}
        />
        <OptionalTextField
          label="Provider 标识"
          value={value?.reranker?.providerId}
          maxLength={64}
          onChange={(next) => updateReranker('providerId', next)}
        />
        <OptionalTextField
          label="模型标识"
          value={value?.reranker?.modelId}
          maxLength={128}
          onChange={(next) => updateReranker('modelId', next)}
        />
        <OptionalNumberField
          label="候选窗口"
          value={value?.reranker?.candidateLimit}
          min={1}
          max={1_000}
          step={1}
          onChange={(next) => updateReranker('candidateLimit', next)}
        />
        <OptionalNumberField
          label="输出 TopK"
          value={value?.reranker?.outputTopK}
          min={1}
          max={1_000}
          step={1}
          onChange={(next) => updateReranker('outputTopK', next)}
        />
      </ConfigurationSection>

      <ConfigurationSection
        icon={<GitBranch size={17} />}
        title="Coverage 与尝试预算"
        description="后端会把这些字段分别合并到每个 Space 的当前配置后再校验。"
      >
        <OptionalBooleanField
          label="启用 Evidence Coverage"
          value={value?.coverage?.enabled}
          onChange={(next) => updateCoverage('enabled', next)}
        />
        <OptionalTextField
          label="Judge Provider"
          value={value?.coverage?.providerId}
          maxLength={64}
          onChange={(next) => updateCoverage('providerId', next)}
        />
        <OptionalTextField
          label="Judge 模型"
          value={value?.coverage?.modelId}
          maxLength={128}
          onChange={(next) => updateCoverage('modelId', next)}
        />
        <OptionalTextField
          label="Prompt 版本"
          value={value?.coverage?.promptVersion}
          maxLength={128}
          onChange={(next) => updateCoverage('promptVersion', next)}
        />
        <OptionalNumberField
          label="记忆候选上限"
          value={value?.coverage?.memoryLimit}
          min={1}
          max={100}
          step={1}
          onChange={(next) => updateCoverage('memoryLimit', next)}
        />
        <OptionalNumberField
          label="充分性阈值"
          value={value?.coverage?.sufficiencyThreshold}
          min={0}
          max={1}
          step={0.01}
          onChange={(next) => updateCoverage('sufficiencyThreshold', next)}
        />
        <OptionalNumberField
          label="最大检索尝试数"
          value={value?.maximumRetrievalAttempts}
          min={1}
          max={16}
          step={1}
          onChange={(next) =>
            emit({ ...value, maximumRetrievalAttempts: next })
          }
        />
      </ConfigurationSection>

      <ConfigurationSection
        icon={<ListTree size={17} />}
        title="固定优化 Chain"
        description="执行顺序由系统固定；每个空白节点都继承对应 Space 的开关。"
      >
        <div className="retrieval-partial-chain-grid">
          {RETRIEVAL_CHAIN_NODES.map((node, index) => (
            <OptionalBooleanField
              key={node}
              compact
              label={`${index + 1}. ${retrievalChainNodeLabels[node]}`}
              value={value?.chainNodeEnables?.[node]}
              onChange={(next) => updateChainNode(node, next)}
            />
          ))}
        </div>
        <div className="retrieval-partial-cross-space">
          <Route size={17} />
          <div>
            <strong>跨 Space</strong>
            <small>改变开关时请同时覆盖 NEXT_SPACE，避免与继承值冲突。</small>
          </div>
          <OptionalBooleanField
            compact
            label="允许跨 Space"
            value={value?.crossSpace?.enabled}
            onChange={(next) => updateCrossSpace('enabled', next)}
          />
          <OptionalNumberField
            compact
            label="最多访问 Space"
            value={value?.crossSpace?.maximumSpaces}
            min={1}
            max={16}
            step={1}
            onChange={(next) => updateCrossSpace('maximumSpaces', next)}
          />
        </div>
      </ConfigurationSection>
    </div>
  )
}

function ConfigurationSection({
  icon,
  title,
  description,
  children,
}: {
  icon: ReactNode
  title: string
  description: string
  children: ReactNode
}) {
  return (
    <section className="processing-config-section">
      <header>
        {icon}
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
      </header>
      <div className="retrieval-partial-fields">{children}</div>
    </section>
  )
}

function OptionalBooleanField({
  label,
  value,
  compact = false,
  onChange,
}: {
  label: string
  value: boolean | undefined
  compact?: boolean
  onChange: (value: boolean | undefined) => void
}) {
  return (
    <OptionalFieldShell
      active={value !== undefined}
      compact={compact}
      label={label}
      onClear={() => onChange(undefined)}
    >
      <select
        aria-label={label}
        value={value === undefined ? '' : value ? 'true' : 'false'}
        onChange={(event) => {
          const next = event.currentTarget.value
          onChange(next === '' ? undefined : next === 'true')
        }}
      >
        <option value="">继承各 Space</option>
        <option value="true">启用</option>
        <option value="false">关闭</option>
      </select>
    </OptionalFieldShell>
  )
}

function OptionalTextField({
  label,
  value,
  maxLength,
  onChange,
}: {
  label: string
  value: string | undefined
  maxLength: number
  onChange: (value: string | undefined) => void
}) {
  return (
    <OptionalFieldShell
      active={value !== undefined}
      label={label}
      onClear={() => onChange(undefined)}
    >
      <input
        aria-label={label}
        value={value ?? ''}
        maxLength={maxLength}
        placeholder="继承各 Space"
        onChange={(event) => onChange(event.currentTarget.value || undefined)}
      />
    </OptionalFieldShell>
  )
}

function OptionalNumberField({
  label,
  value,
  min,
  max,
  step,
  compact = false,
  onChange,
}: {
  label: string
  value: number | undefined
  min: number
  max: number
  step: number
  compact?: boolean
  onChange: (value: number | undefined) => void
}) {
  return (
    <OptionalFieldShell
      active={value !== undefined}
      compact={compact}
      label={label}
      onClear={() => onChange(undefined)}
    >
      <input
        type="number"
        aria-label={label}
        value={value ?? ''}
        min={min}
        max={max}
        step={step}
        placeholder="继承各 Space"
        onChange={(event) => onChange(optionalNumberValue(event))}
      />
    </OptionalFieldShell>
  )
}

function OptionalFieldShell({
  active,
  compact,
  label,
  children,
  onClear,
}: {
  active: boolean
  compact?: boolean
  label: string
  children: ReactNode
  onClear: () => void
}) {
  return (
    <label
      className={`retrieval-partial-field${active ? ' active' : ''}${
        compact ? ' compact' : ''
      }`}
    >
      <span>
        <strong>{label}</strong>
        <small>{active ? '本次覆盖' : '继承各 Space'}</small>
      </span>
      <div>
        {children}
        {active && (
          <button
            type="button"
            aria-label={`移除${label}覆盖`}
            title="移除此项覆盖"
            onClick={onClear}
          >
            <X size={13} />
          </button>
        )}
      </div>
    </label>
  )
}

function optionalNumberValue(event: ChangeEvent<HTMLInputElement>) {
  if (event.currentTarget.value === '') return undefined
  const value = event.currentTarget.valueAsNumber
  return Number.isFinite(value) ? value : undefined
}
