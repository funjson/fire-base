const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly requestId: string | undefined,
    message: string,
  ) {
    super(message)
  }
}

export type Overview = {
  spaces: number
  activeDocuments: number
  chunks: number
  connectors: number
  pendingProjections: number
  deadProjections: number
  evaluationDatasets: number
  evaluationRuns: number
}

export type Space = {
  id: string
  name: string
  description: string
  status: string
  version: number
  documentCount: number
  createdAt: string
  updatedAt: string
}

export type SpaceGrant = {
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT' | 'TENANT'
  subjectId: string
  permission: 'READ' | 'WRITE' | 'ADMIN'
}

export type RetrievalChannelId = 'KEYWORD' | 'VECTOR' | 'GRAPH' | 'PAGE'

export type RetrievalChainNodeId =
  | 'GAP_QUERY'
  | 'PRF'
  | 'RELAX_CONSTRAINTS'
  | 'NARROW_CONSTRAINTS'
  | 'STEP_BACK'
  | 'HYDE'
  | 'NEXT_SPACE'

export type RetrievalBranchConfiguration = {
  enabled: boolean
  topK: number
  rrfWeight: number
}

/** Space 中完整物化的检索语义配置；不包含任何索引参数。 */
export type RetrievalConfiguration = {
  firstRound: {
    termExpansionEnabled: boolean
    terminologyResourceId: string
    maximumExpansionTerms: number
  }
  branches: {
    maximumVariantsPerAttempt: number
    maximumRetrievalBranches: number
    rrfConstant: number
    channels: Record<RetrievalChannelId, RetrievalBranchConfiguration>
  }
  reranker: {
    enabled: boolean
    providerId: string
    modelId: string
    candidateLimit: number
    outputTopK: number
  }
  coverage: {
    enabled: boolean
    providerId: string
    modelId: string
    promptVersion: string
    memoryLimit: number
    sufficiencyThreshold: number
  }
  maximumRetrievalAttempts: number
  chainNodeEnables: Record<RetrievalChainNodeId, boolean>
  crossSpace: {
    enabled: boolean
    maximumSpaces: number
  }
}

/**
 * 单次请求的强类型局部覆盖；未提供的字段由后端针对每个 Space 分别继承当前修订。
 * 控制台不得把它物化成完整 RetrievalConfiguration 后发送。
 */
export type RetrievalConfigurationOverride = {
  firstRound?: Partial<RetrievalConfiguration['firstRound']>
  branches?: {
    maximumVariantsPerAttempt?: number
    maximumRetrievalBranches?: number
    rrfConstant?: number
    channels?: Partial<
      Record<RetrievalChannelId, Partial<RetrievalBranchConfiguration>>
    >
  }
  reranker?: Partial<RetrievalConfiguration['reranker']>
  coverage?: Partial<RetrievalConfiguration['coverage']>
  maximumRetrievalAttempts?: number
  chainNodeEnables?: Partial<Record<RetrievalChainNodeId, boolean>>
  crossSpace?: Partial<RetrievalConfiguration['crossSpace']>
}

/** Space 当前使用的不可变检索配置修订。 */
export type SpaceRetrievalConfiguration = {
  spaceId: string
  revision: number
  configuration: RetrievalConfiguration
  fingerprint: string
  createdBy: string
  createdAt: string
}

export type UpdateSpaceRetrievalConfigurationRequest = {
  expectedRevision: number
  configuration: RetrievalConfiguration
}

/** 空间选择的 Parser，媒体类型由服务端能力目录定义。 */
export type SpaceParserSelection = {
  mediaType: string
  parserId: string
}

/** Provider 专属配置；服务端按所选 Provider 校验并规范化字段。 */
export type ChunkerProviderConfig = Record<string, unknown>

/** 空间级 Chunker 参数；长度均使用所选 Token Counter 的计量结果。 */
export type SpaceChunkerConfiguration = {
  providerId: string
  tokenizerId: string
  minimumTokens: number
  targetTokens: number
  maximumTokens: number
  overlapTokens: number
  providerConfig: ChunkerProviderConfig
}

/** Cleaner 对 Parser 已识别文档角色的确定性处理方式。 */
export type DocumentCleaningAction = 'KEEP' | 'REMOVE' | 'METADATA_ONLY'

/** 空间级内容治理配置；不会对未识别为对应角色的正文做模糊删除。 */
export type SpaceDocumentCleaningConfiguration = {
  header: DocumentCleaningAction
  footer: DocumentCleaningAction
  pageNumber: DocumentCleaningAction
  watermark: DocumentCleaningAction
  frontMatter: DocumentCleaningAction
}

/** Parser 能力目录；只有 available=true 的条目可供空间选择。 */
export type AvailableParser = {
  id: string
  version: string
  canonicalMediaType: string
  defaultSelection: boolean
  available: boolean
  unavailableReason: string | null
  mediaTypes: string[]
  extensions: string[]
  outputCapabilities: string[]
}

/** 当前部署中可供空间选择的 Chunker。 */
export type AvailableChunker = {
  id: string
  version: string
  available: boolean
  unavailableReason: string | null
  requiredParserCapabilities: string[]
  defaultProviderConfig: ChunkerProviderConfig
}

/** 当前部署可用于 Chunk 预算计算的 Token Counter。 */
export type AvailableTokenizer = {
  id: string
  version: string
  description: string
  exactModelTokens: boolean
  modelProfileId: string | null
  available: boolean
  unavailableReason: string | null
}

