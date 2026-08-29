import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, FlaskConical, RotateCcw, SlidersHorizontal } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { SpaceDocumentProcessingConfigDialog } from '../components/SpaceDocumentProcessingConfigDialog'
import { ErrorState, LoadingState } from '../components/State'
import type { DocumentProcessingConfigDraft } from '../components/document-processing/document-processing-config-model'
import {
  toDocumentProcessingConfigDraft,
  validateDocumentProcessingConfigDraft,
} from '../components/document-processing/document-processing-config-model'
import { ExtractionAcceptanceGates } from '../components/extraction/ExtractionAcceptanceGates'
import { ExtractionCapabilitySummary } from '../components/extraction/ExtractionCapabilitySummary'
import { ExtractionExperimentPanel } from '../components/extraction/ExtractionExperimentPanel'
import { ExtractionRunDetail } from '../components/extraction/ExtractionRunDetail'
import { ExtractionRunList } from '../components/extraction/ExtractionRunList'
import { ExtractionTestConfigDialog } from '../components/extraction/ExtractionTestConfigDialog'
import { MultiFileExtractionForm } from '../components/extraction/MultiFileExtractionForm'
import {
  MultiFileIngestionForm,
  type IngestionUploadEntry,
} from '../components/extraction/MultiFileIngestionForm'
import type {
  AvailableParser,
  ExtractionRunStatus,
  SpaceParserSelection,
} from '../lib/api'
import { useApi } from '../lib/use-api'

const activeRunStatuses = new Set<ExtractionRunStatus>([
  'QUEUED',
  'RUNNING',
  'CANCEL_REQUESTED',
])

type TestConfigState = {
  spaceId: string
  spaceConfigVersion: number
  draft: DocumentProcessingConfigDraft
}

