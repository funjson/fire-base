import type {
  AvailableChunker,
  AvailableParser,
  DocumentProcessingCapabilityCatalog,
  DocumentProcessingConfiguration,
  DocumentCleaningAction,
  SpaceChunkerConfiguration,
  SpaceDocumentCleaningConfiguration,
  SpaceParserSelection,
} from '../../lib/api'

/** 页面编辑中的完整草稿；创建或测试请求会整体提交，避免阶段配置不一致。 */
export type DocumentProcessingConfigDraft = {
  parserSelections: SpaceParserSelection[]
  cleaning: SpaceDocumentCleaningConfiguration
  chunker: SpaceChunkerConfiguration
}

export const STRUCTURAL_PROVIDER_ID = 'STRUCTURAL'
export const SEMANTIC_REFINEMENT_PROVIDER_ID = 'SEMANTIC_REFINEMENT'

/** 控制台当前支持编辑的内置双向语义细化参数。 */
export type SemanticRefinementProviderConfig = {
  embeddingProfileId: string
  contextSlices: number
  mergeSimilarityThreshold: number
  splitSimilarityThreshold: number
}

const cleaningActions = new Set<DocumentCleaningAction>([
  'KEEP',
  'REMOVE',
  'METADATA_ONLY',
])

/**
 * 把后端用于运维诊断的稳定能力码转换为用户可执行的中文说明。
 * 未知值原样展示，避免新增 Adapter 的真实原因被前端吞掉。
 */
export function capabilityUnavailableReason(
  reason: string | null | undefined,
) {
  if (!reason) return '当前部署未启用该能力。'
  const messages: Record<string, string> = {
    DOCLING_PARSER_DISABLED:
      '当前部署未启用 Docling Parser；需由运维启用 Docling 服务后才能选择。',
    DOCLING_DOCX_PARSER_DISABLED:
      'Docling DOCX Parser 尚未启用；当前仍可使用内置 DOCX Parser。',
    DOCLING_PARSER_NOT_INSTALLED:
      'Docling 已配置启用，但当前运行时没有安装可用的 Parser。',
    SEMANTIC_REFINEMENT_DISABLED:
      '当前部署未启用语义细化 Chunker；需由运维启用并配置 Embedding 服务。',
    TOKENIZER_EMBEDDING_PROFILE_MISMATCH:
      '该精确 Tokenizer 绑定的模型与当前 Embedding 配置不一致，不能安全选择。',
    HUGGINGFACE_TOKENIZER_NOT_INSTALLED:
      '本地 Hugging Face Tokenizer 已配置启用，但 tokenizer.json 或固定校验配置未成功加载。',
    HUGGINGFACE_TOKENIZER_DISABLED:
      '当前部署未启用本地 Hugging Face Tokenizer；目前只能使用预算估算 Counter。',
  }
  return messages[reason] || reason
}

/** 从服务端快照创建可独立编辑的草稿，避免修改查询缓存对象。 */
export function toDocumentProcessingConfigDraft(
  config: DocumentProcessingConfiguration,
): DocumentProcessingConfigDraft {
  return {
    parserSelections: config.parserSelections.map((selection) => ({ ...selection })),
    cleaning: { ...config.cleaning },
    chunker: {
      ...config.chunker,
      providerConfig: { ...config.chunker.providerConfig },
    },
  }
}

/** 配置字段均为稳定标量，序列化比较可以清楚判断页面是否发生修改。 */
export function sameDocumentProcessingConfigDraft(
  left: DocumentProcessingConfigDraft,
  right: DocumentProcessingConfigDraft,
) {
  return JSON.stringify(left) === JSON.stringify(right)
}