/** 当前部署允许语义细化使用的 Embedding 契约，不包含凭据。 */
export type AvailableEmbeddingProfile = {
  id: string
  description: string
}

/** 会改变解析、切分和索引结果的完整文档处理配置。 */
export type DocumentProcessingConfiguration = {
  parserSelections: SpaceParserSelection[]
  cleaning: SpaceDocumentCleaningConfiguration
  chunker: SpaceChunkerConfiguration
}

/**
 * Space 创建时由服务端根据实际部署生成的处理合同。
 *
 * 用户请求不能提交该对象；它只用于审计固定的 Pipeline、Parser、Cleaner、
 * Chunker 与 Tokenizer 实现，避免把稳定 ID 误认为稳定实现版本。
 */
export type DocumentProcessingContract = {
  pipelineContract: string
  normalizerSchemaContract: string
  parserContracts: Record<string, string>
  cleanerContract: string
  chunkerContract: string
  fingerprint: string
}

/** 当前部署实际安装的 Parser、Chunker、Tokenizer 与模型能力目录。 */
export type DocumentProcessingCapabilityCatalog = {
  availableParsers: AvailableParser[]
  availableChunkers: AvailableChunker[]
  availableTokenizers: AvailableTokenizer[]
  availableEmbeddingProfiles: AvailableEmbeddingProfile[]
}

/** 创建 Space 前使用的默认配置和能力目录。 */
export type DocumentProcessingCapabilities =
  DocumentProcessingCapabilityCatalog & {
  defaultConfig: DocumentProcessingConfiguration
}

/** 创建后只读的 Space 文档处理配置以及当前能力目录。 */
export type SpaceDocumentProcessingConfig =
  DocumentProcessingConfiguration &
  DocumentProcessingCapabilityCatalog & {
  spaceId: string
  version: number
  updatedAt: string
  updatedBy: string
  processingContract: DocumentProcessingContract
  /** 服务端实时校验当前部署是否仍满足 Space 创建时固化的合同。 */
  runtimeContractMatched: boolean
}

/** Space 创建请求必须原子携带创建后不可修改的处理配置。 */
export type CreateSpaceRequest = {
  spaceId: string
  name: string
  description: string
  documentProcessingConfig: DocumentProcessingConfiguration
}

/**
 * 仅用于 TEST_ONLY 的本次测试配置。
 *
 * 它只覆盖单次测试请求并固化到运行快照，不会写回 Space 配置，也不会被正式摄取使用。
 */
export type TestDocumentProcessingConfig = DocumentProcessingConfiguration

/** 数据抽取测试的稳定运行状态。 */
export type ExtractionRunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

/** 单文件当前所处的抽取阶段。 */
export type ExtractionItemStage =
  | 'STORED'
  | 'PARSE'
  | 'CLEAN'
  | 'CHUNK'
  | 'PUBLISHING'
  | 'COMPLETED'

export type ExtractionItemStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'SKIPPED_DUPLICATE'
  | 'FAILED'
  | 'CANCELLED'

export type ExtractionGateStatus =
  | 'NOT_EVALUATED'
  | 'PASSED'
  | 'FAILED'
  | 'ERROR'

/** Clean 阶段的元素去向与稳定原因码，不含正文。 */
export type ExtractionCleaningDiagnostics = {
  indexableElements: number
  metadataOnlyElements: number
  reasonCodeCounts: Record<string, number>
}

/** Chunk 阶段固定的 16 项边界决策与最终大小分布。 */
export type ExtractionChunkingDiagnostics = {
  structuralHardBreaks: number
  baselineSoftBreaks: number
  semanticCandidateBoundaries: number
  semanticCutSuggestions: number
  semanticJoinSuggestions: number
  semanticNeutralSuggestions: number
  semanticCutsAdded: number
  semanticJoinsApplied: number
  semanticNoOps: number
  tokenLimitBreaks: number
  spanLimitBreaks: number
  rejectedSemanticJoins: number
  finalChunkCount: number
  minimumChunkUnits: number
  averageChunkUnits: number
  maximumChunkUnits: number
}

/** 逐阶段时长、产物数量和非敏感决策指标；未成功的文件保持 null。 */
export type ExtractionItemDiagnostics = {
  parserId: string
  processorVersion: string
  elementCount: number
  chunkCount: number
  parseDurationMs: number
  cleanDurationMs: number
  chunkDurationMs: number
  cleaning: ExtractionCleaningDiagnostics
  chunking: ExtractionChunkingDiagnostics
}

export type ExtractionConfigSnapshot = {
  processingContract: DocumentProcessingContract
  normalizerContract: string
  parserSelections: Record<string, string>
  cleaning: {
    header: string
    footer: string
    pageNumber: string
    watermark: string
    frontMatter: string
  }
  chunker: {
    providerId: string
    tokenizerId: string
    minimumTokens: number
    targetTokens: number
    maximumTokens: number
    overlapTokens: number
    providerConfigurationJson: string
  }
  fingerprint: string
}

export type ExtractionArtifactRange = {
  artifactId: string
  startOffset: number
  endOffset: number
  pageNumber: number | null
}

export type ExtractionPreview = {
  artifactId: string
  artifactLength: number
  totalElementCount: number
  totalChunkCount: number
  truncated: boolean
  elements: Array<{
    id: string
    parentId: string | null
    type: string
    ordinal: number
    sectionPath: string[]
    role: string | null
    cleaningAction: string
    cleaningReasonCode: string
    sourceRange: ExtractionArtifactRange | null
    contentLength: number
    text: string
    textTruncated: boolean
  }>
  chunks: Array<{
    id: string
    ordinal: number
    sectionPath: string[]
    contentLength: number
    text: string
    textTruncated: boolean
    sourceSpans: Array<{
      elementId: string
      startOffset: number
      endOffset: number
      pageNumber: number | null
      artifactRange: ExtractionArtifactRange | null
    }>
  }>
}

