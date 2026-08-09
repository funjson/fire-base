import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRight,
  CheckCircle2,
  FlaskConical,
  Play,
  Plus,
  XCircle,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type PropsWithChildren,
} from 'react'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import type { EvaluationCaseInput, EvaluationRun } from '../lib/api'
import { useApi } from '../lib/use-api'

export function EvaluationsPage() {
  const api = useApi()
  const client = useQueryClient()
  const [datasetId, setDatasetId] = useState('')
  const [creatingDataset, setCreatingDataset] = useState(false)
  const [creatingCase, setCreatingCase] = useState(false)
  const [selectedRun, setSelectedRun] = useState<{
    datasetId: string
    runId: string
  }>()
  const [runTopK, setRunTopK] = useState('8')
  const datasets = useQuery({ queryKey: ['datasets'], queryFn: api.datasets })
  const activeDatasetId = datasetId || datasets.data?.[0]?.id || ''
  const activeDatasetIdRef = useRef(activeDatasetId)
  useEffect(() => {
    activeDatasetIdRef.current = activeDatasetId
  }, [activeDatasetId])
  const selectedRunId =
    selectedRun?.datasetId === activeDatasetId ? selectedRun.runId : undefined
  const cases = useQuery({
    queryKey: ['evaluation-cases', activeDatasetId],
    queryFn: () => api.cases(activeDatasetId),
    enabled: Boolean(activeDatasetId),
  })
  const runs = useQuery({
    queryKey: ['evaluation-runs', activeDatasetId],
    queryFn: () => api.runs(activeDatasetId),
    enabled: Boolean(activeDatasetId),
    refetchInterval: (query) =>
      query.state.data?.some((run) => run.status === 'RUNNING') ? 3_000 : false,
  })
  const runDetail = useQuery({
    queryKey: ['evaluation-run', selectedRunId],
    queryFn: () => api.run(selectedRunId!),
    enabled: Boolean(selectedRunId),
    refetchInterval: (query) =>
      query.state.data?.status === 'RUNNING' ? 2_000 : false,
  })
  const createDataset = useMutation({
    mutationFn: api.createDataset,
    onSuccess: async (value) => {
      setCreatingDataset(false)
      setDatasetId(value.id)
      await client.invalidateQueries({ queryKey: ['datasets'] })
    },
  })
  const createCase = useMutation({
    mutationFn: (body: Parameters<typeof api.createCase>[1]) =>
      api.createCase(activeDatasetId, body),
    onSuccess: async () => {
      setCreatingCase(false)
      await client.invalidateQueries({
        queryKey: ['evaluation-cases', activeDatasetId],
      })
      await client.invalidateQueries({ queryKey: ['datasets'] })
    },
  })
  const startRun = useMutation({
    mutationFn: ({ datasetId, topK }: { datasetId: string; topK: number }) =>
      api.startRun(datasetId, topK),
    onSuccess: async (value, variables) => {
      if (activeDatasetIdRef.current === variables.datasetId) {
        setSelectedRun({ datasetId: value.datasetId, runId: value.id })
      }
      await client.invalidateQueries({
        queryKey: ['evaluation-runs', variables.datasetId],
      })
    },
  })

  if (datasets.isPending) return <LoadingState />
  if (datasets.error) return <ErrorState error={datasets.error} />

  const selectedDataset = datasets.data.find(
    (item) => item.id === activeDatasetId,
  )
  const parsedRunTopK = Number(runTopK)
  const validRunTopK =
    Number.isInteger(parsedRunTopK) && parsedRunTopK >= 1 && parsedRunTopK <= 100
  const runMutationBelongsToActive =
    startRun.variables?.datasetId === activeDatasetId

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">QUALITY ENGINEERING</span>
          <h2>用可复现实验持续提升检索质量</h2>
          <p>数据集、运行配置、逐案例结果和检索 Trace 保持关联。</p>
        </div>
        <button className="primary-button" onClick={() => setCreatingDataset(true)}>
          <Plus size={17} />
          新建数据集
        </button>
      </div>

      {!datasets.data.length ? (
        <EmptyState
          title="还没有评测数据集"
          description="创建数据集并添加真实业务问题，才能对检索变化做回归判断。"
          action={
            <button
              className="primary-button"
              onClick={() => setCreatingDataset(true)}
            >
              创建数据集
            </button>
          }
        />
      ) : (
        <div className="evaluation-layout">
          <aside className="dataset-list">
            <div className="aside-title">数据集</div>
            {datasets.data.map((dataset) => (
              <button
                key={dataset.id}
                className={dataset.id === activeDatasetId ? 'active' : ''}
                onClick={() => {
                  setDatasetId(dataset.id)
                  setSelectedRun(undefined)
                }}
              >
                <FlaskConical size={17} />
                <div>
                  <strong>{dataset.name}</strong>
                  <span>
                    {dataset.caseCount} Cases · {dataset.runCount} Runs
                  </span>
                </div>
                <ArrowRight size={15} />
              </button>
            ))}
          </aside>

          <div className="evaluation-main">
            <Panel
              title={selectedDataset?.name}
              description={selectedDataset?.description || '未填写说明'}
              action={
                <div className="button-row">
                  <button onClick={() => setCreatingCase(true)}>
                    <Plus size={16} /> 添加 Case
                  </button>
                  <label className="run-top-k">
                    Top K
                    <input
                      aria-label="本次评测 Top K"
                      type="number"
                      min={1}
                      max={100}
                      value={runTopK}
                      onChange={(event) => setRunTopK(event.target.value)}
                    />
                  </label>
                  <button
                    className="primary-button"
                    disabled={
                      !cases.data?.length || startRun.isPending || !validRunTopK
                    }
                    onClick={() =>
                      startRun.mutate({
                        datasetId: activeDatasetId,
                        topK: parsedRunTopK,
                      })
                    }
                  >
                    <Play size={16} />
                    运行评测
                  </button>
                </div>
              }
            >
              <div className="evaluation-stat-row">
                <EvalStat label="Cases" value={selectedDataset?.caseCount ?? 0} />
                <EvalStat label="Runs" value={selectedDataset?.runCount ?? 0} />
                <EvalStat
                  label="Latest MRR"
                  value={percentage(metric(runs.data?.[0], 'mrr'))}
                />
                <EvalStat
                  label="Latest nDCG"
                  value={percentage(metric(runs.data?.[0], 'ndcgAtK'))}
                />
              </div>
            </Panel>

            <div className="evaluation-split">
              <Panel title="测试案例" description="真实查询与相关性标注">
                {cases.isPending && <LoadingState label="正在加载测试案例" />}
                {cases.error && <ErrorState error={cases.error} />}
                <div className="case-list">
                  {cases.data?.map((item) => (
                    <div className="case-row" key={item.id}>
                      <div className="case-index">
                        {String(cases.data.indexOf(item) + 1).padStart(2, '0')}
                      </div>
                      <div>
                        <strong>{item.query}</strong>
                        <span>
                          {item.spaceIds.join(', ')} · {item.expectedDocuments.length}{' '}
                          个目标文档
                        </span>
                      </div>
                    </div>
                  ))}
                  {!cases.isPending && !cases.error && !cases.data?.length && (
                    <EmptyState
                      title="数据集是空的"
                      description="至少添加一个带目标文档或 Chunk 的测试案例。"
                    />
                  )}
                </div>
              </Panel>

              <Panel title="实验运行" description="选择运行查看逐案例详情">
                {runs.isPending && <LoadingState label="正在加载评测运行" />}
                {runs.error && <ErrorState error={runs.error} />}
                <div className="run-list">
                  {runs.data?.map((run) => (
                    <button
                      key={run.id}
                      className={selectedRunId === run.id ? 'active' : ''}
                      onClick={() =>
                        setSelectedRun({ datasetId: activeDatasetId, runId: run.id })
                      }
                    >
                      <div>
                        <StatusBadge value={run.status} />
                        <strong>{formatTime(run.startedAt)}</strong>
                      </div>
                      <span>
                        Top K {configurationNumber(run, 'topK') ?? '—'} · MRR{' '}
                        {percentage(metric(run, 'mrr'))} · 失败 {run.failedCaseCount}
                      </span>
                    </button>
                  ))}
                  {!runs.isPending && !runs.error && !runs.data?.length && (
                    <div className="inline-empty">尚未运行评测</div>
                  )}
                </div>
              </Panel>
            </div>

            {runMutationBelongsToActive && startRun.error && (
              <ErrorState error={startRun.error} />
            )}

            {selectedRunId && (
              <Panel title="运行详情" description={`Run ${selectedRunId.slice(0, 8)}`}>
                {runDetail.isPending && <LoadingState />}
                {runDetail.error && <ErrorState error={runDetail.error} />}
                {runDetail.data && <RunDetail run={runDetail.data} />}
              </Panel>
            )}
          </div>
        </div>
      )}

      {creatingDataset && (
        <Modal title="新建评测数据集" onClose={() => setCreatingDataset(false)}>
          <form
            className="form-stack"
            onSubmit={(event) => {
              event.preventDefault()
              const data = new FormData(event.currentTarget)
              createDataset.mutate({
                name: String(data.get('name')).trim(),
                description: String(data.get('description')).trim(),
              })
            }}
          >
            <label>
              数据集名称
              <input name="name" required placeholder="研发知识库核心问题" />
            </label>
            <label>
              说明
              <textarea
                name="description"
                rows={4}
                placeholder="覆盖制度、故障和架构关系查询"
              />
            </label>
            {createDataset.error && <ErrorState error={createDataset.error} />}
            <div className="form-actions">
              <button type="button" onClick={() => setCreatingDataset(false)}>
                取消
              </button>
              <button className="primary-button" disabled={createDataset.isPending}>
                {createDataset.isPending ? '创建中…' : '创建'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {creatingCase && (
        <Modal title="添加评测 Case" onClose={() => setCreatingCase(false)}>
          <CaseForm
            error={createCase.error}
            pending={createCase.isPending}
            onCancel={() => setCreatingCase(false)}
            onSubmit={createCase.mutate}
          />
        </Modal>
      )}
    </div>
  )
}

function CaseForm({
  onSubmit,
  onCancel,
  error,
  pending,
}: {
  error: unknown
  pending: boolean
  onCancel: () => void
  onSubmit: (body: EvaluationCaseInput) => void
}) {
  const api = useApi()
  const [validationError, setValidationError] = useState<string>()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  return (
    <form
      className="form-stack"
      onSubmit={(event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const data = new FormData(event.currentTarget)
        const expectedDocuments = splitIds(data.get('documents'))
        const expectedChunks = splitIds(data.get('chunks'))
        if (!expectedDocuments.length && !expectedChunks.length) {
          setValidationError('至少填写一个目标文档 UUID 或目标 Chunk UUID')
          return
        }
        setValidationError(undefined)
        onSubmit({
          query: String(data.get('query')).trim(),
          spaceIds: [String(data.get('spaceId'))],
          expectedDocuments,
          expectedChunks,
          topK: Number(data.get('topK') ?? 8),
          labels: {},
        })
      }}
    >
      <label>
        查询问题
        <textarea name="query" required rows={4} />
      </label>
      <label>
        知识空间
        <select name="spaceId" required defaultValue="">
          <option value="">请选择</option>
          {spaces.data?.map((space) => (
            <option key={space.id} value={space.id}>
              {space.name}
            </option>
          ))}
        </select>
      </label>
      {spaces.isPending && <LoadingState label="正在加载知识空间" />}
      {spaces.error && <ErrorState error={spaces.error} />}
      <label>
        目标文档 UUID
        <textarea
          name="documents"
          rows={3}
          placeholder="多个 UUID 用逗号或换行分隔"
          onInput={() => setValidationError(undefined)}
        />
      </label>
      <label>
        目标 Chunk UUID（可选，优先级更高）
        <textarea
          name="chunks"
          rows={2}
          onInput={() => setValidationError(undefined)}
        />
      </label>
      <label>
        Top K
        <input
          name="topK"
          type="number"
          min={1}
          max={100}
          defaultValue={8}
          required
        />
      </label>
      {validationError && <ErrorState error={new Error(validationError)} />}
      {Boolean(error) && <ErrorState error={error} />}
      <div className="form-actions">
        <button type="button" onClick={onCancel}>
          取消
        </button>
        <button className="primary-button" disabled={pending || spaces.isPending}>
          {pending ? '添加中…' : '添加 Case'}
        </button>
      </div>
    </form>
  )
}

function RunDetail({ run }: { run: EvaluationRun }) {
  return (
    <>
      <div className="evaluation-stat-row">
        <EvalStat label="Hit Rate" value={percentage(metric(run, 'hitRate'))} />
        <EvalStat label="Recall@K" value={percentage(metric(run, 'recallAtK'))} />
        <EvalStat label="MRR" value={percentage(metric(run, 'mrr'))} />
        <EvalStat label="nDCG@K" value={percentage(metric(run, 'ndcgAtK'))} />
      </div>
      {run.errorCode && (
        <div className="notice-card evaluation-run-error">
          <strong>运行未完整成功</strong>
          <span>{run.errorCode}</span>
        </div>
      )}
      <div className="result-table">
        {run.results.map((result) => (
          <div className="result-row" key={result.caseId}>
            {result.hit ? (
              <CheckCircle2 className="text-success" size={17} />
            ) : (
              <XCircle className="text-danger" size={17} />
            )}
            <div className="result-identity">
              <span className="mono">Case {result.caseId.slice(0, 8)}</span>
              <code>{result.errorCode ?? '无错误'}</code>
            </div>
            <div className="result-metrics">
              <span>Recall {percentage(result.recallAtK)}</span>
              <span>RR {result.reciprocalRank.toFixed(2)}</span>
              <span>nDCG {result.ndcgAtK.toFixed(2)}</span>
              <span>结果 {result.resultCount}</span>
              <span>{result.durationMs} ms</span>
            </div>
            <div className="result-state">
              <StatusBadge value={result.status} />
              {result.traceId ? (
                <Link to={`/traces?traceId=${encodeURIComponent(result.traceId)}`}>
                  Trace {result.traceId.slice(0, 8)}
                  <ArrowRight size={13} />
                </Link>
              ) : (
                <span>无 Trace</span>
              )}
            </div>
          </div>
        ))}
        {!run.results.length && (
          <div className="inline-empty">本次运行尚未产生逐案例结果。</div>
        )}
      </div>
    </>
  )
}

function Modal({
  title,
  onClose,
  children,
}: PropsWithChildren<{ title: string; onClose: () => void }>) {
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <Panel className="modal" title={title}>
        <div onMouseDown={(event) => event.stopPropagation()}>{children}</div>
      </Panel>
    </div>
  )
}

function EvalStat({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function metric(run: EvaluationRun | undefined, key: string) {
  const value = run?.metrics?.[key]
  return typeof value === 'number' ? value : undefined
}

function configurationNumber(run: EvaluationRun, key: string) {
  const value = run.configuration?.[key]
  return typeof value === 'number' ? value : undefined
}

function splitIds(value: FormDataEntryValue | null) {
  return String(value ?? '')
    .split(/[\s,]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function percentage(value: number | undefined) {
  return value === undefined ? '—' : `${Math.round(value * 100)}%`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
