import type {
  RetrievalChainNodeId,
  RetrievalChannelId,
  RetrievalConfiguration,
  RetrievalConfigurationOverride,
} from '../../lib/api'

export const RETRIEVAL_CHANNELS: RetrievalChannelId[] = [
  'KEYWORD',
  'VECTOR',
  'GRAPH',
  'PAGE',
]

export const RETRIEVAL_CHAIN_NODES: RetrievalChainNodeId[] = [
  'GAP_QUERY',
  'PRF',
  'RELAX_CONSTRAINTS',
  'NARROW_CONSTRAINTS',
  'STEP_BACK',
  'HYDE',
  'NEXT_SPACE',
]

export const retrievalChannelLabels: Record<RetrievalChannelId, string> = {
  KEYWORD: '关键词召回',
  VECTOR: '向量召回',
  GRAPH: '知识图召回',
  PAGE: '知识页召回',
}

export const retrievalChainNodeLabels: Record<RetrievalChainNodeId, string> = {
  GAP_QUERY: '证据缺口查询',
  PRF: '伪相关反馈',
  RELAX_CONSTRAINTS: '放宽约束',
  NARROW_CONSTRAINTS: '收紧约束',
  STEP_BACK: '上位问题',
  HYDE: '假设文档查询',
  NEXT_SPACE: '进入下一 Space',
}

export const spaceRetrievalConfigurationQueryKey = (spaceId: string) => [
  'space-retrieval-configuration',
  spaceId,
]

/** 与后端 deterministicBaseline 一致的前端编辑起点，不会自动写入 Space。 */
export function deterministicRetrievalConfiguration(): RetrievalConfiguration {
  return {
    firstRound: {
      termExpansionEnabled: false,
      terminologyResourceId: 'none',
      maximumExpansionTerms: 0,
    },
    branches: {
      maximumVariantsPerAttempt: 1,
      maximumRetrievalBranches: 4,
      rrfConstant: 60,
      channels: {
        KEYWORD: { enabled: true, topK: 40, rrfWeight: 1 },
        VECTOR: { enabled: true, topK: 40, rrfWeight: 1 },
        GRAPH: { enabled: true, topK: 20, rrfWeight: 0.8 },
        PAGE: { enabled: true, topK: 20, rrfWeight: 0.8 },
      },
    },
    reranker: {
      enabled: false,
      providerId: 'deterministic',
      modelId: 'rrf-order',
      candidateLimit: 40,
      outputTopK: 8,
    },
    coverage: {
      enabled: false,
      providerId: 'deterministic',
      modelId: 'none',
      promptVersion: 'none',
      memoryLimit: 20,
      sufficiencyThreshold: 0.75,
    },
    maximumRetrievalAttempts: 1,
    chainNodeEnables: {
      GAP_QUERY: false,
      PRF: false,
      RELAX_CONSTRAINTS: false,
      NARROW_CONSTRAINTS: false,
      STEP_BACK: false,
      HYDE: false,
      NEXT_SPACE: false,
    },
    crossSpace: { enabled: false, maximumSpaces: 1 },
  }
}

/** 从查询缓存快照创建独立草稿，避免表单直接修改服务端对象。 */
export function cloneRetrievalConfiguration(
  value: RetrievalConfiguration,
): RetrievalConfiguration {
  return {
    firstRound: { ...value.firstRound },
    branches: {
      ...value.branches,
      channels: {
        KEYWORD: { ...value.branches.channels.KEYWORD },
        VECTOR: { ...value.branches.channels.VECTOR },
        GRAPH: { ...value.branches.channels.GRAPH },
        PAGE: { ...value.branches.channels.PAGE },
      },
    },
    reranker: { ...value.reranker },
    coverage: { ...value.coverage },
    maximumRetrievalAttempts: value.maximumRetrievalAttempts,
    chainNodeEnables: { ...value.chainNodeEnables },
    crossSpace: { ...value.crossSpace },
  }
}

/**
 * 克隆单次请求的局部覆盖，并移除没有叶子字段的空对象。
 *
 * 覆盖对象不能先补成完整配置，否则一次多 Space 请求会把某个 Space 的值误用到其他
 * Space。这里始终保留“调用方明确提供了哪些字段”这一事实。
 */