export type ExtractionGateReport = {
  status: Exclude<ExtractionGateStatus, 'NOT_EVALUATED'>
  datasetId: string
  datasetVersion: string
  configFingerprint: string
  evaluatedAt: string
  cases: Array<{
    caseId: string
    sourceSha256: string
    passed: boolean
    parseSuccessRate: number
    tokenOverflowCount: number
    invalidSourceSpanCount: number
    sourceAccountingRate: number
    silentTruncationCount: number
    findings: Array<{ code: string; message: string }>
  }>
  errorCode: string | null
}

/** 一个源文件的抽取结果；正式摄取额外返回创建任务时固化的业务身份。 */
export type ExtractionRunItem = {
  id: string
  sourceAssetId: string
  fileName: string
  mediaType: string
  contentLength: number
  checksumSha256: string
  externalId: string | null
  title: string | null
  authority: number | null
  status: ExtractionItemStatus
  stage: ExtractionItemStage
  errorCode: string | null
  diagnostics: ExtractionItemDiagnostics | null
  preview: ExtractionPreview | null
  documentId: string | null
  revisionId: string | null
}

/** Space 维度的抽取运行摘要。 */
export type ExtractionRunSummary = {
  id: string
  spaceId: string
  mode: 'TEST_ONLY' | 'INGEST'
  status: ExtractionRunStatus
  configVersion: number
  configFingerprint: string
  datasetId: string | null
  baselineRunId: string | null
  gateStatus: ExtractionGateStatus
  totalItems: number
  succeededItems: number
  skippedDuplicateItems: number
  failedItems: number
  errorCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  heartbeatAt: string | null
}

/** 抽取运行详情包含所有逐文件结果。 */
export type ExtractionRunDetail = ExtractionRunSummary & {
  configSnapshot: ExtractionConfigSnapshot
  gateReport: ExtractionGateReport | null
  items: ExtractionRunItem[]
}

/** 正式摄取时与 Multipart 文件顺序一一对应的发布属性。 */
export type IngestionManifestItem = {
  externalId: string
  title: string
  authority: number
}

export type ExtractionDataset = {
  id: string
  caseCount: number
  cases: Array<{
    id: string
    description: string
    sourceName: string
    sourceSha256: string
    mediaType: string
    license: string
    reviewedBy: string
    reviewedAt: string
  }>
}

export type DocumentView = {
  id: string
  spaceId: string
  title: string
  sourceType: string
  sourceUri: string
  status: string
  authority: number
  version: number
  activeRevisionId: string | null
  chunkCount: number
  keywordStatus: string
  vectorStatus: string
  graphStatus: string
  originalFileName: string | null
  sourceMediaType: string | null
  sourceContentLength: number | null
  updatedAt: string
}

export type DocumentRevisionView = {
  revisionId: string
  revisionNumber: number
  contentHash: string
  mediaType: string
  language: string
  parserVersion: string
  createdAt: string
  active: boolean
  chunkCount: number
}

export type Page<T> = {
  items: T[]
  limit: number
  offset: number
  total: number
}

export type MarkdownDocumentInput = {
  spaceId: string
  externalId: string
  title: string
  sourceUri: string
  language: string
  authority: number
  content: string
  metadata: Record<string, string>
}

export type MarkdownDocumentResult = {
  documentId: string
  revisionId: string
  changed: boolean
  elementCount: number
  chunkCount: number
  vectorStatus: string
  warnings: string[]
}

export type SourceObjectView = {
  documentId: string
  revisionId: string
  documentStatus: string
  originalFileName: string
  mediaType: string
  contentLength: number
  checksumSha256: string
  storedAt: string
  previewable: boolean
}

export type DocumentLifecycleResult = {
  documentId: string
  spaceId: string
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETED'
  version: number
  updatedAt: string
  changed: boolean
}

export type Chunk = {
  id: string
  ordinal: number
  sectionPath: string[]
  content: string
  contentHash: string
}

export type WikiPageStatus =
  | 'DRAFT'
  | 'IN_REVIEW'
  | 'PUBLISHED'
  | 'ARCHIVED'

export type WikiSourceSelection = {
  documentId: string
  revisionId: string
  chunkId: string
}

export type WikiSourceReference = WikiSourceSelection & {
  sectionPath: string[]
  contentHash: string
  authority: number
}

export type WikiRevision = {
  id: string
  revisionNumber: number
  summary: string
  markdown: string
  sources: WikiSourceReference[]
  contentHash: string
  compilerVersion: string
  generatedBy: string
  createdAt: string
}

export type WikiPageSummary = {
  id: string
  spaceId: string
  slug: string
  title: string
  status: WikiPageStatus
  latestRevisionId: string
  activeRevisionId: string | null
  version: number
  sourceCount: number
  updatedAt: string
}

export type WikiPageDetail = {
  page: WikiPageSummary
  latestRevision: WikiRevision
  activeRevision: WikiRevision | null
  unpublishedChanges: boolean
}

export type Connector = {
  id: string
  spaceId: string
  type: string
  displayName: string
  status: string
  version: number
  lastRunId: string | null
  lastRunStatus: string | null
  lastRunAt: string | null
  updatedAt: string
}