/** Space 维度的数据抽取工作台，配置摘要、运行和详情均读取真实后端契约。 */
export function SpaceExtractionPage() {
  const { spaceId = '' } = useParams()
  const api = useApi()
  const client = useQueryClient()
  const [files, setFiles] = useState<File[]>([])
  const [ingestionEntries, setIngestionEntries] = useState<IngestionUploadEntry[]>([])
  const [selectedRunId, setSelectedRunId] = useState<string>()
  const [configuring, setConfiguring] = useState(false)
  const [testConfiguring, setTestConfiguring] = useState(false)
  const [testConfigState, setTestConfigState] = useState<TestConfigState>()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const space = spaces.data?.find((candidate) => candidate.id === spaceId)
  const enabled = Boolean(spaceId && space)
  const config = useQuery({
    queryKey: ['space-document-processing-config', spaceId],
    queryFn: () => api.spaceDocumentProcessingConfig(spaceId),
    enabled,
  })
  const spaceConfigDraft = config.data
    ? toDocumentProcessingConfigDraft(config.data)
    : undefined
  const testConfigDraft =
    config.data &&
    testConfigState?.spaceId === spaceId &&
    testConfigState.spaceConfigVersion === config.data.version
      ? testConfigState.draft
      : undefined
  const spaceConfigErrors =
    config.data && spaceConfigDraft
      ? validateDocumentProcessingConfigDraft(config.data, spaceConfigDraft)
      : []
  const effectiveTestConfigDraft = testConfigDraft ?? spaceConfigDraft
  const testConfigErrors =
    config.data && effectiveTestConfigDraft
      ? validateDocumentProcessingConfigDraft(
          config.data,
          effectiveTestConfigDraft,
        )
      : []
  const spaceConfigExecutable = Boolean(
    config.data && spaceConfigErrors.length === 0,
  )
  const testConfigExecutable = Boolean(
    config.data && testConfigErrors.length === 0,
  )
  const runs = useQuery({
    queryKey: ['extraction-runs', spaceId],
    queryFn: () => api.extractionRuns(spaceId),
    enabled,
    refetchInterval: (query) =>
      query.state.data?.some((run) => activeRunStatuses.has(run.status))
        ? 2_000
        : false,
  })
  const datasets = useQuery({
    queryKey: ['extraction-datasets', spaceId],
    queryFn: () => api.extractionDatasets(spaceId),
    enabled,
  })
  const effectiveRunId = selectedRunId || runs.data?.[0]?.id
  const runDetail = useQuery({
    queryKey: ['extraction-run', spaceId, effectiveRunId],
    queryFn: () => api.extractionRun(spaceId, effectiveRunId!),
    enabled: enabled && Boolean(effectiveRunId),
    refetchInterval: (query) =>
      query.state.data && activeRunStatuses.has(query.state.data.status)
        ? 1_500
        : false,
  })
  const baselineRunId = runDetail.data?.baselineRunId
  const baselineDetail = useQuery({
    queryKey: ['extraction-run', spaceId, baselineRunId],
    queryFn: () => api.extractionRun(spaceId, baselineRunId!),
    enabled: enabled && Boolean(baselineRunId),
  })
  const startRun = useMutation({
    mutationFn: ({ files: selectedFiles, language }: { files: File[]; language: string }) =>
      api.startExtractionRun(
        spaceId,
        selectedFiles,
        language,
        undefined,
        undefined,
        testConfigDraft,
      ),
    onSuccess: async (run) => {
      setFiles([])
      setSelectedRunId(run.id)
      client.setQueryData(['extraction-run', spaceId, run.id], run)
      await client.invalidateQueries({ queryKey: ['extraction-runs', spaceId] })
    },
  })
  const startIngestion = useMutation({
    mutationFn: ({
      entries,
      language,
    }: {
      entries: IngestionUploadEntry[]
      language: string
    }) =>
      api.startIngestionRun(
        spaceId,
        entries.map((entry) => entry.file),
        entries.map(({ externalId, title, authority }) => ({
          externalId,
          title,
          authority,
        })),
        language,
      ),
    onSuccess: async (run) => {
      setIngestionEntries([])
      setSelectedRunId(run.id)
      client.setQueryData(['extraction-run', spaceId, run.id], run)
      await client.invalidateQueries({ queryKey: ['extraction-runs', spaceId] })
    },
  })
  const cancelRun = useMutation({
    mutationFn: (runId: string) => api.cancelExtractionRun(spaceId, runId),
    onSuccess: async (run) => {
      client.setQueryData(['extraction-run', spaceId, run.id], run)
      await client.invalidateQueries({ queryKey: ['extraction-runs', spaceId] })
    },
  })
  const startDatasetRun = useMutation({
    mutationFn: ({
      datasetId,
      language,
      baselineRunId,
    }: {
      datasetId: string
      language: string
      baselineRunId?: string
    }) =>
      api.startExtractionDatasetRun(
        spaceId,
        datasetId,
        language,
        baselineRunId,
        testConfigDraft,
      ),
    onSuccess: async (run) => {
      setSelectedRunId(run.id)
      client.setQueryData(['extraction-run', spaceId, run.id], run)
      await client.invalidateQueries({ queryKey: ['extraction-runs', spaceId] })
    },
  })

  if (spaces.isPending) return <LoadingState label="正在加载知识空间" />
  if (spaces.error) return <ErrorState error={spaces.error} />
  if (!space) {
    return <ErrorState error={new Error('目标知识空间不存在或已停用')} />
  }

  const spaceAcceptedExtensions = selectedParserExtensions(
    config.data?.availableParsers ?? [],
    spaceConfigDraft?.parserSelections ?? [],
  )
  const testAcceptedExtensions = selectedParserExtensions(
    config.data?.availableParsers ?? [],
    effectiveTestConfigDraft?.parserSelections ?? [],
  )
  const testParserMediaTypes =
    effectiveTestConfigDraft?.parserSelections.map((selection) =>
      selection.mediaType.toLowerCase(),
    ) ?? []
  const hasActiveRun = runs.data?.some((run) => activeRunStatuses.has(run.status)) ?? false

  return (
    <div className="page-stack">
      <div className="page-intro extraction-page-intro">
        <div>
          <span className="eyebrow">EXTRACTION WORKBENCH · {space.id}</span>
          <h2>数据抽取与正式摄取工作台</h2>
          <p>
            {space.name} · 抽取测试可用本次请求参数覆盖 Space 配置；
            正式摄取始终使用创建时固定的处理配置。
          </p>
        </div>
        <Link className="primary-button" to="/spaces">
          <ArrowLeft size={16} />
          返回知识空间
        </Link>
      </div>

      {config.isPending && <LoadingState label="正在加载抽取处理配置" />}
      {config.error && <ErrorState error={config.error} />}
      {config.data && (
        <ExtractionCapabilitySummary
          config={config.data}
          onConfigure={() => setConfiguring(true)}
        />
      )}

      {config.data && spaceConfigErrors.length > 0 && (
        <div
          aria-label="Space 固定配置不可执行"
          className="processing-config-validation"
          role="alert"
        >
          <strong>Space 固定配置当前不可执行</strong>
          <span>
            正式摄取已停用；请使用可执行配置创建新的 Space。
            {testConfigDraft && testConfigErrors.length === 0
              ? ' 当前有效的本次测试配置仍可用于 TEST_ONLY 与 Dataset 实验。'
              : ' 你仍可设置有效的本次测试配置继续 TEST_ONLY 与 Dataset 实验。'}
          </span>
          {spaceConfigErrors.map((message) => (
            <span key={message}>{message}</span>
          ))}
        </div>
      )}

      {config.data && testConfigDraft && testConfigErrors.length > 0 && (
        <div
          aria-label="本次测试配置不可执行"
          className="processing-config-validation"
          role="alert"
        >
          <strong>本次测试配置当前不可执行</strong>
          <span>TEST_ONLY 与 Dataset 实验已停用，请重新调整或清除本次覆盖。</span>
          {testConfigErrors.map((message) => (
            <span key={message}>{message}</span>
          ))}
        </div>
      )}

      {config.data && (
        <section
          aria-label="本次测试配置状态"
          className={`extraction-test-config-state ${testConfigDraft ? 'active' : ''}`}
        >
          <FlaskConical size={19} />
          <div>
            <strong>
              {testConfigDraft
                ? '本次测试配置已启用'
                : `测试使用 Space 配置 v${config.data.version}`}
            </strong>
            <span>
              {testConfigDraft
                ? `配置基于 Space v${config.data.version} 调整，只随多文件抽取测试和 Dataset 实验发送。`
                : '未设置请求级覆盖；两个测试入口会使用当前 Space 配置。'}
              {' '}正式摄取不会使用测试覆盖参数。
            </span>
          </div>
          <div className="extraction-test-config-actions">
            {testConfigDraft && (
              <button type="button" onClick={() => setTestConfigState(undefined)}>
                <RotateCcw size={14} />
                清除本次覆盖
              </button>
            )}
            <button
              className="primary-button"
              type="button"
              onClick={() => setTestConfiguring(true)}
            >
              <SlidersHorizontal size={14} />
              {testConfigDraft ? '继续调整测试配置' : '设置本次测试配置'}
            </button>
          </div>
        </section>
      )}

      <div className="extraction-workbench-grid">
        <div className="extraction-workbench-column">
          <MultiFileIngestionForm
            entries={ingestionEntries}
            acceptedExtensions={spaceAcceptedExtensions}
            disabled={
              !spaceConfigExecutable || startIngestion.isPending || hasActiveRun
            }
            pending={startIngestion.isPending}
            error={startIngestion.error}
            onEntriesChange={setIngestionEntries}
            onSubmit={(entries, language) =>
              startIngestion.mutate({ entries, language })
            }
          />
          <MultiFileExtractionForm
            files={files}
            acceptedExtensions={testAcceptedExtensions}
            disabled={
              !testConfigExecutable || startRun.isPending || hasActiveRun
            }
            pending={startRun.isPending}
            error={startRun.error}
            onFilesChange={setFiles}
            onSubmit={(selectedFiles, language) =>
              startRun.mutate({ files: selectedFiles, language })
            }
          />
          <ExtractionExperimentPanel
            datasets={datasets.data}
            runs={runs.data}
            parserMediaTypes={testParserMediaTypes}
            testConfigEnabled={Boolean(testConfigDraft)}
            spaceConfigVersion={config.data?.version}
            pending={datasets.isPending}
            error={datasets.error}
            running={startDatasetRun.isPending}
            runError={startDatasetRun.error}
            disabled={!testConfigExecutable || hasActiveRun}
            onRun={(datasetId, language, baselineRunId) =>
              startDatasetRun.mutate({ datasetId, language, baselineRunId })
            }
          />
          <ExtractionAcceptanceGates />
        </div>
        <ExtractionRunList
          runs={runs.data}
          selectedRunId={effectiveRunId}
          pending={runs.isPending}
          error={runs.error}
          onSelect={setSelectedRunId}
        />
      </div>

      {effectiveRunId ? (
        <ExtractionRunDetail
          run={runDetail.data}
          baseline={baselineDetail.data}
          baselinePending={baselineDetail.isPending && Boolean(baselineRunId)}
          baselineError={baselineDetail.error}
          pending={runDetail.isPending}
          error={runDetail.error}
          cancelling={cancelRun.isPending}
          cancelError={cancelRun.error}
          onCancel={() => cancelRun.mutate(effectiveRunId)}
          onDownloadSource={async (item) => {
            const blob = await api.extractionRunSource(
              spaceId,
              effectiveRunId,
              item.id,
            )
            downloadBlob(blob, item.fileName)
          }}
        />
      ) : (
        <div className="extraction-no-detail">
          <FlaskConical size={20} />
          <span>运行抽取测试后，这里会展示逐文件阶段耗时和产物数量。</span>
        </div>
      )}

      {configuring && (
        <SpaceDocumentProcessingConfigDialog
          space={space}
          onClose={() => setConfiguring(false)}
        />
      )}
      {testConfiguring && config.data && (
        <ExtractionTestConfigDialog
          config={config.data}
          initialDraft={
            testConfigDraft ?? toDocumentProcessingConfigDraft(config.data)
          }
          onApply={(draft) => {
            setTestConfigState(
              draft
                ? {
                    spaceId,
                    spaceConfigVersion: config.data.version,
                    draft,
                  }
                : undefined,
            )
            setTestConfiguring(false)
          }}
          onClose={() => setTestConfiguring(false)}
        />
      )}
    </div>
  )
}

function downloadBlob(blob: Blob, originalFileName: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = originalFileName.replace(/[\\/]/g, '_') || '源文件'
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** 只向文件选择器暴露当前入口明确选择且仍可用的 Parser 扩展名。 */
function selectedParserExtensions(
  parsers: AvailableParser[],
  selections: SpaceParserSelection[],
) {
  const selected = new Set(
    selections.map(
      (selection) =>
        `${selection.mediaType.toLowerCase()}\u0000${selection.parserId}`,
    ),
  )
  return [
    ...new Set(
      parsers
        .filter(
          (parser) =>
            parser.available &&
            selected.has(
              `${parser.canonicalMediaType.toLowerCase()}\u0000${parser.id}`,
            ),
        )
        .flatMap((parser) => parser.extensions),
    ),
  ].sort()
}
