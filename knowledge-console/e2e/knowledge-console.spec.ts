import { Buffer } from 'node:buffer'
import { randomUUID } from 'node:crypto'
import { env } from 'node:process'
import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Page,
} from '@playwright/test'

const settings = {
  webUrl: withoutTrailingSlash(env.E2E_WEB_URL ?? 'http://localhost:5173'),
  apiUrl: withoutTrailingSlash(env.E2E_API_URL ?? 'http://localhost:8080'),
  oidcUrl: withoutTrailingSlash(env.E2E_OIDC_URL ?? 'http://localhost:8180'),
  realm: env.E2E_OIDC_REALM ?? 'infinity-knowledge',
  directClientId: env.E2E_OIDC_DIRECT_CLIENT_ID ?? 'infinity-knowledge-cli',
  admin: {
    username: env.E2E_ADMIN_USERNAME ?? 'demo-admin',
    password: env.E2E_ADMIN_PASSWORD ?? 'demo-admin',
  },
  reader: {
    username: env.E2E_READER_USERNAME ?? 'demo-reader',
    password: env.E2E_READER_PASSWORD ?? 'demo-reader',
  },
}

type Identity = {
  username: string
  password: string
  token: string
  subject: string
}

type Runtime = {
  admin: Identity
  reader: Identity
}

type Seed = {
  spaceId: string
  spaceName: string
  documentId: string
  documentTitle: string
  retrievalFact: string
  fileDocumentId: string
  fileTitle: string
  fileFact: string
  datasetId: string
  datasetName: string
  wikiTitle: string
}

let shared: { runtime: Runtime; seed: Seed } | undefined

test.describe.serial('OIDC-backed console journeys', () => {
  test('admin completes the governed knowledge and quality journey', async ({
    request,
    page,
  }) => {
    const value = await sharedRuntime(request)
    if (!value) return
    const { runtime, seed } = value

    await verifyAdminApiJourney(request, runtime, seed)

    await login(page, runtime.admin)

    await openRoute(page, '/spaces', '知识空间是权限和检索策略的边界')
    const space = page.locator('article.space-card').filter({ hasText: seed.spaceName })
    await expect(space).toBeVisible()
    await space.getByRole('button', { name: '管理授权' }).click()
    await expect(page.getByText(runtime.reader.subject, { exact: true })).toBeVisible()

    await openRoute(page, '/documents', '查看文档、活动修订和索引投影')
    await expect(page.getByText(seed.documentTitle, { exact: true })).toBeVisible()
    await expect(page.getByText(seed.fileTitle, { exact: true })).toBeVisible()

    await openRoute(page, '/evaluations', '用可复现实验持续提升检索质量')
    await expect(page.getByText(seed.datasetName, { exact: true }).first()).toBeVisible()
    await expect(page.getByText('SUCCEEDED', { exact: true }).first()).toBeVisible()

    await openRoute(page, '/graph', '企业知识关系探索')
    await openRoute(page, '/wiki', '将可信来源编译为可审核、可发布的知识页')
    await expect(page.getByText(seed.wikiTitle, { exact: true })).toBeVisible()

    await openRoute(page, '/audit-events', '租户操作审计')
    await expect(page.getByText(
      '/api/v1/admin/wiki/pages/{pageId}/publish',
      { exact: true },
    )).toBeVisible()
  })

  test('reader receives only ACL-scoped evidence and source access', async ({
    request,
    page,
  }) => {
    const value = await sharedRuntime(request)
    if (!value) return
    const { runtime, seed } = value

    const accessible = await apiRequest(
      request,
      runtime.reader.token,
      'GET',
      '/api/v1/spaces/accessible',
    )
    await expectStatus(accessible, 200)
    const spaces = (await accessible.json()) as Array<{ id: string }>
    expect(spaces.some((space) => space.id === seed.spaceId)).toBe(true)

    const query = await apiRequest(
      request,
      runtime.reader.token,
      'POST',
      '/api/v1/knowledge/query',
      {
        query: seed.fileFact,
        spaceIds: [seed.spaceId],
        topK: 5,
        filters: {},
      },
    )
    await expectStatus(query, 200)
    const bundle = (await query.json()) as EvidenceBundle
    expect(bundle.evidences.some(
      (evidence) => evidence.citation.documentId === seed.fileDocumentId
        && Boolean(evidence.citation.revisionId)
        && Boolean(evidence.citation.chunkId),
    )).toBe(true)

    await expectStatus(await apiRequest(
      request,
      runtime.reader.token,
      'GET',
      `/api/v1/documents/${seed.fileDocumentId}/source`,
    ), 200)
    const sourceContent = await apiRequest(
      request,
      runtime.reader.token,
      'GET',
      `/api/v1/documents/${seed.fileDocumentId}/source/content`,
    )
    await expectStatus(sourceContent, 200)
    expect(await sourceContent.text()).toContain(seed.fileFact)

    await expectStatus(await apiRequest(
      request,
      runtime.reader.token,
      'GET',
      '/api/v1/admin/overview',
    ), 403)

    await login(page, runtime.reader)
    await page.goto(`${settings.webUrl}/documents`)
    await expect(page).toHaveURL(/\/retrieval$/)
    await expect(
      page.getByRole('heading', { name: '检查每一次召回、融合和引用' }),
    ).toBeVisible()
    await expect(page.getByRole('link', { name: '文档管理' })).toHaveCount(0)

    const space = page.getByRole('checkbox', {
      name: new RegExp(escapeRegex(seed.spaceName)),
    })
    await expect(space).toBeVisible()
    await space.check()
    await page.getByRole('textbox', { name: '查询', exact: true }).fill(
      seed.fileFact,
    )
    const response = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().endsWith('/api/v1/knowledge/query'),
    )
    await page.getByRole('button', { name: '运行检索' }).click()
    expect((await response).status()).toBe(200)
    await expect(page.getByText(seed.fileTitle, { exact: true }).first()).toBeVisible()
  })
})