export type ConnectorRun = {
  runId: string
  connectorId: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  recordsSeen: number
  recordsChanged: number
  recordsDeleted: number
  errorCode: string | null
  startedAt: string
  completedAt: string | null
}

export type ProjectionType = 'VECTOR' | 'KEYWORD' | 'GRAPH'

export type ProjectionJobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'RETRY'
  | 'SUCCEEDED'
  | 'DEAD'

export type ProjectionRebuild = {
  jobs: number
  projectionTypes: ProjectionType[]
}

export type ProjectionJob = {
  id: string
  projectionType: ProjectionType
  status: ProjectionJobStatus
  attemptCount: number
  lastErrorCode: string | null
  availableAt: string
  updatedAt: string
}

export type ProjectionRetry = {
  requeued: boolean
}

export type TraceStep = {
  ordinal: number
  name: string
  durationMs: number
  inputCount: number
  outputCount: number
  status: string
}

export type Trace = {
  id: string
  requestId: string
  principalId: string
  totalDurationMs: number
  resultCount: number
  createdAt: string
  steps: TraceStep[]
}

export type AuditEvent = {
  id: string
  tenantId: string
  principalId: string
  requestId: string
  httpMethod: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  routePattern: string
  action: string
  responseStatus: number
  outcome: 'SUCCEEDED' | 'FAILED'
  durationMs: number
  createdAt: string
}

export type KnowledgeSourceSpan = {
  elementId: string
  startOffset: number
  endOffset: number
  pageNumber: number | null
}

export type Evidence = {
  id: string
  content: string
  relevance: number
  authority: number
  channels: string[]
  citation: {
    documentId: string
    revisionId: string
    chunkId: string
    title: string
    sectionPath: string[]
    sourceUri: string
    sourceSpans: KnowledgeSourceSpan[]
  }
}

export type EvidenceBundle = {
  requestId: string
  traceId: string
  tenantId: string
  evidences: Evidence[]
  sufficient: boolean
  warnings: string[]
  generatedAt: string
  /** 以下字段由增强检索运行时返回；旧后端缺失时控制台明确显示“尚未返回”。 */
  configurationFingerprints?: string[]
  visitedSpaceIds?: string[]
  terminalStatus?: string
  stopReason?: string
  degraded?: boolean
  testMode?: boolean
}

/** Coverage Judge 在单次请求中需要验证的一项证据要求。 */
export type EvidenceRequirementInput = {
  id: string
  description: string
}

/** 调用方显式授权 Chain 改变的过滤条件；普通 filters 始终为硬约束。 */
export type RetrievalConstraintInput = {
  relaxableFilters: Record<string, string>
  narrowingFilters: Record<string, string>
}

export type KnowledgeQueryInput = {
  query: string
  spaceIds: string[]
  topK: number
  filters: Record<string, string>
  constraints?: RetrievalConstraintInput
  retrievalTarget?: string
  evidenceRequirements?: EvidenceRequirementInput[]
  configurationOverride?: RetrievalConfigurationOverride
  testMode?: boolean
}

/** 检索观测用途是指标隔离维度，测试广场事实不得进入 ONLINE 聚合。 */
export type RetrievalObservationPurpose =
  | 'ONLINE'
  | 'EVALUATION'
  | 'TEST_PLAZA'
  | 'SHADOW'
  | 'REPLAY'

/** 一次 Space visit 实际采用的物化配置修订与请求级有效指纹。 */
export type VisitedRetrievalConfiguration = {
  visitIndex: number
  spaceId: string
  sourceRevision: number
  fingerprint: string
}

/** 供测试广场展示的安全阶段事实，不含查询、候选正文或模型原始响应。 */
export type RetrievalObservationEvent = {
  sequence: number
  visitIndex: number
  attemptIndex: number
  stage: string
  status: string
  reasonCode: string
  configFingerprint: string
  startedAt: string
  completedAt: string
  durationMillis: number
  inputCount: number
  outputCount: number
}

/** Eval 模块从原始事件投影出的运行指标事实。 */
export type RetrievalObservationMetric = {
  metricKey: string
  aggregation: string
  value: number
  dimensions: Record<string, string | null>
  observedAt: string
}

/** 按请求读取的一次检索执行观测视图。 */
export type RetrievalObservationReport = {
  executionId: string
  requestId: string
  purpose: RetrievalObservationPurpose
  completeness: 'COMPLETE' | 'INCOMPLETE'
  incompleteReasons: string[]
  visitedConfigurations: VisitedRetrievalConfiguration[]
  eventCount: number
  events: RetrievalObservationEvent[]
  metrics: RetrievalObservationMetric[]
}

/** 线上检索指标使用统一时间和版本过滤；用途由服务端固定为 ONLINE。 */
export type RetrievalObservabilityFilter = {
  from: string
  to: string
  spaceId?: string
  configFingerprint?: string
  dataIndexVersion?: string
}

/** 比例同时携带分子和分母，避免控制台把空样本误判成 0%。 */
export type RetrievalObservabilityRate = {
  numerator: number
  denominator: number
  value: number | null
}

/** 延迟分布携带自身样本数；无样本时所有分位值为 null。 */
export type RetrievalObservabilityPercentiles = {
  sampleCount: number
  p50: number | null
  p95: number | null
  p99: number | null
}