export function normalizeRetrievalConfigurationOverride(
  value: RetrievalConfigurationOverride | undefined,
): RetrievalConfigurationOverride | undefined {
  if (!value) return undefined
  const firstRound = definedObject(value.firstRound)
  const channelEntries = RETRIEVAL_CHANNELS.flatMap((channel) => {
    const branch = definedObject(value.branches?.channels?.[channel])
    return branch ? [[channel, branch] as const] : []
  })
  const channels = channelEntries.length
    ? (Object.fromEntries(channelEntries) as NonNullable<
        NonNullable<RetrievalConfigurationOverride['branches']>['channels']
      >)
    : undefined
  const branches = definedObject({
    maximumVariantsPerAttempt: value.branches?.maximumVariantsPerAttempt,
    maximumRetrievalBranches: value.branches?.maximumRetrievalBranches,
    rrfConstant: value.branches?.rrfConstant,
    channels,
  })
  const reranker = definedObject(value.reranker)
  const coverage = definedObject(value.coverage)
  const chainNodeEnables = definedObject(value.chainNodeEnables)
  const crossSpace = definedObject(value.crossSpace)
  return definedObject({
    firstRound,
    branches,
    reranker,
    coverage,
    maximumRetrievalAttempts: value.maximumRetrievalAttempts,
    chainNodeEnables,
    crossSpace,
  })
}

/** 统计用户明确覆盖的叶子字段，供测试广场清楚展示本次请求影响面。 */
export function retrievalConfigurationOverrideFieldCount(
  value: RetrievalConfigurationOverride | undefined,
): number {
  return countDefinedLeaves(value)
}

/**
 * 只校验本次显式提供的字段。跨字段规则仅在相关字段都被覆盖时提前提示；最终配置仍由
 * 后端针对每个 Space 分别合并、校验，前端不能用一个 Space 的值推断另一个 Space。
 */
export function validateRetrievalConfigurationOverride(
  value: RetrievalConfigurationOverride | undefined,
): string[] {
  const override = normalizeRetrievalConfigurationOverride(value)
  if (!override) return []
  const errors: string[] = []
  const firstRound = override.firstRound
  if (
    firstRound?.terminologyResourceId !== undefined &&
    !stableId(firstRound.terminologyResourceId, 128)
  ) {
    errors.push('术语资源标识只能包含字母、数字、点、下划线、冒号或短横线。')
  }
  if (
    firstRound?.maximumExpansionTerms !== undefined &&
    !integerBetween(firstRound.maximumExpansionTerms, 0, 64)
  ) {
    errors.push('扩展术语数必须是 0 到 64 之间的整数。')
  }
  if (
    firstRound?.termExpansionEnabled === true &&
    firstRound.maximumExpansionTerms !== undefined &&
    firstRound.maximumExpansionTerms < 1
  ) {
    errors.push('启用术语增强时，显式覆盖的扩展术语数至少为 1。')
  }

  const branches = override.branches
  if (
    branches?.maximumVariantsPerAttempt !== undefined &&
    !integerBetween(branches.maximumVariantsPerAttempt, 1, 16)
  ) {
    errors.push('单次 Query Variant 上限必须是 1 到 16 之间的整数。')
  }
  if (
    branches?.maximumRetrievalBranches !== undefined &&
    !integerBetween(branches.maximumRetrievalBranches, 1, 64)
  ) {
    errors.push('单次物理分支上限必须是 1 到 64 之间的整数。')
  }
  if (
    branches?.maximumVariantsPerAttempt !== undefined &&
    branches.maximumRetrievalBranches !== undefined &&
    branches.maximumRetrievalBranches < branches.maximumVariantsPerAttempt
  ) {
    errors.push('单次物理分支上限不能小于单次 Query Variant 上限。')
  }
  if (
    branches?.rrfConstant !== undefined &&
    !integerBetween(branches.rrfConstant, 1, 10_000)
  ) {
    errors.push('RRF 平滑常数必须是 1 到 10000 之间的整数。')
  }
  for (const channel of RETRIEVAL_CHANNELS) {
    const branch = branches?.channels?.[channel]
    const label = retrievalChannelLabels[channel]
    if (
      branch?.topK !== undefined &&
      !integerBetween(branch.topK, 1, 1_000)
    ) {
      errors.push(`${label} TopK 必须是 1 到 1000 之间的整数。`)
    }
    if (
      branch?.rrfWeight !== undefined &&
      (!Number.isFinite(branch.rrfWeight) ||
        branch.rrfWeight < 0 ||
        branch.rrfWeight > 100)
    ) {
      errors.push(`${label} RRF 权重必须在 0 到 100 之间。`)
    }
    if (branch?.enabled === true && branch.rrfWeight !== undefined && branch.rrfWeight <= 0) {
      errors.push(`${label} 启用时，显式覆盖的 RRF 权重必须大于 0。`)
    }
  }

  validateModelOverride(override.reranker, 'Reranker', errors)
  if (
    override.reranker?.candidateLimit !== undefined &&
    !integerBetween(override.reranker.candidateLimit, 1, 1_000)
  ) {
    errors.push('精排候选窗口必须是 1 到 1000 之间的整数。')
  }
  if (
    override.reranker?.outputTopK !== undefined &&
    !integerBetween(override.reranker.outputTopK, 1, 1_000)
  ) {
    errors.push('精排输出 TopK 必须是 1 到 1000 之间的整数。')
  }
  if (
    override.reranker?.candidateLimit !== undefined &&
    override.reranker.outputTopK !== undefined &&
    override.reranker.outputTopK > override.reranker.candidateLimit
  ) {
    errors.push('精排输出 TopK 不能超过显式覆盖的候选窗口。')
  }

  validateModelOverride(override.coverage, 'Coverage', errors)
  if (
    override.coverage?.promptVersion !== undefined &&
    !stableId(override.coverage.promptVersion, 128)
  ) {
    errors.push('Coverage Prompt 版本格式无效。')
  }
  if (
    override.coverage?.memoryLimit !== undefined &&
    !integerBetween(override.coverage.memoryLimit, 1, 100)
  ) {
    errors.push('Coverage 记忆候选上限必须是 1 到 100 之间的整数。')
  }
  if (
    override.coverage?.sufficiencyThreshold !== undefined &&
    (!Number.isFinite(override.coverage.sufficiencyThreshold) ||
      override.coverage.sufficiencyThreshold < 0 ||
      override.coverage.sufficiencyThreshold > 1)
  ) {
    errors.push('Coverage 充分性阈值必须在 0 到 1 之间。')
  }
  if (
    override.maximumRetrievalAttempts !== undefined &&
    !integerBetween(override.maximumRetrievalAttempts, 1, 16)
  ) {
    errors.push('检索尝试预算必须是 1 到 16 之间的整数。')
  }
  if (
    override.crossSpace?.maximumSpaces !== undefined &&
    !integerBetween(override.crossSpace.maximumSpaces, 1, 16)
  ) {
    errors.push('最多访问 Space 数必须是 1 到 16 之间的整数。')
  }
  if (
    override.crossSpace?.enabled !== undefined &&
    override.chainNodeEnables?.NEXT_SPACE !== undefined &&
    override.crossSpace.enabled !== override.chainNodeEnables.NEXT_SPACE
  ) {
    errors.push('同时覆盖跨 Space 和 NEXT_SPACE 时，两者开关必须保持一致。')
  }
  return [...new Set(errors)]
}

