import { FlaskConical, Play, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import type { ExtractionDataset, ExtractionRunSummary } from '../../lib/api'
import { ErrorState, LoadingState, Panel } from '../State'

/** 选择受版本控制 Dataset 和同 Space Baseline，创建真实硬门禁运行。 */
export function ExtractionExperimentPanel({
  datasets,
  runs,
  parserMediaTypes,
  testConfigEnabled,
  spaceConfigVersion,
  pending,
  error,
  running,
  runError,
  disabled,
  onRun,
}: {
  datasets: ExtractionDataset[] | undefined
  runs: ExtractionRunSummary[] | undefined
  parserMediaTypes: string[]
  testConfigEnabled: boolean
  spaceConfigVersion: number | undefined
  pending: boolean
  error: unknown
  running: boolean
  runError: unknown
  disabled: boolean
  onRun: (datasetId: string, language: string, baselineRunId?: string) => void
}) {
  const [datasetId, setDatasetId] = useState('')
  const [language, setLanguage] = useState('zh-CN')
  const [baselineRunId, setBaselineRunId] = useState('')
  const effectiveDatasetId = datasetId || datasets?.[0]?.id || ''
  const selected = datasets?.find(
    (dataset) => dataset.id === effectiveDatasetId,
  )
  const configuredMediaTypes = new Set(
    parserMediaTypes.map((mediaType) => mediaType.toLowerCase()),
  )
  const unsupportedDatasetMediaTypes = [
    ...new Set(
      selected?.cases
        .map((value) => value.mediaType.toLowerCase())
        .filter((mediaType) => !configuredMediaTypes.has(mediaType)) ?? [],
    ),
  ].sort()
  const datasetSupported = unsupportedDatasetMediaTypes.length === 0
  const baselines =
    runs?.filter(
      (run) =>
        run.mode === 'TEST_ONLY' &&
        run.status === 'SUCCEEDED' &&
        run.datasetId === effectiveDatasetId &&
        (run.gateStatus === 'PASSED' || run.gateStatus === 'FAILED'),
    ) ?? []
  const effectiveBaselineRunId = baselines.some(
    (run) => run.id === baselineRunId,
  )
    ? baselineRunId
    : ''

  return (
    <Panel
      title="Dataset 验收实验"
      description="服务端 Golden Sources 会走与上传文件相同的 OSS → Parse → Clean → Chunk 链路。"
    >
      {pending && <LoadingState label="正在加载 Extraction Dataset" />}
      {Boolean(error) && <ErrorState error={error} />}
      {datasets && (
        <form
          className="extraction-experiment-form"
          onSubmit={(event) => {
            event.preventDefault()
            if (
              effectiveDatasetId &&
              language.trim() &&
              datasetSupported &&
              !disabled &&
              !running
            ) {
              onRun(
                effectiveDatasetId,
                language.trim(),
                effectiveBaselineRunId || undefined,
              )
            }
          }}
        >
          <div className="extraction-experiment-fields">
            <label>
              Golden Dataset
              <select
                value={effectiveDatasetId}
                disabled={disabled || running}
                onChange={(event) => {
                  setDatasetId(event.target.value)
                  setBaselineRunId('')
                }}
              >
                {datasets.map((dataset) => (
                  <option key={dataset.id} value={dataset.id}>
                    {dataset.id} · {dataset.caseCount} Cases
                  </option>
                ))}
              </select>
            </label>
            <label>
              文档语言
              <input
                value={language}
                maxLength={32}
                disabled={disabled || running}
                onChange={(event) => setLanguage(event.target.value)}
              />
            </label>
            <label>
              Baseline Run（可选）
              <select
                value={effectiveBaselineRunId}
                disabled={disabled || running}
                onChange={(event) => setBaselineRunId(event.target.value)}
              >
                <option value="">不比较历史配置</option>
                {baselines.map((run) => (
                  <option key={run.id} value={run.id}>
                    {run.id.slice(0, 8)} · {run.gateStatus} ·{' '}
                    {shortFingerprint(run.configFingerprint)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {selected && (
            <details className="extraction-dataset-cases">
              <summary>
                <FlaskConical size={14} /> 查看 {selected.caseCount} 个受治理 Case
              </summary>
              {selected.cases.map((value) => (
                <div key={value.id}>
                  <strong>{value.id}</strong>
                  <span>{value.description}</span>
                  <small>
                    {value.sourceName} · {value.mediaType} · {value.license} ·{' '}
                    {value.reviewedAt}
                  </small>
                </div>
              ))}
            </details>
          )}
          {!datasetSupported && (
            <div className="processing-config-validation" role="alert">
              <strong>当前配置不能执行该 Dataset</strong>
              <span>
                Dataset 包含未在本次配置中显式选择的格式：
                {unsupportedDatasetMediaTypes.join('、')}。请使用包含这些 Parser
                映射的配置创建新 Space。
              </span>
            </div>
          )}
          <div className="extraction-experiment-submit">
            <span>
              <ShieldCheck size={16} />
              {testConfigEnabled
                ? `本次使用页面测试配置（基于 Space v${spaceConfigVersion ?? '—'}）。`
                : `本次使用 Space 配置 v${spaceConfigVersion ?? '—'}。`}{' '}
              Run 成功与 Gate 通过会分别记录。
            </span>
            <button
              className="primary-button"
              disabled={
                disabled ||
                running ||
                !datasetSupported ||
                !effectiveDatasetId ||
                !language.trim()
              }
            >
              <Play size={15} />
              {running ? '创建验收 Run…' : '运行真实硬门禁'}
            </button>
          </div>
          {Boolean(runError) && <ErrorState error={runError} />}
        </form>
      )}
    </Panel>
  )
}

function shortFingerprint(value: string) {
  return value.length > 12 ? `${value.slice(0, 12)}…` : value
}