type EvidenceBundle = {
  evidences: Array<{
    channels: string[]
    citation: { documentId: string; revisionId: string; chunkId: string }
  }>
}

type ProjectionJob = {
  id: string
  projectionType: 'KEYWORD' | 'VECTOR' | 'GRAPH'
  status: 'PENDING' | 'RUNNING' | 'RETRY' | 'SUCCEEDED' | 'DEAD'
}

async function sharedRuntime(
  request: APIRequestContext,
): Promise<{ runtime: Runtime; seed: Seed } | undefined> {
  if (shared) return shared
  const runtime = await readyRuntime(request)
  if (!runtime) return undefined
  shared = { runtime, seed: await seedTenant(request, runtime) }
  return shared
}

async function readyRuntime(
  request: APIRequestContext,
): Promise<Runtime | undefined> {
  const webReady = await reachable(request, settings.webUrl)
  const apiReady = await reachable(request, `${settings.apiUrl}/actuator/health`)
  if (!webReady || !apiReady) {
    test.skip(
      true,
      'The externally managed console and API services must already be reachable',
    )
    return undefined
  }

  try {
    const [admin, reader] = await Promise.all([
      identity(request, settings.admin.username, settings.admin.password),
      identity(request, settings.reader.username, settings.reader.password),
    ])
    return { admin, reader }
  } catch {
    test.skip(
      true,
      'The configured OIDC service or direct-grant test identities are unavailable',
    )
    return undefined
  }
}

async function reachable(request: APIRequestContext, url: string) {
  try {
    const response = await request.get(url, {
      failOnStatusCode: false,
      timeout: 3_000,
    })
    return response.ok()
  } catch {
    return false
  }
}

async function identity(
  request: APIRequestContext,
  username: string,
  password: string,
): Promise<Identity> {
  const response = await request.post(
    `${settings.oidcUrl}/realms/${encodeURIComponent(settings.realm)}` +
      '/protocol/openid-connect/token',
    {
      failOnStatusCode: false,
      form: {
        client_id: settings.directClientId,
        grant_type: 'password',
        username,
        password,
      },
      timeout: 4_000,
    },
  )
  if (!response.ok()) throw new Error(`OIDC returned HTTP ${response.status()}`)
  const body = (await response.json()) as { access_token?: string }
  if (!body.access_token) throw new Error('OIDC response has no access token')
  return {
    username,
    password,
    token: body.access_token,
    subject: jwtSubject(body.access_token),
  }
}

function jwtSubject(token: string): string {
  const encodedPayload = token.split('.')[1]
  if (!encodedPayload) throw new Error('OIDC access token is not a JWT')
  const payload = JSON.parse(
    Buffer.from(encodedPayload, 'base64url').toString('utf8'),
  ) as { sub?: string }
  if (!payload.sub) throw new Error('OIDC access token has no subject')
  return payload.sub
}

