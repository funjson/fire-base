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
  updatedAt: string
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

export type Chunk = {
  id: string
  ordinal: number
  sectionPath: string[]
  content: string
  contentHash: string
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
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  recordsSeen: number
  recordsChanged: number
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
  status: string
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

export function createApi(getToken: () => string | undefined) {
  async function request<T>(
    path: string,
    init: RequestInit = {},
  ): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('X-Request-Id', crypto.randomUUID())
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body && !headers.has('Content-Type')) {
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
      limit = 25,
      offset = 0,
    }: {
      spaceId?: string
      limit?: number
      offset?: number
    } = {}) => {
      const parameters = new URLSearchParams({
        limit: String(limit),
        offset: String(offset),
      })
      if (spaceId) parameters.set('spaceId', spaceId)
      return request<Page<DocumentView>>(
        `/api/v1/admin/documents?${parameters.toString()}`,
      )
    },
    chunks: (documentId: string) =>
      request<Chunk[]>(`/api/v1/admin/documents/${documentId}/chunks`),
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
    run: (runId: string) =>
      request<EvaluationRun>(`/api/v1/evaluations/runs/${runId}`),
  }
}

export type KnowledgeApi = ReturnType<typeof createApi>
