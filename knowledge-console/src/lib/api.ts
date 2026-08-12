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
  updatedAt: string
}

export type SpaceGrant = {
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT' | 'TENANT'
  subjectId: string
  permission: 'READ' | 'WRITE' | 'ADMIN'
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

export type FileDocumentResult = {
  documentId: string
  revisionId: string
  changed: boolean
  elementCount: number
  chunkCount: number
  originalFileName: string
  mediaType: string
  contentLength: number
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
    createSpace: (body: { spaceId: string; name: string }) =>
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
    rebuildSpaceProjections: (spaceId: string) =>
      request<ProjectionRebuild>(
        `/api/v1/spaces/${encodeURIComponent(spaceId)}/projections/rebuild`,
        { method: 'POST' },
      ),
    documents: ({
      spaceId,
      status,
      limit = 25,
      offset = 0,
    }: {
      spaceId?: string
      status?: string
      limit?: number
      offset?: number
    } = {}) => {
      const parameters = new URLSearchParams({
        limit: String(limit),
        offset: String(offset),
      })
      if (spaceId) parameters.set('spaceId', spaceId)
      if (status) parameters.set('status', status)
      return request<Page<DocumentView>>(
        `/api/v1/admin/documents?${parameters.toString()}`,
      )
    },
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
    ingestFile: (body: FormData) =>
      request<FileDocumentResult>('/api/v1/documents/files', {
        method: 'POST',
        body,
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
    query: (body: {
      query: string
      spaceIds: string[]
      topK: number
      filters: Record<string, string>
    }) =>
      request<EvidenceBundle>('/api/v1/knowledge/query', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
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

export type KnowledgeApi = ReturnType<typeof createApi>