async function seedTenant(
  request: APIRequestContext,
  runtime: Runtime,
): Promise<Seed> {
  const suffix = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
  const spaceId = `e2e-${suffix}`
  const spaceName = `E2E Space ${suffix}`
  const documentTitle = `E2E Markdown ${suffix}`
  const fileTitle = `E2E Source ${suffix}`
  const datasetName = `E2E Evaluation ${suffix}`
  const wikiTitle = `E2E Wiki ${suffix}`
  const safeSuffix = suffix.replace(/[^a-zA-Z0-9]/g, '_')
  const retrievalFact = `E2E_ACCESS_BOUNDARY_${safeSuffix}`
  const fileFact = `E2E_ORIGINAL_SOURCE_${safeSuffix}`

  await expectStatus(
    await apiRequest(request, runtime.admin.token, 'POST', '/api/v1/spaces', {
      spaceId,
      name: spaceName,
    }),
    204,
  )
  await expectStatus(
    await apiRequest(
      request,
      runtime.admin.token,
      'POST',
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/acl`,
      {
        subjectType: 'USER',
        subjectId: runtime.reader.subject,
        permission: 'READ',
      },
    ),
    204,
  )
  const documentResponse = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/documents/markdown',
    {
      spaceId,
      externalId: `e2e/${suffix}.md`,
      title: documentTitle,
      sourceUri: `https://e2e.example.invalid/${suffix}`,
      language: 'en',
      authority: 80,
      content: [
        '# E2E access boundary',
        '',
        `${retrievalFact} is visible only to the granted reader.`,
        'OrderService calls InventoryService and depends on Redis.',
      ].join('\n'),
      metadata: { suite: 'playwright' },
    },
  )
  await expectStatus(documentResponse, 200)
  const document = (await documentResponse.json()) as {
    documentId: string
    revisionId: string
  }

  const fileResponse = await request.post(`${settings.apiUrl}/api/v1/documents/files`, {
    failOnStatusCode: false,
    headers: authorizedHeaders(runtime.admin.token),
    multipart: {
      file: {
        name: `e2e-${suffix}.txt`,
        mimeType: 'text/plain',
        buffer: Buffer.from(`${fileFact}\nRetained source bytes for ACL verification.`, 'utf8'),
      },
      spaceId,
      externalId: `e2e/${suffix}.txt`,
      title: fileTitle,
      language: 'en',
      authority: '80',
    },
  })
  await expectStatus(fileResponse, 200)
  const fileDocument = (await fileResponse.json()) as { documentId: string }

  // Projection is asynchronous. Wait for both search channels before asserting retrieval.
  for (const documentId of [document.documentId, fileDocument.documentId]) {
    for (const projectionType of ['KEYWORD', 'VECTOR'] as const) {
      const projection = await waitForProjection(
        request,
        runtime.admin.token,
        documentId,
        projectionType,
      )
      expect(projection.status).toBe('SUCCEEDED')
    }
  }

  const datasetResponse = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/evaluations/datasets',
    { name: datasetName, description: 'Playwright API setup' },
  )
  await expectStatus(datasetResponse, 201)
  const dataset = (await datasetResponse.json()) as { id: string }
  await expectStatus(
    await apiRequest(
      request,
      runtime.admin.token,
      'POST',
      `/api/v1/evaluations/datasets/${dataset.id}/cases`,
      {
        query: retrievalFact,
        spaceIds: [spaceId],
        expectedDocuments: [document.documentId],
        expectedChunks: [],
        topK: 5,
        labels: { suite: 'playwright' },
      },
    ),
    201,
  )

  return {
    spaceId,
    spaceName,
    documentId: document.documentId,
    documentTitle,
    retrievalFact,
    fileDocumentId: fileDocument.documentId,
    fileTitle,
    fileFact,
    datasetId: dataset.id,
    datasetName,
    wikiTitle,
  }
}