/** 固定时间桶内的在线运行与代理质量事实。 */
export type OnlineOverviewPoint = {
  bucketStart: string
  requestCount: number
  technicalSuccessRate: RetrievalObservabilityRate
  degradedRate: RetrievalObservabilityRate
  latencyMillis: RetrievalObservabilityPercentiles
  terminalObservationRate: RetrievalObservabilityRate
  firstCoverageSufficientRate: RetrievalObservabilityRate
  finalCoverageSufficientRate: RetrievalObservabilityRate
  observationCompleteRate: RetrievalObservabilityRate
}

/** 一个受控配置或索引版本覆盖的去重执行数量。 */
export type RetrievalDimensionCount = {
  value: string
  count: number
}

/** 在线总览只描述运行事实和代理质量，不替代 Gold Dataset 的真实能力指标。 */
export type OnlineRetrievalOverview = {
  from: string
  to: string
  granularity: string
  requestCount: number
  configFingerprints: RetrievalDimensionCount[]
  dataIndexVersions: RetrievalDimensionCount[]
  technicalSuccessRate: RetrievalObservabilityRate
  degradedRate: RetrievalObservabilityRate
  endToEndLatencyMillis: RetrievalObservabilityPercentiles
  terminalObservationRate: RetrievalObservabilityRate
  firstCoverageSufficientRate: RetrievalObservabilityRate
  finalCoverageSufficientRate: RetrievalObservabilityRate
  coverageRecoveryRate: RetrievalObservabilityRate
  budgetExhaustedRate: RetrievalObservabilityRate
  observationCompleteRate: RetrievalObservabilityRate
  series: OnlineOverviewPoint[]
}

/** 通用检索阶段的吞吐、技术成功和输入输出规模。 */
export type RetrievalStageDiagnostic = {
  stage: string
  executionCount: number
  eventCount: number
  metricDefinitionVersion: number | null
  successRate: RetrievalObservabilityRate
  latencyMillis: RetrievalObservabilityPercentiles
  averageInputCount: number | null
  averageOutputCount: number | null
}

/** 按召回通道、策略、组件和索引版本隔离的分支汇总。 */
export type RetrievalBranchDiagnostic = {
  strategy: string
  channel: string
  componentModel: string
  dataIndexVersion: string
  executionCount: number
  eventCount: number
  successRate: RetrievalObservabilityRate
  emptyRate: RetrievalObservabilityRate
  averageCandidateCount: number | null
  latencyMillis: RetrievalObservabilityPercentiles
}

/** RRF 融合阶段的去重与候选规模汇总。 */
export type RetrievalFusionDiagnostic = {
  executionCount: number
  duplicateRate: RetrievalObservabilityRate
  averageInputCandidateCount: number | null
  averageUniqueCandidateCount: number | null
  latencyMillis: RetrievalObservabilityPercentiles
}

/** Reranker 执行、模型回退和候选规模汇总。 */
export type RetrievalRerankDiagnostic = {
  executionCount: number
  executedRate: RetrievalObservabilityRate
  fallbackRate: RetrievalObservabilityRate
  averageInputCandidateCount: number | null
  averageOutputCandidateCount: number | null
  modelRequestCount: number
  latencyMillis: RetrievalObservabilityPercentiles
}

/** Coverage Judge 的在线代理质量事实。 */
export type RetrievalCoverageDiagnostic = {
  executionCount: number
  checkCount: number
  measuredCount: number
  sufficientRate: RetrievalObservabilityRate
  averageScore: number | null
  averageRetainedCandidateCount: number | null
  modelRequestCount: number
  latencyMillis: RetrievalObservabilityPercentiles
}

/** 单个优化 Chain 节点的代理增益和运行成本。 */
export type RetrievalChainDiagnostic = {
  node: string
  strategy: string
  executionCount: number
  eventCount: number
  positiveGainRate: RetrievalObservabilityRate
  averageCoverageDelta: number | null
  modelRequestCount: number
  latencyMillis: RetrievalObservabilityPercentiles
}

/** 在线检索各层聚合诊断结果。 */
export type RetrievalStageDiagnostics = {
  from: string
  to: string
  stageRows: RetrievalStageDiagnostic[]
  branchRows: RetrievalBranchDiagnostic[]
  fusion: RetrievalFusionDiagnostic
  rerank: RetrievalRerankDiagnostic
  coverage: RetrievalCoverageDiagnostic
  chainRows: RetrievalChainDiagnostic[]
}

/** 单次 ONLINE 执行的安全摘要，不含查询和候选正文。 */
export type RetrievalExecutionItem = {
  requestId: string
  executionId: string
  startedAt: string
  lastObservedAt: string
  completedAt: string | null
  durationMillis: number | null
  completeness: string
  technicalStatus: string | null
  terminalStatus: string | null
  stopReason: string | null
  degraded: boolean | null
  attemptCount: number | null
  resultCount: number | null
  eventCount: number
  spaceIds: string[]
  configFingerprints: string[]
}

/** 在线执行记录分页结果。 */
export type RetrievalExecutionPage = {
  page: number
  size: number
  totalItems: number
  totalPages: number
  items: RetrievalExecutionItem[]
}

/** 在线执行记录在公共过滤之外允许追加的分页和终态过滤。 */
export type RetrievalExecutionFilter = RetrievalObservabilityFilter & {
  page?: number
  size?: number
  terminalStatus?: string
  stopReason?: string
}

export type GraphNode = {
  id: string
  type: string
  name: string
}

export type GraphEdge = {
  relationId: string
  source: GraphNode
  target: GraphNode
  type: string
  depth: number
  score: number
  provenance: {
    documentId: string
    revisionId: string
    chunkId: string
    documentTitle: string
    sourceUri: string
    excerpt: string
    confidence: number
  }
}