/** 在提交前复核部署能力和参数边界，服务端仍是最终校验边界。 */
export function validateDocumentProcessingConfigDraft(
  config: DocumentProcessingCapabilityCatalog,
  draft: DocumentProcessingConfigDraft,
) {
  const errors: string[] = []
  const mediaTypes = new Set<string>()
  for (const selection of draft.parserSelections) {
    const normalizedMediaType = selection.mediaType.toLowerCase()
    if (mediaTypes.has(normalizedMediaType)) {
      errors.push(`${selection.mediaType} 存在重复 Parser 映射。`)
    }
    mediaTypes.add(normalizedMediaType)
    const selectedParser = parsersForMediaType(
      config.availableParsers,
      selection.mediaType,
    ).find((parser) => parser.id === selection.parserId)
    if (!selectedParser?.available) {
      errors.push(
        selectedParser?.unavailableReason
          ? `${selection.mediaType} 的 Parser ${selection.parserId} 不可用：${capabilityUnavailableReason(selectedParser.unavailableReason)}`
          : `${selection.mediaType} 的 Parser ${selection.parserId} 在当前部署中不可用。`,
      )
    }
  }
  if (draft.parserSelections.length === 0) {
    errors.push('至少需要配置一个 Parser 映射。')
  }
  if (
    Object.values(draft.cleaning).some(
      (action) => !cleaningActions.has(action),
    )
  ) {
    errors.push('Cleaner 包含不受支持的处理方式。')
  }

  const chunker = draft.chunker
  const capability = config.availableChunkers.find(
    (item) => item.id === chunker.providerId,
  )
  if (!capability?.available) {
    errors.push(
      capability?.unavailableReason
        ? capabilityUnavailableReason(capability.unavailableReason)
        : '选择的 Chunker 在当前部署中不可用。',
    )
  }
  const missingRequirements = missingChunkerParserCapabilities(
    capability,
    draft.parserSelections,
    config.availableParsers,
  )
  if (missingRequirements.length > 0) {
    errors.push(
      `所选 Chunker 要求每个 Parser 都提供：${missingRequirements
        .map(parserCapabilityLabel)
        .join('、')}。`,
    )
  }
  const selectedTokenizer = config.availableTokenizers.find(
    (tokenizer) => tokenizer.id === chunker.tokenizerId,
  )
  if (!selectedTokenizer?.available) {
    errors.push(
      selectedTokenizer?.unavailableReason
        ? `Token Counter ${chunker.tokenizerId} 不可用：${capabilityUnavailableReason(selectedTokenizer.unavailableReason)}`
        : `Token Counter ${chunker.tokenizerId} 在当前部署中不可用。`,
    )
  }
  if (
    !Number.isInteger(chunker.minimumTokens) ||
    !Number.isInteger(chunker.targetTokens) ||
    !Number.isInteger(chunker.maximumTokens) ||
    chunker.minimumTokens < 1 ||
    chunker.minimumTokens > chunker.targetTokens ||
    chunker.targetTokens > chunker.maximumTokens ||
    chunker.maximumTokens > 65_536
  ) {
    errors.push('Token 预算必须满足 1 ≤ 最小值 ≤ 目标值 ≤ 最大值 ≤ 65536。')
  }
  if (
    !Number.isInteger(chunker.overlapTokens) ||
    chunker.overlapTokens < 0 ||
    chunker.overlapTokens >= chunker.minimumTokens
  ) {
    errors.push('Overlap 必须是不小于 0 且小于最小 Token 数的整数。')
  }
  if (isSemanticChunker(chunker.providerId)) {
    const semantic = semanticRefinementProviderConfig(chunker.providerConfig)
    if (!semantic) {
      errors.push('语义细化 Provider 配置字段不完整。')
    } else {
      if (
        !config.availableEmbeddingProfiles.some(
          (profile) => profile.id === semantic.embeddingProfileId,
        )
      ) {
        errors.push('选择的 Embedding Profile 在当前部署中不可用。')
      }
      if (
        !Number.isInteger(semantic.contextSlices) ||
        semantic.contextSlices < 0 ||
        semantic.contextSlices > 2
      ) {
        errors.push('语义边界上下文片段数必须是 0 到 2 之间的整数。')
      }
      if (
        !Number.isFinite(semantic.splitSimilarityThreshold) ||
        !Number.isFinite(semantic.mergeSimilarityThreshold) ||
        semantic.splitSimilarityThreshold < -1 ||
        semantic.mergeSimilarityThreshold > 1 ||
        semantic.splitSimilarityThreshold >= semantic.mergeSimilarityThreshold
      ) {
        errors.push('语义阈值必须满足 -1 ≤ 拆分阈值 < 合并阈值 ≤ 1。')
      }
    }
  } else if (
    chunker.providerId === STRUCTURAL_PROVIDER_ID &&
    Object.keys(chunker.providerConfig).length > 0
  ) {
    errors.push('结构切分 Provider 不接受专属配置。')
  }
  return [...new Set(errors)]
}