async function verifyAdminApiJourney(
  request: APIRequestContext,
  runtime: Runtime,
  seed: Seed,
) {
  const query = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/knowledge/query',
    {
      query: seed.retrievalFact,
      spaceIds: [seed.spaceId],
      topK: 5,
      filters: {},
    },
  )
  await expectStatus(query, 200)
  const bundle = (await query.json()) as EvidenceBundle
  const sourceEvidence = bundle.evidences.find(
    (evidence) => evidence.citation.documentId === seed.documentId,
  )
  expect(sourceEvidence).toBeDefined()
  expect(sourceEvidence?.citation.revisionId).toBeTruthy()
  expect(sourceEvidence?.citation.chunkId).toBeTruthy()

  const projections = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    `/api/v1/documents/${seed.documentId}/projections`,
  )
  await expectStatus(projections, 200)
  const projectionJobs = (await projections.json()) as ProjectionJob[]
  expect(projectionJobs.length).toBeGreaterThan(0)
  expect(projectionJobs.every(
    (job) => Boolean(job.id) && Boolean(job.projectionType) && Boolean(job.status),
  )).toBe(true)
  const graphProjectionConfigured = projectionJobs.some(
    (job) => job.projectionType === 'GRAPH',
  )

  const sourceMetadata = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    `/api/v1/documents/${seed.fileDocumentId}/source`,
  )
  await expectStatus(sourceMetadata, 200)
  expect((await sourceMetadata.json()) as { originalFileName: string }).toMatchObject({
    originalFileName: expect.stringMatching(/\.txt$/),
  })

  const evaluation = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    `/api/v1/evaluations/datasets/${seed.datasetId}/runs`,
    { topK: 5, configuration: { suite: 'playwright' } },
  )
  await expectStatus(evaluation, 202)
  const startedRun = (await evaluation.json()) as { id: string }
  const completedRun = await waitForEvaluationRun(
    request,
    runtime.admin.token,
    startedRun.id,
  )
  expect(completedRun.status).toBe('SUCCEEDED')
  expect(completedRun.metrics.hitRate).toBe(1)

  const chunksResponse = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    `/api/v1/admin/documents/${seed.documentId}/chunks`,
  )
  await expectStatus(chunksResponse, 200)
  const chunks = (await chunksResponse.json()) as Array<{ id: string }>
  expect(chunks.length).toBeGreaterThan(0)
  const documentsResponse = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    `/api/v1/admin/documents?spaceId=${encodeURIComponent(seed.spaceId)}&status=ACTIVE`,
  )
  await expectStatus(documentsResponse, 200)
  const documents = (await documentsResponse.json()) as {
    items: Array<{ id: string; activeRevisionId: string }>
  }
  const document = documents.items.find((item) => item.id === seed.documentId)
  if (!document?.activeRevisionId) {
    throw new Error(`active revision is missing for ${seed.documentId}`)
  }
  const firstChunk = chunks[0]
  if (!firstChunk) throw new Error(`active chunks are missing for ${seed.documentId}`)

  const compiled = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/admin/wiki/pages',
    {
      spaceId: seed.spaceId,
      slug: `e2e-${seed.documentId}`,
      title: seed.wikiTitle,
      sources: [{
        documentId: seed.documentId,
        revisionId: document.activeRevisionId,
        chunkId: firstChunk.id,
      }],
    },
  )
  await expectStatus(compiled, 201)
  let wiki = (await compiled.json()) as WikiDetail
  wiki = await transitionWiki(
    request,
    runtime.admin.token,
    wiki.page.id,
    'submit-review',
    wiki.page.version,
  )
  wiki = await transitionWiki(
    request,
    runtime.admin.token,
    wiki.page.id,
    'publish',
    wiki.page.version,
  )
  expect(wiki.page.status).toBe('PUBLISHED')

  const wikiQuery = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/knowledge/query',
    { query: seed.wikiTitle, spaceIds: [seed.spaceId], topK: 5, filters: {} },
  )
  await expectStatus(wikiQuery, 200)
  const wikiBundle = (await wikiQuery.json()) as EvidenceBundle
  expect(wikiBundle.evidences.some(
    (evidence) => evidence.channels.includes('PAGE')
      && evidence.citation.documentId === seed.documentId,
  )).toBe(true)

  if (graphProjectionConfigured) {
    const graphProjection = await waitForProjection(
      request,
      runtime.admin.token,
      seed.documentId,
      'GRAPH',
    )
    expect(graphProjection.status).toBe('SUCCEEDED')
  }
  const graph = await apiRequest(
    request,
    runtime.admin.token,
    'POST',
    '/api/v1/admin/graph/search',
    { query: 'OrderService', spaceIds: [seed.spaceId], maxHops: 2, limit: 25 },
  )
  expect([200, 503]).toContain(graph.status())
  if (graph.status() === 200) {
    const graphResult = (await graph.json()) as {
      tenantId: string
      edges: Array<{ provenance: { documentId: string } }>
    }
    expect(graphResult.tenantId).toBeTruthy()
    expect(Array.isArray(graphResult.edges)).toBe(true)
    if (graphProjectionConfigured) {
      expect(graphResult.edges.some(
        (edge) => edge.provenance.documentId === seed.documentId,
      )).toBe(true)
    }
  } else {
    expect(graphProjectionConfigured).toBe(false)
    expect((await graph.json()) as { code: string }).toMatchObject({
      code: 'GRAPH_CAPABILITY_UNAVAILABLE',
    })
  }

  const audit = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    '/api/v1/admin/audit-events?limit=200&offset=0',
  )
  await expectStatus(audit, 200)
  const auditPage = (await audit.json()) as {
    items: Array<{ routePattern: string; action: string }>
  }
  expect(auditPage.items.some(
    (event) => event.routePattern === '/api/v1/documents/files',
  )).toBe(true)
  expect(auditPage.items.some(
    (event) => event.routePattern === '/api/v1/admin/wiki/pages/{pageId}/publish',
  )).toBe(true)
}