export type GraphSearchResult = {
  tenantId: string
  edges: GraphEdge[]
}

export type Dataset = {
  id: string
  name: string
  description: string
  version: number
  status: string
  caseCount: number
  runCount: number
  createdAt: string
}

export type EvaluationCase = {
  id: string
  datasetId: string
  query: string
  spaceIds: string[]
  expectedDocuments: string[]
  expectedChunks: string[]
  topK: number
  labels: Record<string, string>
  createdAt: string
}

export type EvaluationCaseInput = {
  query: string
  spaceIds: string[]
  expectedDocuments: string[]
  expectedChunks: string[]
  topK: number
  labels: Record<string, string>
}

export type CaseResult = {
  caseId: string
  traceId: string | null
  status: string
  hit: boolean
  recallAtK: number
  reciprocalRank: number
  ndcgAtK: number
  resultCount: number
  durationMs: number
  errorCode: string | null
}

export type EvaluationRun = {
  id: string
  datasetId: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  caseCount: number
  failedCaseCount: number
  configuration: Record<string, unknown>
  metrics: Record<string, unknown>
  requestedBy: string
  errorCode: string | null
  startedAt: string
  completedAt: string | null
  results: CaseResult[]
}

export type EvaluationComparisonInput = {
  baselineRunId: string
  candidateRunId: string
  minimumHitRate: number | null
  minimumRecallAtK: number | null
  minimumMrr: number | null
  minimumNdcgAtK: number | null
  maximumRegression: number
}

export type EvaluationGateViolation = {
  metric: string
  rule: 'MINIMUM' | 'MAXIMUM_REGRESSION'
  expected: number
  actual: number
}

export type EvaluationRunComparison = {
  datasetId: string
  baselineRunId: string
  candidateRunId: string
  baseline: Record<string, number>
  candidate: Record<string, number>
  deltas: Record<string, number>
  maximumRegression: number
  passed: boolean
  violations: EvaluationGateViolation[]
}

