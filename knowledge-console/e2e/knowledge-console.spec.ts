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
    await space.getByRole('button', { name: '查看处理配置' }).click()
    const parserCapabilities = page.getByLabel('Parser 能力目录')
    for (const parserId of [
      'markdown-structure',
      'plain-text',
      'html-structure',
      'pdfbox-page',
      'poi-docx-structure',
      'docling-serve-pdf',
      'docling-serve-docx',
    ]) {
      await expect(parserCapabilities.getByText(parserId, { exact: true })).toBeVisible()
    }
    const chunkerCapabilities = page.getByLabel('Chunker 部署能力')
    await expect(chunkerCapabilities.getByText('STRUCTURAL', { exact: true })).toBeVisible()
    await expect(
      chunkerCapabilities.getByText('SEMANTIC_REFINEMENT', { exact: true }),
    ).toBeVisible()
    await expect(
      page.getByLabel('Chunker 部署能力').locator('article.unavailable small'),
    ).not.toHaveText('')
    await page.getByRole('button', { name: '关闭' }).click()
    await space.getByRole('link', { name: '数据抽取' }).click()
    await expect(page).toHaveURL(
      new RegExp(`/spaces/${escapeRegex(seed.spaceId)}/extraction$`),
    )
    await expect(
      page.getByRole('heading', { name: '数据抽取与正式摄取工作台' }),
    ).toBeVisible()
    await expect(
      page.getByRole('heading', { name: '当前抽取能力' }),
    ).toBeVisible()
    const multiFileInput = page.getByLabel('选择多个源文件')
    await expect(multiFileInput).toBeEnabled()
    await expect(multiFileInput).toHaveAttribute('multiple', '')
    await multiFileInput.setInputFiles([
      {
        name: 'extraction-one.md',
        mimeType: 'text/markdown',
        buffer: Buffer.from('# First\n\nExtraction one.'),
      },
      {
        name: 'extraction-two.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('Extraction two.'),
      },
    ])
    await expect(page.getByText('已选 2 个文件')).toBeVisible()
    await expect(
      page.getByRole('heading', {
        name: '企业级验收硬门禁（规则说明）',
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('heading', { name: 'Dataset 验收实验' }),
    ).toBeVisible()
    await expect(page.getByLabel('Golden Dataset')).toHaveValue(
      'extraction-artifact-provenance-v4',
    )
    await expect(
      page.getByText('Run 成功与 Gate 通过会分别记录。'),
    ).toBeVisible()
    await page.getByRole('link', { name: '返回知识空间' }).click()
    await expect(space).toBeVisible()
    await space.getByRole('button', { name: '管理授权' }).click()
    await expect(page.getByText(runtime.reader.subject, { exact: true })).toBeVisible()

    await openRoute(page, '/documents', '查看文档、活动修订和索引投影')
    await expect(page.getByText(seed.documentTitle, { exact: true })).toBeVisible()

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

    await openRoute(
      page,
      '/observability/retrieval/overview',
      '用在线运行事实定位检索问题',
    )
    await expect(page.getByText('ONLINE 数据已隔离', { exact: true })).toBeVisible()
    await expect(page.getByLabel('统计窗口')).toBeEnabled()
    await expect(page.getByLabel('知识空间')).toBeEnabled()
    const observabilityNavigation = page.getByRole('navigation', {
      name: '检索观测视图',
    })
    await observabilityNavigation.getByRole('link', { name: /分层诊断/ }).click()
    await expect(page).toHaveURL(/\/observability\/retrieval\/stages/)
    await observabilityNavigation.getByRole('link', { name: /执行记录/ }).click()
    await expect(page).toHaveURL(/\/observability\/retrieval\/executions/)
  })

  test('admin creates a Space with one immutable processing configuration', async ({
    request,
    page,
  }) => {
    const value = await sharedRuntime(request)
    if (!value) return
    const suffix = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
    const spaceId = `e2e-console-create-${suffix}`
    const spaceName = `E2E Console Create ${suffix}`

    await login(page, value.runtime.admin)
    await openRoute(page, '/spaces', '知识空间是权限和检索策略的边界')
    await page.getByRole('button', { name: '新建空间' }).first().click()
    const form = page.getByRole('form', {
      name: '新建知识空间与文档处理配置',
    })
    await expect(form.getByLabel('空间标识')).toBeEnabled()
    await expect(form.getByLabel('application/pdf Parser')).toBeEnabled()
    await expect(form.getByLabel('Chunker Provider')).toBeEnabled()
    await expect(form.getByLabel('Token Counter')).toBeEnabled()
    await form.getByLabel('空间标识').fill(spaceId)
    await form.getByLabel('显示名称').fill(spaceName)
    await form
      .getByLabel('空间描述')
      .fill('E2E console creation knowledge space')

    const createRequestPromise = page.waitForRequest(
      (candidate) =>
        candidate.method() === 'POST' &&
        new URL(candidate.url()).pathname === '/api/v1/spaces',
    )
    const createResponsePromise = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname === '/api/v1/spaces',
    )
    await form.getByRole('button', { name: '确认配置并创建' }).click()
    const createRequest = await createRequestPromise
    const payload = createRequest.postDataJSON() as {
      spaceId: string
      name: string
      description: string
      documentProcessingConfig: SpaceProcessingConfigBody
    }
    expect(payload.spaceId).toBe(spaceId)
    expect(payload.name).toBe(spaceName)
    expect(payload.description).toBe('E2E console creation knowledge space')
    expect(payload.documentProcessingConfig.parserSelections.length).toBeGreaterThan(0)
    expect(payload.documentProcessingConfig.chunker.providerId).toBeTruthy()
    expect(payload.documentProcessingConfig.chunker.tokenizerId).toBeTruthy()
    expect(payload.documentProcessingConfig).not.toHaveProperty('processingContract')
    expect((await createResponsePromise).status()).toBe(204)

    const processingConfigPath =
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`
    const persistedResponse = await apiRequest(
      request,
      value.runtime.admin.token,
      'GET',
      processingConfigPath,
    )
    await expectStatus(persistedResponse, 200)
    const persisted = (await persistedResponse.json()) as SpaceProcessingConfig
    expect(spaceConfigBody(persisted)).toEqual(
      normalizedSpaceConfigBody(payload.documentProcessingConfig),
    )
    expect(persisted.version).toBe(1)
    expect(persisted.updatedAt).toBeTruthy()
    expect(persisted.updatedBy).toBeTruthy()
    assertProcessingContract(
      persisted.processingContract,
      persisted.parserSelections.map((selection) => selection.mediaType),
    )
    expect(persisted.runtimeContractMatched).toBe(true)

    await expectStatus(
      await apiRequest(
        request,
        value.runtime.admin.token,
        'POST',
        '/api/v1/spaces',
        payload,
      ),
      204,
    )

    const duplicateReadResponse = await apiRequest(
      request,
      value.runtime.admin.token,
      'GET',
      processingConfigPath,
    )
    await expectStatus(duplicateReadResponse, 200)
    const duplicateRead = (await duplicateReadResponse.json()) as SpaceProcessingConfig
    expect(duplicateRead.processingContract).toEqual(persisted.processingContract)

    const differentConfig = cloneProcessingConfig(
      payload.documentProcessingConfig,
    )
    differentConfig.cleaning.header =
      differentConfig.cleaning.header === 'KEEP' ? 'REMOVE' : 'KEEP'
    await expectStatus(
      await apiRequest(
        request,
        value.runtime.admin.token,
        'POST',
        '/api/v1/spaces',
        { ...payload, documentProcessingConfig: differentConfig },
      ),
      409,
    )

    await expectStatus(
      await apiRequest(
        request,
        value.runtime.admin.token,
        'PUT',
        processingConfigPath,
        payload.documentProcessingConfig,
      ),
      405,
    )

    const unchangedResponse = await apiRequest(
      request,
      value.runtime.admin.token,
      'GET',
      processingConfigPath,
    )
    await expectStatus(unchangedResponse, 200)
    const unchanged = (await unchangedResponse.json()) as SpaceProcessingConfig
    expect(spaceConfigBody(unchanged)).toEqual(spaceConfigBody(persisted))
    expect(unchanged.version).toBe(1)
    expect(unchanged.processingContract).toEqual(persisted.processingContract)

    const card = page.locator('article.space-card').filter({ hasText: spaceName })
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '查看处理配置' }).click()
    const readOnlyConfig = page.getByRole('form', {
      name: 'Space 文档处理配置（只读）',
    })
    await expect(readOnlyConfig.getByLabel('最大 Token 数')).toBeDisabled()
    await expect(
      readOnlyConfig.getByRole('heading', { name: '固化处理版本' }),
    ).toBeVisible()
    await expect(
      readOnlyConfig.getByText(persisted.processingContract.fingerprint, {
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      readOnlyConfig.getByText('当前部署匹配', { exact: true }),
    ).toBeVisible()
    await expect(readOnlyConfig.getByRole('button', { name: '保存配置' })).toHaveCount(0)
    await readOnlyConfig.getByRole('button', { name: '关闭' }).click()
  })

  test('reader receives only ACL-scoped evidence', async ({
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
        query: seed.retrievalFact,
        spaceIds: [seed.spaceId],
        topK: 5,
        filters: {},
      },
    )
    await expectStatus(query, 200)
    const bundle = (await query.json()) as EvidenceBundle
    expect(bundle.evidences.some(
      (evidence) => evidence.citation.documentId === seed.documentId
        && Boolean(evidence.citation.revisionId)
        && Boolean(evidence.citation.chunkId),
    )).toBe(true)

    await expectStatus(await apiRequest(
      request,
      runtime.reader.token,
      'GET',
      '/api/v1/admin/overview',
    ), 403)

    await login(page, runtime.reader)
    await page.goto(`${settings.webUrl}/documents`)
    await expect(page).toHaveURL(/\/evaluations\/playground$/)
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
      seed.retrievalFact,
    )
    await page.getByRole('button', { name: '设置参数' }).click()
    const overrideForm = page.getByRole('form', {
      name: '本次检索参数覆盖',
    })
    await overrideForm.getByLabel('最大检索尝试数').fill('2')
    await overrideForm.getByLabel('RRF 平滑常数').fill('70')
    await overrideForm.getByRole('button', {
      name: '移除RRF 平滑常数覆盖',
    }).click()
    await expect(overrideForm.getByText('本次只发送 1 项局部覆盖。')).toBeVisible()
    await overrideForm.getByRole('button', { name: '用于本次检索' }).click()
    await expect(
      page.getByText('本次请求将覆盖 1 项参数', { exact: true }),
    ).toBeVisible()
    const queryRequest = page.waitForRequest(
      (candidate) =>
        candidate.method() === 'POST' &&
        candidate.url().endsWith('/api/v1/knowledge/query'),
    )
    const response = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().endsWith('/api/v1/knowledge/query'),
    )
    await page.getByRole('button', { name: '运行检索' }).click()
    expect((await queryRequest).postDataJSON().configurationOverride).toEqual({
      maximumRetrievalAttempts: 2,
    })
    expect((await response).status()).toBe(200)
    await expect(page.getByText(seed.documentTitle, { exact: true }).first()).toBeVisible()
  })
})

test.describe.serial('extraction workbench acceptance', () => {
  test('admin completes a real two-file TEST_ONLY run', async ({ request, page }) => {
    const runtime = await readyRuntime(request)
    if (!runtime) return
    const spaceId = await createExtractionSpace(request, runtime.admin, 'success')

    const processingConfig = await apiRequest(
      request,
      runtime.admin.token,
      'GET',
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`,
    )
    await expectStatus(processingConfig, 200)
    const processingCapabilities =
      (await processingConfig.json()) as ExtractionProcessingCapability
    assertParserCapabilityCatalog(processingCapabilities.availableParsers)
    if (!env.E2E_EXACT_TOKENIZER_ID) {
      const externalTokenizer = processingCapabilities.availableTokenizers.find(
        (tokenizer) => tokenizer.id === 'HUGGINGFACE_LOCAL',
      )
      expect(externalTokenizer).toMatchObject({
        exactModelTokens: true,
        available: false,
      })
      expect(externalTokenizer?.unavailableReason).toBeTruthy()
    }

    await openExtractionWorkbench(page, runtime.admin, spaceId)
    await page.getByRole('button', { name: '查看 Space 配置' }).click()
    const parserCatalog = page.getByLabel('Parser 能力目录')
    const doclingPdf = parserCatalog.locator('article').filter({
      hasText: 'docling-serve-pdf',
    })
    const doclingDocx = parserCatalog.locator('article').filter({
      hasText: 'docling-serve-docx',
    })
    await expect(doclingPdf).toContainText('application/pdf')
    await expect(doclingPdf.getByText('默认', { exact: true })).toHaveCount(0)
    await expect(doclingDocx.getByText('不可用', { exact: true })).toBeVisible()
    await expect(doclingDocx).toContainText('DOCLING_DOCX_PARSER_DISABLED')
    if (!env.E2E_EXACT_TOKENIZER_ID) {
      const disabledTokenizer = page
        .getByLabel('Token Counter')
        .locator('option')
        .filter({ hasText: 'HUGGINGFACE_LOCAL' })
      await expect(disabledTokenizer).toHaveCount(1)
      await expect(disabledTokenizer).toHaveAttribute('disabled', '')
      await expect(disabledTokenizer).not.toHaveText('')
    }
    await page.getByRole('button', { name: '关闭' }).click()

    const firstSource = Buffer.from('# First\n\nExtraction one keeps its source span.', 'utf8')
    const secondSource = Buffer.from(
      'Extraction two verifies the plain-text parser and chunk preview.',
      'utf8',
    )
    const multiFileInput = page.getByLabel('选择多个源文件')
    await multiFileInput.setInputFiles([
      {
        name: 'extraction-one.md',
        mimeType: 'text/markdown',
        buffer: firstSource,
      },
      {
        name: 'extraction-two.txt',
        mimeType: 'text/plain',
        buffer: secondSource,
      },
    ])
    await expect(page.getByText('已选 2 个文件')).toBeVisible()

    const createResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-runs`,
    )
    await page.getByRole('button', { name: '运行抽取测试' }).click()
    expect((await createResponse).status()).toBe(202)

    const detail = extractionRunPanel(page)
    await expect(
      detail.locator('.extraction-run-overview').getByText('SUCCEEDED', { exact: true }),
    ).toBeVisible({ timeout: 60_000 })
    await expect(detail.locator('.extraction-item-entry')).toHaveCount(2)
    for (const [fileName, parserId] of [
      ['extraction-one.md', 'markdown-structure'],
      ['extraction-two.txt', 'plain-text'],
    ] as const) {
      const item = detail.locator('.extraction-item-entry').filter({ hasText: fileName })
      await expect(item.getByText('SUCCEEDED', { exact: true })).toBeVisible()
      await expect(item.getByText(parserId, { exact: true }).first()).toBeVisible()
      await expect(
        item.getByText('本次分层诊断（只含计数与耗时）', { exact: true }),
      ).toBeVisible()
      const preview = item.locator('details.extraction-artifact-preview')
      await expect(preview.locator('summary')).toContainText('内容与边界预览')
      await preview.locator('summary').click()
      await expect(preview.getByText('Element 结构', { exact: true })).toBeVisible()
      await expect(preview.getByText('Chunk 边界', { exact: true })).toBeVisible()
    }

    const gate = detail.locator('section.extraction-gate-report')
    await expect(gate.getByText('真实硬门禁报告', { exact: true })).toBeVisible()
    await expect(gate.getByText('NOT_EVALUATED', { exact: true })).toBeVisible()
    await expect(gate.getByText('未选择 Dataset', { exact: true })).toBeVisible()
    await expect(
      gate.getByText('本次没有真实门禁报告，不能判定为 PASSED。', { exact: true }),
    ).toBeVisible()

    const firstItem = detail.locator('.extraction-item-entry').filter({
      hasText: 'extraction-one.md',
    })
    const sourceResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'GET' &&
        new URL(candidate.url()).pathname.endsWith('/source'),
    )
    const downloadEvent = page.waitForEvent('download')
    await firstItem.getByRole('button', { name: '下载原件' }).click()
    const [downloadedSource, download] = await Promise.all([
      sourceResponse,
      downloadEvent,
    ])
    expect(downloadedSource.status()).toBe(200)
    expect(download.suggestedFilename()).toBe('extraction-one.md')
    const downloadedBytes: Buffer[] = []
    const sourceStream = await download.createReadStream()
    for await (const chunk of sourceStream) {
      downloadedBytes.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
    }
    expect(Buffer.compare(Buffer.concat(downloadedBytes), firstSource)).toBe(0)
    expect(await download.failure()).toBeNull()
  })

  test('Space compares a request-level TEST_ONLY config without changing its fixed config', async ({
    request,
    page,
  }) => {
    const runtime = await readyRuntime(request)
    if (!runtime) return
    const spaceId = await createExtractionSpace(request, runtime.admin, 'test-config')
    await openExtractionWorkbench(page, runtime.admin, spaceId)

    const suffix = randomUUID().slice(0, 8)
    await stageFormalIngestion(page, [
      {
        name: 'fixed-config.md',
        mimeType: 'text/markdown',
        buffer: Buffer.from('# Fixed config\n\nThe Space keeps one processing contract.', 'utf8'),
        externalId: `acceptance/${suffix}/fixed-config`,
        title: `Fixed config ${suffix}`,
        authority: 80,
      },
    ])
    const ingestion = await submitFormalIngestion(page, spaceId)
    await waitForRunStatusOnPage(page, ingestion.id, 'SUCCEEDED')

    // 重新加载，确认正式摄取没有改变创建时已经固定的 Space 配置。
    await page.reload()
    await expect(page.getByLabel('选择多个源文件')).toBeEnabled({ timeout: 15_000 })
    const activeResponse = await apiRequest(
      request,
      runtime.admin.token,
      'GET',
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`,
    )
    await expectStatus(activeResponse, 200)
    const spaceConfigBefore = (await activeResponse.json()) as SpaceProcessingConfig

    await page.getByRole('button', { name: '查看 Space 配置' }).click()
    const activeForm = page.locator('form.document-processing-config-form')
    await expect(activeForm.getByLabel('最大 Token 数')).toBeDisabled()
    await activeForm.getByRole('button', { name: '关闭' }).click()

    const datasetId = await page.getByLabel('Golden Dataset').inputValue()
    const baselineResponsePromise = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-datasets/${datasetId}/runs`,
    )
    await page.getByRole('button', { name: '运行真实硬门禁' }).click()
    const baselineResponse = await baselineResponsePromise
    expect(baselineResponse.status()).toBe(202)
    const baseline = (await baselineResponse.json()) as IngestionRunView
    await waitForRunStatusOnPage(page, baseline.id, 'SUCCEEDED')
    const baselineGate = extractionRunPanel(page).locator('section.extraction-gate-report')
    await expect(
      baselineGate.locator('header').getByText('PASSED', { exact: true }),
    ).toBeVisible()
    await expect(baselineGate.locator('.extraction-gate-finding')).toHaveCount(0)

    let spaceConfigWrites = 0
    page.on('request', (candidate) => {
      if (
        candidate.method() === 'PUT' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/document-processing-config`
      ) {
        spaceConfigWrites += 1
      }
    })

    await page.getByRole('button', { name: '设置本次测试配置' }).click()
    const testConfigForm = page.getByRole('form', {
      name: '本次测试配置',
    })
    await expect(testConfigForm.getByLabel('application/pdf Parser')).toBeEnabled()
    await expect(testConfigForm.getByLabel('页码处理方式')).toBeEnabled()
    await expect(testConfigForm.getByLabel('Chunker Provider')).toBeEnabled()
    await expect(testConfigForm.getByLabel('Token Counter')).toBeEnabled()
    const maximumTokens = testConfigForm.getByLabel('最大 Token 数')
    const testMaximumTokens = Number(await maximumTokens.inputValue()) + 1
    expect(testMaximumTokens).toBeLessThanOrEqual(65_536)
    await maximumTokens.fill(String(testMaximumTokens))
    await testConfigForm.getByRole('button', {
      name: '用于本次测试',
    }).click()
    await expect(page.getByText('本次测试配置已启用')).toBeVisible()
    expect(spaceConfigWrites).toBe(0)

    const baselineSelect = page.getByLabel('Baseline Run（可选）')
    await expect(
      baselineSelect.locator(`option[value="${baseline.id}"]`),
    ).toHaveCount(1)
    await expect(
      baselineSelect.locator(`option[value="${ingestion.id}"]`),
    ).toHaveCount(0)
    await baselineSelect.selectOption(baseline.id)

    const testDatasetRequestPromise = page.waitForRequest(
      (candidate) =>
        candidate.method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-datasets/${datasetId}/runs`,
    )
    const testDatasetResponsePromise = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-datasets/${datasetId}/runs`,
    )
    await page.getByRole('button', { name: '运行真实硬门禁' }).click()
    const testDatasetRequest = await testDatasetRequestPromise
    const datasetPayload = testDatasetRequest.postDataJSON() as {
      baselineRunId: string
      testConfig: SpaceProcessingConfigBody
    }
    expect(datasetPayload.baselineRunId).toBe(baseline.id)
    expect(datasetPayload.testConfig.chunker.maximumTokens).toBe(
      testMaximumTokens,
    )
    expect(datasetPayload.testConfig).not.toHaveProperty('processingContract')
    const testDatasetResponse = await testDatasetResponsePromise
    expect(testDatasetResponse.status()).toBe(202)
    const testDataset =
      (await testDatasetResponse.json()) as IngestionRunView
    assertProcessingContract(testDataset.configSnapshot.processingContract)
    expect(testDataset.configSnapshot.normalizerContract).toBeTruthy()
    await waitForRunStatusOnPage(page, testDataset.id, 'SUCCEEDED')
    const candidateGate = extractionRunPanel(page).locator('section.extraction-gate-report')
    await expect(
      candidateGate.locator('header').getByText('PASSED', { exact: true }),
    ).toBeVisible()
    await expect(candidateGate.locator('.extraction-gate-finding')).toHaveCount(0)
    await expect(
      extractionRunPanel(page).locator('.extraction-config-comparison'),
    ).toContainText('处理版本总指纹')
    await expect(
      extractionRunPanel(page).locator('.extraction-config-table > div.changed'),
    ).not.toHaveCount(0)

    await page.getByLabel('选择多个源文件').setInputFiles({
      name: 'test-config-upload.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('The upload TEST_ONLY path uses the request configuration.', 'utf8'),
    })
    const uploadResponsePromise = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-runs`,
    )
    await page.getByRole('button', { name: '运行抽取测试' }).click()
    const uploadResponse = await uploadResponsePromise
    expect(uploadResponse.status()).toBe(202)
    const uploadRun = (await uploadResponse.json()) as IngestionRunView
    // Chromium 对带文件的 multipart 请求不保证向 Playwright 暴露 postData。
    // 以服务端固化的 Run 快照验证本次覆盖，避免把浏览器抓包细节当作业务契约。
    expect(uploadRun.configSnapshot.chunker.maximumTokens).toBe(
      testMaximumTokens,
    )
    assertProcessingContract(uploadRun.configSnapshot.processingContract)
    await waitForRunStatusOnPage(page, uploadRun.id, 'SUCCEEDED')

    const activeAfterResponse = await apiRequest(
      request,
      runtime.admin.token,
      'GET',
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`,
    )
    await expectStatus(activeAfterResponse, 200)
    const spaceConfigAfter = (await activeAfterResponse.json()) as SpaceProcessingConfig
    expect(spaceConfigBody(spaceConfigAfter)).toEqual(
      spaceConfigBody(spaceConfigBefore),
    )
    expect(spaceConfigAfter.version).toBe(spaceConfigBefore.version)
    expect(spaceConfigAfter.processingContract).toEqual(
      spaceConfigBefore.processingContract,
    )
    expect(spaceConfigWrites).toBe(0)

    await page.getByRole('button', { name: '清除本次覆盖', exact: true }).click()
    await expect(
      page.getByText(`测试使用 Space 配置 v${spaceConfigBefore.version}`),
    ).toBeVisible()
  })

  test('admin selects the pinned exact tokenizer for a real TEST_ONLY run', async ({
    request,
    page,
  }) => {
    const tokenizerId = env.E2E_EXACT_TOKENIZER_ID
    test.skip(!tokenizerId, '仅在固定本地 Tokenizer 验收部署中运行。')
    if (!tokenizerId) return
    const modelProfileId =
      env.E2E_EXACT_TOKENIZER_MODEL_PROFILE ?? 'acceptance/word-level@1'
    const runtime = await readyRuntime(request)
    if (!runtime) return
    const spaceId = await createExtractionSpace(
      request,
      runtime.admin,
      'tokenizer',
      (documentProcessingConfig, capabilities) => {
        expect(
          capabilities.availableTokenizers.find(
            (tokenizer) => tokenizer.id === tokenizerId,
          ),
        ).toMatchObject({
          exactModelTokens: true,
          modelProfileId,
          available: true,
          unavailableReason: null,
        })
        documentProcessingConfig.chunker.tokenizerId = tokenizerId
      },
    )

    const processingConfig = await apiRequest(
      request,
      runtime.admin.token,
      'GET',
      `/api/v1/spaces/${encodeURIComponent(spaceId)}/document-processing-config`,
    )
    await expectStatus(processingConfig, 200)
    const config = (await processingConfig.json()) as ExtractionProcessingCapability
    expect(config.availableTokenizers.find((tokenizer) => tokenizer.id === tokenizerId))
      .toMatchObject({
        exactModelTokens: true,
        modelProfileId,
        available: true,
        unavailableReason: null,
      })

    await openExtractionWorkbench(page, runtime.admin, spaceId)
    await page.getByRole('button', { name: '查看 Space 配置' }).click()
    const configForm = page.locator('form.document-processing-config-form')
    const tokenizerSelect = configForm.getByLabel('Token Counter')
    await expect(
      tokenizerSelect.locator('option').filter({ hasText: tokenizerId }),
    ).toHaveCount(1)
    await expect(tokenizerSelect).toBeDisabled()
    await expect(tokenizerSelect).toHaveValue(tokenizerId)
    await expect(configForm).toContainText(
      `已由运维固定绑定模型配置 ${modelProfileId}。`,
    )
    await configForm.getByRole('button', { name: '关闭' }).click()

    const tokenizerCard = page.locator('article.extraction-capability-card').filter({
      hasText: 'Tokenizer',
    })
    await expect(tokenizerCard).toContainText(tokenizerId)
    await expect(tokenizerCard).toContainText('模型精确')
    await expect(tokenizerCard).toContainText(`运维固定绑定 ${modelProfileId}`)

    await page.getByLabel('选择多个源文件').setInputFiles({
      name: 'exact-tokenizer.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('alpha beta gamma delta', 'utf8'),
    })
    const createResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-runs`,
    )
    await page.getByRole('button', { name: '运行抽取测试' }).click()
    const createdResponse = await createResponse
    expect(createdResponse.status()).toBe(202)
    const created = (await createdResponse.json()) as IngestionRunView
    await waitForRunStatusOnPage(page, created.id, 'SUCCEEDED')
    const result = await readExtractionRun(
      request,
      runtime.admin.token,
      spaceId,
      created.id,
    )
    expect(result).toMatchObject({
      mode: 'TEST_ONLY',
      status: 'SUCCEEDED',
      succeededItems: 1,
      failedItems: 0,
      configSnapshot: {
        chunker: { tokenizerId },
      },
    })
    await expect(
      extractionRunPanel(page)
        .locator('.extraction-item-entry')
        .filter({ hasText: 'exact-tokenizer.txt' })
        .getByText('SUCCEEDED', { exact: true }),
    ).toBeVisible()
  })

  test('admin cancels a real TEST_ONLY run from the page', async ({ request, page }) => {
    const runtime = await readyRuntime(request)
    if (!runtime) return
    const spaceId = await createExtractionSpace(request, runtime.admin, 'cancel')
    await openExtractionWorkbench(page, runtime.admin, spaceId)

    // 较大的单段落让 Worker 在真实 Chunk 边界上处理取消，不依赖前端伪造延迟。
    const cancellationText = 'Boundary preserving cancellation payload. '.repeat(20_000)
    const multiFileInput = page.getByLabel('选择多个源文件')
    await multiFileInput.setInputFiles([
      {
        name: 'cancel-one.md',
        mimeType: 'text/markdown',
        buffer: Buffer.from(`# Cancel one\n\n${cancellationText}`, 'utf8'),
      },
      {
        name: 'cancel-two.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from(`Cancel two. ${cancellationText}`, 'utf8'),
      },
    ])

    const createResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname ===
          `/api/v1/spaces/${spaceId}/extraction-runs`,
    )
    await page.getByRole('button', { name: '运行抽取测试' }).click()
    expect((await createResponse).status()).toBe(202)

    const detail = extractionRunPanel(page)
    const cancelResponse = page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        new URL(candidate.url()).pathname.endsWith('/cancel'),
    )
    await detail.getByRole('button', { name: '取消运行' }).click({ timeout: 5_000 })
    expect((await cancelResponse).status()).toBe(202)
    await expect(
      detail.locator('.extraction-run-overview').getByText('CANCELLED', { exact: true }),
    ).toBeVisible({ timeout: 60_000 })
    await expect(detail.locator('.extraction-item-entry')).toHaveCount(2)
    expect(
      await detail.locator('.extraction-item-entry').getByText('CANCELLED', {
        exact: true,
      }).count(),
    ).toBeGreaterThanOrEqual(1)
    await expect(detail.getByRole('button', { name: '取消运行' })).toHaveCount(0)
    await expect(detail.locator('.extraction-run-overview').getByText('NOT_EVALUATED', {
      exact: true,
    })).toBeVisible()
  })

  test('admin publishes, deduplicates, and protects formal ingestion identities', async ({
    request,
    page,
  }) => {
    const runtime = await readyRuntime(request)
    if (!runtime) return
    const spaceId = await createExtractionSpace(request, runtime.admin, 'ingest')
    await openExtractionWorkbench(page, runtime.admin, spaceId)

    const suffix = randomUUID().slice(0, 8)
    const sources: FormalIngestionFile[] = [
      {
        name: 'formal-policy.md',
        mimeType: 'text/markdown',
        buffer: Buffer.from(
          '# Formal policy\n\nThis governed policy keeps its original source span.',
          'utf8',
        ),
        externalId: `acceptance/${suffix}/policy`,
        title: `Formal policy ${suffix}`,
        authority: 93,
      },
      {
        name: 'formal-guide.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from(
          'This governed guide validates independent publication metadata.',
          'utf8',
        ),
        externalId: `acceptance/${suffix}/guide`,
        title: `Formal guide ${suffix}`,
        authority: 71,
      },
    ]

    await stageFormalIngestion(page, sources)
    const firstRun = await submitFormalIngestion(page, spaceId)
    await waitForRunStatusOnPage(page, firstRun.id, 'SUCCEEDED')
    const firstResult = await readExtractionRun(
      request,
      runtime.admin.token,
      spaceId,
      firstRun.id,
    )
    expect(firstResult).toMatchObject({
      mode: 'INGEST',
      status: 'SUCCEEDED',
      succeededItems: 2,
      skippedDuplicateItems: 0,
      failedItems: 0,
    })
    const published = new Map<string, IngestionRunItemView>()
    const detail = extractionRunPanel(page)
    for (const source of sources) {
      const item = firstResult.items.find((candidate) => candidate.fileName === source.name)
      expect(item).toMatchObject({
        externalId: source.externalId,
        title: source.title,
        authority: source.authority,
        status: 'SUCCEEDED',
        stage: 'COMPLETED',
        errorCode: null,
      })
      expect(item?.diagnostics).not.toBeNull()
      expect(item?.preview).not.toBeNull()
      expect(item?.documentId).toBeTruthy()
      expect(item?.revisionId).toBeTruthy()
      published.set(source.name, item!)

      const row = detail.locator('.extraction-item-entry').filter({ hasText: source.name })
      await assertFormalIdentityOnPage(row, source)
      await expect(row.getByText('SUCCEEDED', { exact: true })).toBeVisible()
      await expect(row.getByText('已发布', { exact: false })).toBeVisible()
      await expect(
        row.getByText('本次分层诊断（只含计数与耗时）', { exact: true }),
      ).toBeVisible()
      await expect(
        row.locator('details.extraction-artifact-preview summary'),
      ).toContainText('内容与边界预览')
    }
    await assertDownloadedSource(page, detail, sources[0]!)

    await stageFormalIngestion(page, sources)
    const duplicateRun = await submitFormalIngestion(page, spaceId)
    await waitForRunStatusOnPage(page, duplicateRun.id, 'SUCCEEDED')
    const duplicateResult = await readExtractionRun(
      request,
      runtime.admin.token,
      spaceId,
      duplicateRun.id,
    )
    expect(duplicateResult).toMatchObject({
      mode: 'INGEST',
      status: 'SUCCEEDED',
      succeededItems: 0,
      skippedDuplicateItems: 2,
      failedItems: 0,
    })
    for (const source of sources) {
      const duplicate = duplicateResult.items.find(
        (candidate) => candidate.fileName === source.name,
      )
      const original = published.get(source.name)
      expect(duplicate).toMatchObject({
        externalId: source.externalId,
        title: source.title,
        authority: source.authority,
        status: 'SKIPPED_DUPLICATE',
        stage: 'COMPLETED',
        errorCode: null,
        documentId: original?.documentId,
        revisionId: original?.revisionId,
      })
      const row = extractionRunPanel(page)
        .locator('.extraction-item-entry')
        .filter({ hasText: source.name })
      await assertFormalIdentityOnPage(row, source)
      await expect(row.getByText('SKIPPED_DUPLICATE', { exact: true })).toBeVisible()
      await expect(row.getByText('已发布', { exact: false })).toBeVisible()
    }

    const conflict: FormalIngestionFile = {
      ...sources[0]!,
      buffer: Buffer.from(
        '# Conflicting content\n\nThis body must never overwrite the active revision.',
        'utf8',
      ),
      title: `Conflicting title ${suffix}`,
      authority: 5,
    }
    await stageFormalIngestion(page, [conflict])
    const conflictRun = await submitFormalIngestion(page, spaceId)
    await waitForRunStatusOnPage(page, conflictRun.id, 'FAILED')
    const conflictResult = await readExtractionRun(
      request,
      runtime.admin.token,
      spaceId,
      conflictRun.id,
    )
    expect(conflictResult).toMatchObject({
      mode: 'INGEST',
      status: 'FAILED',
      errorCode: 'ITEM_FAILED',
      succeededItems: 0,
      skippedDuplicateItems: 0,
      failedItems: 1,
    })
    expect(conflictResult.items[0]).toMatchObject({
      externalId: conflict.externalId,
      title: conflict.title,
      authority: conflict.authority,
      status: 'FAILED',
      stage: 'PARSE',
      errorCode: 'EXTERNAL_ID_CONFLICT',
      documentId: null,
      revisionId: null,
    })
    const conflictRow = extractionRunPanel(page)
      .locator('.extraction-item-entry')
      .filter({ hasText: conflict.name })
    await assertFormalIdentityOnPage(conflictRow, conflict)
    await expect(conflictRow.getByText('EXTERNAL_ID_CONFLICT', { exact: true })).toBeVisible()
    await expect(
      extractionRunPanel(page).getByText('ITEM_FAILED', { exact: true }),
    ).toBeVisible()

    const documentsResponse = await apiRequest(
      request,
      runtime.admin.token,
      'GET',
      `/api/v1/admin/documents?spaceId=${encodeURIComponent(spaceId)}&status=ACTIVE&limit=25&offset=0`,
    )
    await expectStatus(documentsResponse, 200)
    const documents = (await documentsResponse.json()) as {
      items: Array<{
        id: string
        title: string
        authority: number
        activeRevisionId: string | null
      }>
    }
    expect(documents.items).toHaveLength(2)
    for (const source of sources) {
      const original = published.get(source.name)!
      expect(documents.items.find((document) => document.id === original.documentId)).toMatchObject({
        title: source.title,
        authority: source.authority,
        activeRevisionId: original.revisionId,
      })
      const revisionsResponse = await apiRequest(
        request,
        runtime.admin.token,
        'GET',
        `/api/v1/admin/documents/${original.documentId}/revisions`,
      )
      await expectStatus(revisionsResponse, 200)
      const revisions = (await revisionsResponse.json()) as Array<{
        revisionId: string
        active: boolean
      }>
      expect(revisions).toEqual([
        expect.objectContaining({ revisionId: original.revisionId, active: true }),
      ])
    }
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

type ParserCapability = {
  id: string
  canonicalMediaType: string
  available: boolean
  unavailableReason: string | null
  defaultSelection: boolean
}

type ExtractionProcessingCapability = {
  availableParsers: ParserCapability[]
  availableTokenizers: Array<{
    id: string
    exactModelTokens: boolean
    modelProfileId: string | null
    available: boolean
    unavailableReason: string | null
  }>
}

type SpaceProcessingConfigBody = {
  parserSelections: Array<{ mediaType: string; parserId: string }>
  cleaning: Record<string, string>
  chunker: {
    providerId: string
    tokenizerId: string
    minimumTokens: number
    targetTokens: number
    maximumTokens: number
    overlapTokens: number
    providerConfig: Record<string, unknown>
  }
}

type SpaceProcessingConfig = SpaceProcessingConfigBody & {
  version: number
  updatedAt: string
  updatedBy: string
  processingContract: ProcessingContract
  runtimeContractMatched: boolean
}

type ProcessingContract = {
  pipelineContract: string
  normalizerSchemaContract: string
  parserContracts: Record<string, string>
  cleanerContract: string
  chunkerContract: string
  fingerprint: string
}

type DocumentProcessingCapabilitiesResponse = ExtractionProcessingCapability & {
  defaultConfig: SpaceProcessingConfigBody
}

type FormalIngestionFile = {
  name: string
  mimeType: string
  buffer: Buffer
  externalId: string
  title: string
  authority: number
}

type IngestionRunItemView = {
  fileName: string
  externalId: string | null
  title: string | null
  authority: number | null
  status: string
  stage: string
  errorCode: string | null
  diagnostics: unknown | null
  preview: unknown | null
  documentId: string | null
  revisionId: string | null
}

type IngestionRunView = {
  id: string
  mode: string
  status: string
  errorCode: string | null
  totalItems: number
  succeededItems: number
  skippedDuplicateItems: number
  failedItems: number
  configSnapshot: {
    processingContract: ProcessingContract
    normalizerContract: string
    chunker: {
      tokenizerId: string
    }
  }
  items: IngestionRunItemView[]
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
  const datasetName = `E2E Evaluation ${suffix}`
  const wikiTitle = `E2E Wiki ${suffix}`
  const safeSuffix = suffix.replace(/[^a-zA-Z0-9]/g, '_')
  const retrievalFact = `E2E_ACCESS_BOUNDARY_${safeSuffix}`

  await createSpaceWithProcessingConfig(
    request,
    runtime.admin.token,
    spaceId,
    spaceName,
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

  // 投影是异步的；查询前必须等待两条检索通道完成。
  for (const projectionType of ['KEYWORD', 'VECTOR'] as const) {
    const projection = await waitForProjection(
      request,
      runtime.admin.token,
      document.documentId,
      projectionType,
    )
    expect(projection.status).toBe('SUCCEEDED')
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
  const processingConfig = await apiRequest(
    request,
    runtime.admin.token,
    'GET',
    `/api/v1/spaces/${encodeURIComponent(seed.spaceId)}/document-processing-config`,
  )
  await expectStatus(processingConfig, 200)
  const processingCapability = (await processingConfig.json()) as {
    updatedBy: string
    availableParsers: ParserCapability[]
    availableChunkers: Array<{
      id: string
      available: boolean
      unavailableReason: string | null
    }>
  }
  expect(processingCapability.availableParsers.map((parser) => parser.id)).toEqual(
    expect.arrayContaining([
      'markdown-structure',
      'plain-text',
      'html-structure',
      'pdfbox-page',
      'poi-docx-structure',
      'docling-serve-pdf',
      'docling-serve-docx',
    ]),
  )
  expect(processingCapability.updatedBy).toBeTruthy()
  assertParserCapabilityCatalog(processingCapability.availableParsers)
  expect(processingCapability.availableChunkers.map((chunker) => chunker.id)).toEqual(
    expect.arrayContaining(['STRUCTURAL', 'SEMANTIC_REFINEMENT']),
  )
  expect(processingCapability.availableChunkers.some(
    (chunker) => !chunker.available && Boolean(chunker.unavailableReason),
  )).toBe(true)

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
    (event) => event.routePattern === '/api/v1/documents/markdown',
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

async function createExtractionSpace(
  request: APIRequestContext,
  admin: Identity,
  purpose: string,
  configure?: (
    config: SpaceProcessingConfigBody,
    capabilities: DocumentProcessingCapabilitiesResponse,
  ) => void,
) {
  const suffix = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`
  const spaceId = `e2e-extraction-${purpose}-${suffix}`
  await createSpaceWithProcessingConfig(
    request,
    admin.token,
    spaceId,
    `E2E Extraction ${purpose} ${suffix}`,
    configure,
  )
  return spaceId
}

async function createSpaceWithProcessingConfig(
  request: APIRequestContext,
  token: string,
  spaceId: string,
  name: string,
  configure?: (
    config: SpaceProcessingConfigBody,
    capabilities: DocumentProcessingCapabilitiesResponse,
  ) => void,
) {
  const capabilityResponse = await apiRequest(
    request,
    token,
    'GET',
    '/api/v1/document-processing-capabilities',
  )
  await expectStatus(capabilityResponse, 200)
  const capabilities =
    (await capabilityResponse.json()) as DocumentProcessingCapabilitiesResponse
  const documentProcessingConfig = cloneProcessingConfig(
    capabilities.defaultConfig,
  )
  configure?.(documentProcessingConfig, capabilities)
  await expectStatus(
    await apiRequest(request, token, 'POST', '/api/v1/spaces', {
      spaceId,
      name,
      description: 'E2E extraction and retrieval knowledge space',
      documentProcessingConfig,
    }),
    204,
  )
}

function cloneProcessingConfig(
  value: SpaceProcessingConfigBody,
): SpaceProcessingConfigBody {
  return {
    parserSelections: value.parserSelections.map((selection) => ({ ...selection })),
    cleaning: { ...value.cleaning },
    chunker: {
      ...value.chunker,
      providerConfig: { ...value.chunker.providerConfig },
    },
  }
}

async function openExtractionWorkbench(
  page: Page,
  admin: Identity,
  spaceId: string,
) {
  await login(page, admin)
  await page.goto(`${settings.webUrl}/spaces/${encodeURIComponent(spaceId)}/extraction`)
  await expect(page).toHaveURL(
    new RegExp(`/spaces/${escapeRegex(spaceId)}/extraction$`),
  )
  await expect(
    page.getByRole('heading', { name: '数据抽取与正式摄取工作台' }),
  ).toBeVisible()
  await expect(page.getByLabel('选择多个源文件')).toBeEnabled()
}

function formalIngestionPanel(page: Page) {
  return page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: '正式多文件摄取', exact: true }),
  })
}

async function stageFormalIngestion(page: Page, sources: FormalIngestionFile[]) {
  const panel = formalIngestionPanel(page)
  const input = panel.getByLabel('选择多个正式摄取文件')
  await expect(input).toBeEnabled({ timeout: 15_000 })
  await input.setInputFiles(sources.map((source) => ({
    name: source.name,
    mimeType: source.mimeType,
    buffer: source.buffer,
  })))
  await expect(panel.locator('.ingestion-manifest-row')).toHaveCount(sources.length)
  for (const source of sources) {
    const row = panel.locator('.ingestion-manifest-row').filter({ hasText: source.name })
    await row.getByRole('textbox', { name: /稳定 externalId/ }).fill(source.externalId)
    await row.getByRole('textbox', { name: /检索标题/ }).fill(source.title)
    await row.getByRole('spinbutton', { name: /权威等级/ }).fill(
      String(source.authority),
    )
  }
}

async function submitFormalIngestion(page: Page, spaceId: string) {
  const createResponse = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      new URL(candidate.url()).pathname ===
        `/api/v1/spaces/${spaceId}/ingestion-runs`,
  )
  await formalIngestionPanel(page).getByRole('button', {
    name: '开始正式摄取',
  }).click()
  const response = await createResponse
  expect(response.status()).toBe(202)
  const run = (await response.json()) as IngestionRunView
  expect(run).toMatchObject({ mode: 'INGEST', totalItems: run.items.length })
  await expect(extractionRunPanel(page).locator('.panel-header p')).toContainText(run.id)
  return run
}

async function waitForRunStatusOnPage(
  page: Page,
  runId: string,
  status: 'SUCCEEDED' | 'FAILED' | 'CANCELLED',
) {
  const detail = extractionRunPanel(page)
  await expect(detail.locator('.panel-header p')).toContainText(runId)
  await expect(
    detail.locator('.extraction-run-overview').getByText(status, { exact: true }),
  ).toBeVisible({ timeout: 60_000 })
}

async function readExtractionRun(
  request: APIRequestContext,
  token: string,
  spaceId: string,
  runId: string,
) {
  const response = await apiRequest(
    request,
    token,
    'GET',
    `/api/v1/spaces/${encodeURIComponent(spaceId)}/extraction-runs/${encodeURIComponent(runId)}`,
  )
  await expectStatus(response, 200)
  return (await response.json()) as IngestionRunView
}

async function assertDownloadedSource(
  page: Page,
  detail: ReturnType<typeof extractionRunPanel>,
  source: FormalIngestionFile,
) {
  const item = detail.locator('.extraction-item-entry').filter({ hasText: source.name })
  const sourceResponse = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'GET' &&
      new URL(candidate.url()).pathname.endsWith('/source'),
  )
  const downloadEvent = page.waitForEvent('download')
  await item.getByRole('button', { name: '下载原件' }).click()
  const [response, download] = await Promise.all([sourceResponse, downloadEvent])
  expect(response.status()).toBe(200)
  expect(download.suggestedFilename()).toBe(source.name)
  const downloadedBytes: Buffer[] = []
  const stream = await download.createReadStream()
  for await (const chunk of stream) {
    downloadedBytes.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  }
  expect(Buffer.compare(Buffer.concat(downloadedBytes), source.buffer)).toBe(0)
  expect(await download.failure()).toBeNull()
}

async function assertFormalIdentityOnPage(
  row: ReturnType<Page['locator']>,
  source: FormalIngestionFile,
) {
  const identity = row.locator('.extraction-publication-identity')
  await expect(identity).toContainText(`稳定来源标识 ${source.externalId}`)
  await expect(identity).toContainText(`检索标题 ${source.title}`)
  await expect(identity).toContainText(`来源权威度 ${source.authority}`)
}

function extractionRunPanel(page: Page) {
  return page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: 'Run 详情', exact: true }),
  })
}

function spaceConfigBody(value: SpaceProcessingConfig): SpaceProcessingConfigBody {
  return normalizedSpaceConfigBody(value)
}

function normalizedSpaceConfigBody(
  value: SpaceProcessingConfigBody,
): SpaceProcessingConfigBody {
  return {
    parserSelections: [...value.parserSelections]
      .map((selection) => ({ ...selection }))
      .sort((left, right) => left.mediaType.localeCompare(right.mediaType)),
    cleaning: { ...value.cleaning },
    chunker: {
      ...value.chunker,
      providerConfig: { ...value.chunker.providerConfig },
    },
  }
}

function assertProcessingContract(
  value: ProcessingContract,
  expectedMediaTypes?: string[],
) {
  expect(value.pipelineContract).toBeTruthy()
  expect(value.normalizerSchemaContract).toBeTruthy()
  expect(value.cleanerContract).toBeTruthy()
  expect(value.chunkerContract).toBeTruthy()
  expect(value.fingerprint).toMatch(/^[0-9a-f]{64}$/)
  const mediaTypes = Object.keys(value.parserContracts).sort()
  expect(mediaTypes.length).toBeGreaterThan(0)
  expect(Object.values(value.parserContracts).every(Boolean)).toBe(true)
  if (expectedMediaTypes) {
    expect(mediaTypes).toEqual([...expectedMediaTypes].sort())
  }
}

function assertParserCapabilityCatalog(parsers: ParserCapability[]) {
  expect(parsers.every((parser) => Boolean(parser.canonicalMediaType))).toBe(true)
  const parsersByMediaType = new Map<string, ParserCapability[]>()
  for (const parser of parsers) {
    const candidates = parsersByMediaType.get(parser.canonicalMediaType) ?? []
    candidates.push(parser)
    parsersByMediaType.set(parser.canonicalMediaType, candidates)
  }
  for (const [mediaType, candidates] of parsersByMediaType) {
    expect(
      candidates.filter((parser) => parser.defaultSelection),
      `${mediaType} must have exactly one default Parser`,
    ).toHaveLength(1)
  }

  expect(parsers.find((parser) => parser.id === 'docling-serve-pdf')).toMatchObject({
    canonicalMediaType: 'application/pdf',
    available: true,
    defaultSelection: false,
  })
  expect(parsers.find((parser) => parser.id === 'docling-serve-docx')).toMatchObject({
    canonicalMediaType:
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    available: false,
    unavailableReason: 'DOCLING_DOCX_PARSER_DISABLED',
    defaultSelection: false,
  })
}

async function apiRequest(
  request: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
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