type WikiDetail = {
  page: { id: string; status: string; version: number }
}

async function transitionWiki(
  request: APIRequestContext,
  token: string,
  pageId: string,
  action: 'submit-review' | 'publish',
  expectedVersion: number,
): Promise<WikiDetail> {
  const response = await apiRequest(
    request,
    token,
    'POST',
    `/api/v1/admin/wiki/pages/${pageId}/${action}`,
    { expectedVersion },
  )
  await expectStatus(response, 200)
  return (await response.json()) as WikiDetail
}

async function waitForEvaluationRun(
  request: APIRequestContext,
  token: string,
  runId: string,
) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const response = await apiRequest(
      request,
      token,
      'GET',
      `/api/v1/evaluations/runs/${runId}`,
    )
    await expectStatus(response, 200)
    const run = (await response.json()) as {
      status: string
      metrics: Record<string, number>
      errorCode: string | null
    }
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.status)) return run
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`evaluation run ${runId} did not reach a terminal state`)
}

async function waitForProjection(
  request: APIRequestContext,
  token: string,
  documentId: string,
  projectionType: ProjectionJob['projectionType'],
): Promise<ProjectionJob> {
  // A real acceptance run serializes bounded vector and Graph projections.
  for (let attempt = 0; attempt < 180; attempt += 1) {
    const response = await apiRequest(
      request,
      token,
      'GET',
      `/api/v1/documents/${documentId}/projections`,
    )
    await expectStatus(response, 200)
    const jobs = (await response.json()) as ProjectionJob[]
    const job = jobs.find((candidate) => candidate.projectionType === projectionType)
    if (!job) throw new Error(`${projectionType} projection disappeared for ${documentId}`)
    if (['SUCCEEDED', 'DEAD'].includes(job.status)) return job
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`${projectionType} projection did not reach a terminal state`)
}

async function apiRequest(
  request: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  data?: unknown,
) {
  return request.fetch(`${settings.apiUrl}${path}`, {
    method,
    data,
    failOnStatusCode: false,
    headers: authorizedHeaders(token),
  })
}

function authorizedHeaders(token: string) {
  return {
    Authorization: `Bearer ${token}`,
    'X-Request-Id': randomUUID(),
  }
}

async function expectStatus(response: APIResponse, expected: number) {
  const detail = response.status() === expected ? '' : await response.text()
  expect(response.status(), detail).toBe(expected)
}

async function login(page: Page, identityValue: Identity) {
  await page.goto(`${settings.webUrl}/`, { waitUntil: 'domcontentloaded' })
  const username = page.locator('input[name="username"]')
  await expect(username).toBeVisible({ timeout: 15_000 })
  await username.fill(identityValue.username)
  await page.locator('input[name="password"]').fill(identityValue.password)
  await page
    .locator('#kc-login, button[type="submit"], input[type="submit"]')
    .first()
    .click()
  const consoleOrigin = new URL(settings.webUrl).origin
  await page.waitForURL((url) => url.origin === consoleOrigin, { timeout: 20_000 })
}

async function openRoute(page: Page, path: string, heading: string) {
  await page.goto(`${settings.webUrl}${path}`)
  await expect(page.getByRole('heading', { name: heading })).toBeVisible()
}

function withoutTrailingSlash(value: string) {
  return value.replace(/\/$/, '')
}

function escapeRegex(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