function validateModelOverride(
  value:
    | RetrievalConfigurationOverride['reranker']
    | RetrievalConfigurationOverride['coverage'],
  label: string,
  errors: string[],
) {
  if (value?.providerId !== undefined && !stableId(value.providerId, 64)) {
    errors.push(`${label} Provider 标识格式无效。`)
  }
  if (value?.modelId !== undefined && !stableId(value.modelId, 128)) {
    errors.push(`${label} 模型标识格式无效。`)
  }
}

function definedObject<T extends object>(value: T | undefined): T | undefined {
  if (!value) return undefined
  const entries = Object.entries(value).filter(([, item]) => item !== undefined)
  return entries.length > 0 ? (Object.fromEntries(entries) as T) : undefined
}

function countDefinedLeaves(value: unknown): number {
  if (value === undefined || value === null) return 0
  if (typeof value !== 'object') return 1
  return Object.values(value).reduce<number>(
    (total, item) => total + countDefinedLeaves(item),
    0,
  )
}

export function sameRetrievalConfiguration(
  left: RetrievalConfiguration,
  right: RetrievalConfiguration,
) {
  return JSON.stringify(left) === JSON.stringify(right)
}

/** 复核领域绝对边界；部署级硬上限仍由后端统一拒绝。 */
export function validateRetrievalConfiguration(
  value: RetrievalConfiguration,
): string[] {
  const errors: string[] = []
  const firstRound = value.firstRound
  if (!stableId(firstRound.terminologyResourceId, 128)) {
    errors.push('术语资源标识只能包含字母、数字、点、下划线、冒号或短横线。')
  }
  if (!integerBetween(firstRound.maximumExpansionTerms, 0, 64)) {
    errors.push('扩展术语数必须是 0 到 64 之间的整数。')
  }
  if (firstRound.termExpansionEnabled && firstRound.maximumExpansionTerms < 1) {
    errors.push('启用术语增强时，扩展术语数至少为 1。')
  }

  const branches = value.branches
  if (!integerBetween(branches.maximumVariantsPerAttempt, 1, 16)) {
    errors.push('单次 Query Variant 上限必须是 1 到 16 之间的整数。')
  }
  if (!integerBetween(branches.maximumRetrievalBranches, 1, 64)) {
    errors.push('单次物理分支上限必须是 1 到 64 之间的整数。')
  }
  if (branches.maximumRetrievalBranches < branches.maximumVariantsPerAttempt) {
    errors.push('单次物理分支上限不能小于单次 Query Variant 上限。')
  }
  if (
    firstRound.termExpansionEnabled &&
    branches.maximumVariantsPerAttempt < 2
  ) {
    errors.push(
      '启用首轮术语增强时，单次 Query Variant 上限至少为 2，以同时保留原始查询。',
    )
  }
  if (!integerBetween(branches.rrfConstant, 1, 10_000)) {
    errors.push('RRF 平滑常数必须是 1 到 10000 之间的整数。')
  }
  if (!RETRIEVAL_CHANNELS.some((channel) => branches.channels[channel].enabled)) {
    errors.push('至少需要启用一个召回通道。')
  }
  for (const channel of RETRIEVAL_CHANNELS) {
    const branch = branches.channels[channel]
    const label = retrievalChannelLabels[channel]
    if (!integerBetween(branch.topK, 1, 1_000)) {
      errors.push(`${label} TopK 必须是 1 到 1000 之间的整数。`)
    }
    if (
      !Number.isFinite(branch.rrfWeight) ||
      branch.rrfWeight < 0 ||
      branch.rrfWeight > 100
    ) {
      errors.push(`${label} RRF 权重必须在 0 到 100 之间。`)
    }
    if (branch.enabled && branch.rrfWeight <= 0) {
      errors.push(`${label} 启用时 RRF 权重必须大于 0。`)
    }
  }

  if (!stableId(value.reranker.providerId, 64)) {
    errors.push('Reranker Provider 标识格式无效。')
  }
  if (!stableId(value.reranker.modelId, 128)) {
    errors.push('Reranker 模型标识格式无效。')
  }
  if (!integerBetween(value.reranker.candidateLimit, 1, 1_000)) {
    errors.push('精排候选窗口必须是 1 到 1000 之间的整数。')
  }
  if (
    !integerBetween(value.reranker.outputTopK, 1, 1_000) ||
    value.reranker.outputTopK > value.reranker.candidateLimit
  ) {
    errors.push('精排输出 TopK 必须为正整数，且不能超过候选窗口。')
  }

  if (!stableId(value.coverage.providerId, 64)) {
    errors.push('Coverage Provider 标识格式无效。')
  }
  if (!stableId(value.coverage.modelId, 128)) {
    errors.push('Coverage 模型标识格式无效。')
  }
  if (!stableId(value.coverage.promptVersion, 128)) {
    errors.push('Coverage Prompt 版本格式无效。')
  }
  if (!integerBetween(value.coverage.memoryLimit, 1, 100)) {
    errors.push('Coverage 记忆候选上限必须是 1 到 100 之间的整数。')
  }
  if (
    !Number.isFinite(value.coverage.sufficiencyThreshold) ||
    value.coverage.sufficiencyThreshold < 0 ||
    value.coverage.sufficiencyThreshold > 1
  ) {
    errors.push('Coverage 充分性阈值必须在 0 到 1 之间。')
  }
  if (!integerBetween(value.maximumRetrievalAttempts, 1, 16)) {
    errors.push('检索尝试预算必须是 1 到 16 之间的整数。')
  }
  const chainEnabled = RETRIEVAL_CHAIN_NODES.some(
    (node) => value.chainNodeEnables[node],
  )
  if (chainEnabled && !value.coverage.enabled) {
    errors.push('启用任一优化 Chain 节点时必须启用 Coverage。')
  }
  if (chainEnabled && value.maximumRetrievalAttempts < 2) {
    errors.push('启用任一优化 Chain 节点时，检索尝试预算至少为 2。')
  }
  if (value.crossSpace.enabled !== value.chainNodeEnables.NEXT_SPACE) {
    errors.push('跨 Space 开关必须与 NEXT_SPACE 节点保持一致。')
  }
  if (!integerBetween(value.crossSpace.maximumSpaces, 1, 16)) {
    errors.push('最多访问 Space 数必须是 1 到 16 之间的整数。')
  }
  if (value.crossSpace.enabled && value.crossSpace.maximumSpaces < 2) {
    errors.push('启用跨 Space 时，最多访问 Space 数至少为 2。')
  }
  return [...new Set(errors)]
}

function stableId(value: string, maximumLength: number) {
  return (
    value.length > 0 &&
    value.length <= maximumLength &&
    /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)
  )
}

function integerBetween(value: number, minimum: number, maximum: number) {
  return Number.isInteger(value) && value >= minimum && value <= maximum
}