/** 返回真正声明支持该媒体类型的已安装 Parser。 */
export function parsersForMediaType(
  parsers: AvailableParser[],
  mediaType: string,
) {
  const normalized = mediaType.toLowerCase()
  return parsers.filter(
    (parser) => parser.canonicalMediaType.toLowerCase() === normalized,
  )
}

/**
 * 返回当前 Parser 映射不能统一满足的 Chunker 前置能力。
 * 服务端只校验 Space 创建时显式固化的映射，不会用后续部署默认值补齐；
 * 这里用于尽早阻止明显无效的页面组合。
 */
export function missingChunkerParserCapabilities(
  chunker: AvailableChunker | undefined,
  selections: SpaceParserSelection[],
  parsers: AvailableParser[],
) {
  if (!chunker || chunker.requiredParserCapabilities.length === 0) return []
  const missing = new Set<string>()
  for (const selection of selections) {
    const parser = parsersForMediaType(parsers, selection.mediaType).find(
      (candidate) => candidate.id === selection.parserId,
    )
    for (const requirement of chunker.requiredParserCapabilities) {
      if (!parser?.outputCapabilities.includes(requirement)) {
        missing.add(requirement)
      }
    }
  }
  return [...missing].sort()
}

export function isSemanticChunker(providerId: string) {
  return providerId === SEMANTIC_REFINEMENT_PROVIDER_ID
}

/** 严格读取内置语义配置，避免页面把任意动态 JSON 当作已支持字段。 */
export function semanticRefinementProviderConfig(
  value: Record<string, unknown>,
): SemanticRefinementProviderConfig | undefined {
  const keys = Object.keys(value).sort()
  const expected = [
    'contextSlices',
    'embeddingProfileId',
    'mergeSimilarityThreshold',
    'splitSimilarityThreshold',
  ]
  if (JSON.stringify(keys) !== JSON.stringify(expected)) return undefined
  if (
    typeof value.embeddingProfileId !== 'string' ||
    typeof value.contextSlices !== 'number' ||
    typeof value.mergeSimilarityThreshold !== 'number' ||
    typeof value.splitSimilarityThreshold !== 'number'
  ) {
    return undefined
  }
  return {
    embeddingProfileId: value.embeddingProfileId,
    contextSlices: value.contextSlices,
    mergeSimilarityThreshold: value.mergeSimilarityThreshold,
    splitSimilarityThreshold: value.splitSimilarityThreshold,
  }
}

/** 把后端稳定 Parser 能力码转换为控制台展示文本。 */
export function parserCapabilityLabel(capability: string) {
  const labels: Record<string, string> = {
    STANDARD_ELEMENTS: '标准元素',
    HIERARCHY: '标题层级',
    PAGE_NUMBER: '页码来源',
    BOUNDING_BOX: '页面坐标',
    FLAT_TABLE_TEXT: '扁平表格文本',
    TABLE_STRUCTURE: '表格结构',
    NATIVE_ARTIFACT: '原生解析产物',
  }
  return labels[capability] || capability
}