export function createApi(getToken: () => string | undefined) {
  async function request<T>(
    path: string,
    init: RequestInit = {},
  ): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('X-Request-Id', crypto.randomUUID())
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as {
        code?: string
        message?: string
        requestId?: string
      }
      throw new ApiError(
        response.status,
        body.code ?? 'REQUEST_FAILED',
        body.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
        body.message ?? `请求失败（HTTP ${response.status}）`,
      )
    }
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  async function requestBlob(path: string): Promise<Blob> {
    const headers = new Headers()
    headers.set('X-Request-Id', crypto.randomUUID())
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(`${baseUrl}${path}`, { headers })
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as {
        code?: string
        message?: string
        requestId?: string
      }
      throw new ApiError(
        response.status,
        body.code ?? 'REQUEST_FAILED',
        body.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
        body.message ?? `请求失败（HTTP ${response.status}）`,
      )
    }
    return response.blob()
  }

  return {
    overview: () => request<Overview>('/api/v1/admin/overview'),
    spaces: () => request<Space[]>('/api/v1/admin/spaces'),
    accessibleSpaces: () => request<Space[]>('/api/v1/spaces/accessible'),
    documentProcessingCapabilities: () =>
      request<DocumentProcessingCapabilities>(
        '/api/v1/document-processing-capabilities',
      ),
    createSpace: (body: CreateSpaceRequest) =>
      request<void>('/api/v1/spaces', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    spaceGrants: (spaceId: string) =>
      request<SpaceGrant[]>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/acl`,
      ),
    grantSpace: (spaceId: string, body: SpaceGrant) =>
      request<void>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/acl`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    revokeSpace: (spaceId: string, body: SpaceGrant) =>
      request<void>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/acl`, {
        method: 'DELETE',
        body: JSON.stringify(body),
      }),
    spaceDocumentProcessingConfig: (spaceId: string) =>
      request<SpaceDocumentProcessingConfig>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`,
      ),
    spaceRetrievalConfiguration: (spaceId: string) =>
      request<SpaceRetrievalConfiguration>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/retrieval-configuration`,
      ),
    updateSpaceRetrievalConfiguration: (
      spaceId: string,
      body: UpdateSpaceRetrievalConfigurationRequest,
    ) =>
      request<SpaceRetrievalConfiguration>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/retrieval-configuration`,
        { method: 'PUT', body: JSON.stringify(body) },
      ),
    extractionRuns: (spaceId: string) =>
      request<ExtractionRunSummary[]>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs`,
      ),
    extractionRun: (spaceId: string, runId: string) =>
      request<ExtractionRunDetail>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs/${encodeURIComponent(runId)}`,
      ),
    startExtractionRun: (
      spaceId: string,
      files: File[],
      language?: string,
      datasetId?: string,
      baselineRunId?: string,
      testConfig?: TestDocumentProcessingConfig,
    ) => {
      const body = new FormData()
      files.forEach((file) => body.append('files', file))
      if (language?.trim()) body.set('language', language.trim())
      if (datasetId?.trim()) body.set('datasetId', datasetId.trim())
      if (baselineRunId?.trim()) body.set('baselineRunId', baselineRunId.trim())
      if (testConfig) {
        body.append(
          'testConfig',
          new Blob([JSON.stringify(testConfig)], {
            type: 'application/json',
          }),
        )
      }
      return request<ExtractionRunDetail>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs`,
        { method: 'POST', body },
      )
    },
    startIngestionRun: (
      spaceId: string,
      files: File[],
      manifestItems: IngestionManifestItem[],
      language?: string,
    ) => {
      const body = new FormData()
      files.forEach((file) => body.append('files', file))
      body.append(
        'manifest',
        new Blob([JSON.stringify({ items: manifestItems })], {
          type: 'application/json',
        }),
      )
      if (language?.trim()) body.set('language', language.trim())
      return request<ExtractionRunDetail>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/ingestion-runs`,
        { method: 'POST', body },
      )
    },
    extractionDatasets: (spaceId: string) =>
      request<ExtractionDataset[]>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-datasets`,
      ),
    startExtractionDatasetRun: (
      spaceId: string,
      datasetId: string,
      language: string,
      baselineRunId?: string,
      testConfig?: TestDocumentProcessingConfig,
    ) =>
      request<ExtractionRunDetail>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-datasets/${encodeURIComponent(datasetId)}/runs`,
        {
          method: 'POST',
          body: JSON.stringify({
            language,
            baselineRunId: baselineRunId || null,
            testConfig,
          }),
        },
      ),
    cancelExtractionRun: (spaceId: string, runId: string) =>
      request<ExtractionRunDetail>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs/${encodeURIComponent(runId)}/cancel`,
        { method: 'POST' },
      ),
    extractionRunSource: (spaceId: string, runId: string, itemId: string) =>
      requestBlob(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs/${encodeURIComponent(runId)}/items/${encodeURIComponent(itemId)}/source`,
      ),
    rebuildSpaceProjections: (spaceId: string) =>
      request<ProjectionRebuild>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/projections/rebuild`,
        { method: 'POST' },
      ),
    documents: ({
      spaceId,
      status,
      title,
      source,
      keywordStatus,
      vectorStatus,
      minimumChunkCount,
      maximumChunkCount,
      updatedFrom,
      updatedTo,
      limit = 25,
      offset = 0,
    }: {
      spaceId?: string
      status?: string
      title?: string
      source?: string
      keywordStatus?: string
      vectorStatus?: string
      minimumChunkCount?: number
      maximumChunkCount?: number
      updatedFrom?: string
      updatedTo?: string
      limit?: number
      offset?: number
    } = {}) => {
      const parameters = new URLSearchParams({
        limit: String(limit),
        offset: String(offset),
      })
      if (spaceId) parameters.set('spaceId', spaceId)
      if (status) parameters.set('status', status)
      if (title) parameters.set('title', title)
      if (source) parameters.set('source', source)
      if (keywordStatus) parameters.set('keywordStatus', keywordStatus)
      if (vectorStatus) parameters.set('vectorStatus', vectorStatus)
      if (minimumChunkCount !== undefined) {
        parameters.set('minimumChunkCount', String(minimumChunkCount))
      }
      if (maximumChunkCount !== undefined) {
        parameters.set('maximumChunkCount', String(maximumChunkCount))
      }
      if (updatedFrom) parameters.set('updatedFrom', updatedFrom)
      if (updatedTo) parameters.set('updatedTo', updatedTo)
      return request<Page<DocumentView>>(
        `/api/v1/admin/documents?${parameters.toString()}`,
      )
    },
    document: (documentId: string) =>
      request<DocumentView>(
        `/api/v1/admin/documents/${encodeURIComponent(documentId)}`,
      ),
    chunks: (documentId: string) =>
      request<Chunk[]>(
        `/api/v1/admin/documents/${encodeURIComponent(documentId)}/chunks`,
      ),
    documentRevisions: (documentId: string) =>
      request<DocumentRevisionView[]>(
        `/api/v1/admin/documents/${encodeURIComponent(documentId)}/revisions`,
      ),
    revisionChunks: (documentId: string, revisionId: string) =>
      request<Chunk[]>(
        `/api/v1/admin/documents/${encodeURIComponent(documentId)}/revisions/${encodeURIComponent(revisionId)}/chunks`,
      ),
    wikiPages: (spaceId?: string) => {
      const parameters = new URLSearchParams()
      if (spaceId) parameters.set('spaceId', spaceId)
      const query = parameters.size ? `?${parameters.toString()}` : ''
      return request<WikiPageSummary[]>(`/api/v1/admin/wiki/pages${query}`)
    },
    wikiPage: (pageId: string) =>
      request<WikiPageDetail>(
        `/api/v1/admin/wiki/pages/${encodeURIComponent(pageId)}`,
      ),
    compileWikiPage: (body: {
      spaceId: string
      slug: string
      title: string
      sources: WikiSourceSelection[]
    }) =>
      request<WikiPageDetail>('/api/v1/admin/wiki/pages', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    transitionWikiPage: (
      pageId: string,
      action: 'submit-review' | 'reject' | 'publish' | 'archive',
      expectedVersion: number,
    ) =>
      request<WikiPageDetail>(
        `/api/v1/admin/wiki/pages/${encodeURIComponent(pageId)}/${action}`,
        {
          method: 'POST',
          body: JSON.stringify({ expectedVersion }),
        },
      ),
    projectionJobs: (documentId: string) =>
      request<ProjectionJob[]>(
        `/api/v1/documents/${encodeURIComponent(documentId)}/projections`,
      ),
    retryProjection: (documentId: string, projectionType: ProjectionType) =>
      request<ProjectionRetry>(
        `/api/v1/documents/${encodeURIComponent(documentId)}/projections/${encodeURIComponent(projectionType)}/retry`,
        { method: 'POST' },
      ),
    ingestMarkdown: (body: MarkdownDocumentInput) =>
      request<MarkdownDocumentResult>('/api/v1/documents/markdown', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    sourceMetadata: (documentId: string) =>
      request<SourceObjectView>(
        `/api/v1/documents/${encodeURIComponent(documentId)}/source`,
      ),
    sourceContent: (documentId: string, inline = false) =>
      requestBlob(
        `/api/v1/documents/${encodeURIComponent(documentId)}/source/content?inline=${inline}`,
      ),
    transitionDocument: (
      documentId: string,
      status: 'ACTIVE' | 'ARCHIVED' | 'DELETED',
      expectedVersion: number,
    ) =>
      request<DocumentLifecycleResult>(
        `/api/v1/documents/${encodeURIComponent(documentId)}/status`,
        {
          method: 'PATCH',
          body: JSON.stringify({ status, expectedVersion }),
        },
      ),
    connectors: () => request<Connector[]>('/api/v1/admin/connectors'),
    configureObsidian: (body: {
      connectorId: string
      spaceId: string
      displayName: string
      vaultName: string
      vaultPath: string
      authority: number
    }) =>
      request<void>('/api/v1/connectors/obsidian', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    synchronizeConnector: (connectorId: string) =>
      request<{ runId: string; status: string }>(
        `/api/v1/connectors/${encodeURIComponent(connectorId)}/sync`,
        { method: 'POST' },
      ),
    connectorRun: (runId: string) =>
      request<ConnectorRun>(
        `/api/v1/connectors/runs/${encodeURIComponent(runId)}`,
      ),
    traces: () => request<Trace[]>('/api/v1/admin/traces?limit=100'),
    trace: (traceId: string) =>
      request<Trace>(`/api/v1/admin/traces/${encodeURIComponent(traceId)}`),
    auditEvents: (limit = 50, offset = 0) =>
      request<Page<AuditEvent>>(
        `/api/v1/admin/audit-events?limit=${limit}&offset=${offset}`,
      ),
    query: (body: KnowledgeQueryInput) =>
      request<EvidenceBundle>('/api/v1/knowledge/query', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    retrievalObservationByRequest: (requestId: string) =>
      request<RetrievalObservationReport>(
        `/api/v1/retrieval-observations/requests/${encodeURIComponent(requestId)}`,
      ),
    onlineRetrievalOverview: (filter: RetrievalObservabilityFilter) =>
      request<OnlineRetrievalOverview>(
        `/api/v1/retrieval-observability/online/overview?${retrievalObservabilityParameters(filter)}`,
      ),
    retrievalStageDiagnostics: (filter: RetrievalObservabilityFilter) =>
      request<RetrievalStageDiagnostics>(
        `/api/v1/retrieval-observability/online/stages?${retrievalObservabilityParameters(filter)}`,
      ),
    retrievalExecutions: (filter: RetrievalExecutionFilter) =>
      request<RetrievalExecutionPage>(
        `/api/v1/retrieval-observability/online/executions?${retrievalObservabilityParameters(filter)}`,
      ),
    graphSearch: (body: {
      query: string
      spaceIds: string[]
      maxHops: number
      limit: number
    }) =>
      request<GraphSearchResult>('/api/v1/admin/graph/search', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    datasets: () => request<Dataset[]>('/api/v1/evaluations/datasets'),
    createDataset: (body: { name: string; description: string }) =>
      request<Dataset>('/api/v1/evaluations/datasets', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    cases: (datasetId: string) =>
      request<EvaluationCase[]>(
        `/api/v1/evaluations/datasets/${datasetId}/cases`,
      ),
    createCase: (
      datasetId: string,
      body: EvaluationCaseInput,
    ) =>
      request<EvaluationCase>(
        `/api/v1/evaluations/datasets/${datasetId}/cases`,
        { method: 'POST', body: JSON.stringify(body) },
      ),
    runs: (datasetId: string) =>
      request<EvaluationRun[]>(
        `/api/v1/evaluations/datasets/${datasetId}/runs`,
      ),
    startRun: (datasetId: string, topK: number) =>
      request<EvaluationRun>(
        `/api/v1/evaluations/datasets/${datasetId}/runs`,
        {
          method: 'POST',
          body: JSON.stringify({ topK, configuration: {} }),
        },
      ),
    compareRuns: (datasetId: string, body: EvaluationComparisonInput) =>
      request<EvaluationRunComparison>(
        `/api/v1/evaluations/datasets/${datasetId}/compare`,
        { method: 'POST', body: JSON.stringify(body) },
      ),
    run: (runId: string) =>
      request<EvaluationRun>(`/api/v1/evaluations/runs/${runId}`),
  }
}

function retrievalObservabilityParameters(
  filter: RetrievalExecutionFilter,
): string {
  const parameters = new URLSearchParams({
    from: filter.from,
    to: filter.to,
  })
  if (filter.spaceId) parameters.set('spaceId', filter.spaceId)
  if (filter.configFingerprint) {
    parameters.set('configFingerprint', filter.configFingerprint)
  }
  if (filter.dataIndexVersion) {
    parameters.set('dataIndexVersion', filter.dataIndexVersion)
  }
  if (filter.page !== undefined) parameters.set('page', String(filter.page))
  if (filter.size !== undefined) parameters.set('size', String(filter.size))
  if (filter.terminalStatus) {
    parameters.set('terminalStatus', filter.terminalStatus)
  }
  if (filter.stopReason) parameters.set('stopReason', filter.stopReason)
  return parameters.toString()
}

export type KnowledgeApi = ReturnType<typeof createApi>
