import { useMutation, useQuery } from '@tanstack/react-query'
import {
  CheckCircle2,
  ExternalLink,
  Fingerprint,
  FlaskConical,
  Plus,
  Route,
  Search,
  Settings2,
  ShieldAlert,
  Trash2,
  X,
} from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { RetrievalConfigurationOverrideDialog } from '../components/retrieval/RetrievalConfigurationOverrideDialog'
import { RetrievalObservationPanel } from '../components/retrieval/RetrievalObservationPanel'
import { retrievalConfigurationOverrideFieldCount } from '../components/retrieval/retrieval-configuration-model'
import { EvaluationWorkspaceNav } from '../components/evaluation/EvaluationWorkspaceNav'
import { ErrorState, Panel, StatusBadge } from '../components/State'
import { ApiError } from '../lib/api'
import type {
  EvidenceBundle,
  EvidenceRequirementInput,
  KnowledgeQueryInput,
  RetrievalConfigurationOverride,
} from '../lib/api'
import { useApi } from '../lib/use-api'

export function RetrievalPage() {
  const api = useApi()
  const [selectedSpaces, setSelectedSpaces] = useState<string[]>([])
  const [testMode, setTestMode] = useState(true)
  const [configurationOverride, setConfigurationOverride] =
    useState<RetrievalConfigurationOverride>()
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false)
  const [evidenceRequirements, setEvidenceRequirements] = useState<
    EvidenceRequirementInput[]
  >([{ id: 'requirement-1', description: '' }])
  const nextEvidenceRequirementId = useRef(2)
  const spaces = useQuery({
    queryKey: ['accessible-spaces'],
    queryFn: api.accessibleSpaces,
  })
  const query = useMutation({ mutationFn: api.query })
  const executionObservation = useQuery({
    queryKey: ['retrieval-observation', query.data?.requestId ?? 'none'],
    queryFn: () => api.retrievalObservationByRequest(query.data!.requestId),
    enabled: Boolean(query.data?.requestId),
    retry: (failureCount, error) =>
      error instanceof ApiError && error.status === 404 && failureCount < 2,
    retryDelay: (attemptIndex) => Math.min(400 * 2 ** attemptIndex, 1_600),
    refetchOnWindowFocus: false,
  })

  return (
    <div className="page-stack">
      <EvaluationWorkspaceNav />
      <div className="page-intro">
        <div>
          <span className="eyebrow">RETRIEVAL PLAYGROUND</span>
          <h2>调整参数并检查每一次召回、融合和引用</h2>
          <p>使用当前用户的真实权限执行，不提供绕过 ACL 的调试后门。</p>
        </div>
      </div>

      <div className="retrieval-layout">
        <Panel title="查询条件" description="选择知识空间并执行真实混合检索">
          <form
            className="form-stack"
            onSubmit={(event) =>
              submit(
                event,
                selectedSpaces,
                query.mutate,
                configurationOverride,
                testMode,
                evidenceRequirements,
              )
            }
          >
            <label>
              查询
              <textarea
                name="query"
                rows={5}
                required
                placeholder="例如：订单服务超时时应该检查哪些依赖？"
              />
            </label>
            <label>
              检索目标（可选）
              <textarea
                name="retrievalTarget"
                rows={2}
                maxLength={4_000}
                placeholder="例如：定位订单超时的直接原因，并给出可引用的排查步骤"
              />
              <small>用于约束 Coverage 判断，不会替换上方的原始查询。</small>
            </label>
            <fieldset className="retrieval-requirements">
              <legend>证据要求（可选）</legend>
              <div className="retrieval-requirements-heading">
                <small>
                  检索目标与有效证据要求同时为空时，将标记
                  EVIDENCE_REQUIREMENTS_MISSING，并跳过 Coverage 与后续优化。
                </small>
                <button
                  type="button"
                  disabled={evidenceRequirements.length >= 32}
                  onClick={() => {
                    const id = `requirement-${nextEvidenceRequirementId.current}`
                    nextEvidenceRequirementId.current += 1
                    setEvidenceRequirements((current) => [
                      ...current,
                      { id, description: '' },
                    ])
                  }}
                >
                  <Plus size={13} />
                  添加要求
                </button>
              </div>
              <div className="retrieval-requirement-list">
                {evidenceRequirements.map((requirement, index) => (
                  <div className="retrieval-requirement-row" key={requirement.id}>
                    <span>{index + 1}</span>
                    <input
                      aria-label={`证据要求 ${index + 1}`}
                      value={requirement.description}
                      maxLength={2_000}
                      placeholder="例如：必须包含超时阈值的配置来源"
                      onChange={(event) => {
                        const description = event.currentTarget.value
                        setEvidenceRequirements((current) =>
                          current.map((item) =>
                            item.id === requirement.id
                              ? { ...item, description }
                              : item,
                          ),
                        )
                      }}
                    />
                    <button
                      type="button"
                      aria-label={`删除证据要求 ${index + 1}`}
                      title="删除这条证据要求"
                      onClick={() =>
                        setEvidenceRequirements((current) =>
                          current.filter((item) => item.id !== requirement.id),
                        )
                      }
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
                {!evidenceRequirements.length && (
                  <span className="retrieval-requirements-empty">
                    当前未设置证据要求；如需 Coverage，请填写检索目标或至少一条要求。
                  </span>
                )}
              </div>
            </fieldset>
            <fieldset>
              <legend>知识空间</legend>
              {spaces.error && <ErrorState error={spaces.error} />}
              <div className="check-list">
                {spaces.data?.map((space) => (
                  <label key={space.id}>
                    <input
                      type="checkbox"
                      checked={selectedSpaces.includes(space.id)}
                      onChange={(event) =>
                        setSelectedSpaces((current) =>
                          event.target.checked
                            ? [...current, space.id]
                            : current.filter((id) => id !== space.id),
                        )
                      }
                    />
                    <span>
                      <strong>{space.name}</strong>
                      <small>{space.documentCount} 篇文档</small>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <label>
              返回数量
              <input
                name="topK"
                type="number"
                min={1}
                max={30}
                defaultValue={8}
                required
              />
            </label>
            <div className="form-grid">
              <div className="retrieval-constraint-field">
                <label>
                  语言（BCP 47）
                  <input name="language" placeholder="例如 zh-CN" maxLength={32} />
                </label>
                <label>
                  约束方式
                  <select name="languageConstraintMode" defaultValue="HARD">
                    <option value="HARD">硬过滤（不可放宽）</option>
                    <option value="RELAXABLE">初始生效，可放宽</option>
                    <option value="NARROWING">仅作为收窄候选</option>
                  </select>
                </label>
              </div>
              <div className="retrieval-constraint-field">
                <label>
                  来源类型
                  <select name="sourceType" defaultValue="">
                    <option value="">全部来源</option>
                    <option value="API">Markdown API</option>
                    <option value="UPLOAD">文件上传</option>
                    <option value="OBSIDIAN">Obsidian</option>
                    <option value="FILESYSTEM">文件系统</option>
                    <option value="GIT">Git</option>
                    <option value="COMPILED">编译知识</option>
                  </select>
                </label>
                <label>
                  约束方式
                  <select name="sourceTypeConstraintMode" defaultValue="HARD">
                    <option value="HARD">硬过滤（不可放宽）</option>
                    <option value="RELAXABLE">初始生效，可放宽</option>
                    <option value="NARROWING">仅作为收窄候选</option>
                  </select>
                </label>
              </div>
            </div>
            <small className="retrieval-constraint-help">
              硬过滤始终生效；可放宽过滤仅在启用 RELAX 时允许移除；收窄候选首轮不生效，
              仅在启用 NARROW 且证据不足时加入。
            </small>
            <label className="retrieval-test-mode">
              <input
                type="checkbox"
                checked={testMode}
                onChange={(event) => setTestMode(event.currentTarget.checked)}
              />
              <span>
                <strong>测试广场模式</strong>
                <small>
                  原始观测数据标记为 TEST_PLAZA，与在线请求指标隔离。
                </small>
              </span>
            </label>
            <div
              className={`retrieval-override-state${
                configurationOverride ? ' active' : ''
              }`}
            >
              <FlaskConical size={17} />
              <div>
                <strong>
                  {configurationOverride
                    ? `本次请求将覆盖 ${retrievalConfigurationOverrideFieldCount(
                        configurationOverride,
                      )} 项参数`
                    : '本次请求继承 Space 当前配置'}
                </strong>
                <span>
                  {configurationOverride
                    ? `只覆盖明确设置的字段；已选 ${selectedSpaces.length} 个 Space 分别继承其余配置。`
                    : '可调整召回、RRF、精排、Coverage 和固定 Chain。'}
                </span>
              </div>
              <div className="retrieval-override-actions">
                {configurationOverride && (
                  <button
                    type="button"
                    title="清除本次参数覆盖"
                    onClick={() => setConfigurationOverride(undefined)}
                  >
                    <X size={14} />
                    清除
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setOverrideDialogOpen(true)}
                >
                  <Settings2 size={14} />
                  {configurationOverride ? '调整参数' : '设置参数'}
                </button>
              </div>
            </div>
            <button
              className="primary-button"
              disabled={query.isPending || !selectedSpaces.length}
            >
              <Search size={17} />
              {query.isPending ? '检索中…' : '运行检索'}
            </button>
          </form>
        </Panel>

        <div className="result-column">
          {!query.data && !query.error && (
            <div className="retrieval-placeholder">
              <Search size={30} />
              <strong>等待查询</strong>
              <span>结果会显示通道、相关度、引用和安全 Trace。</span>
            </div>
          )}
          {query.error && <ErrorState error={query.error} />}
          {query.data && (
            <>
              <div className="result-summary">
                <div
                  className={
                    query.data.sufficient ? 'summary-icon success' : 'summary-icon warning'
                  }
                >
                  {query.data.sufficient ? (
                    <CheckCircle2 size={21} />
                  ) : (
                    <ShieldAlert size={21} />
                  )}
                </div>
                <div>
                  <strong>
                    {query.data.sufficient ? '证据充分' : '证据可能不足'}
                  </strong>
                  <span>
                    {query.data.evidences.length} 条证据 · Trace{' '}
                    <span className="mono">{query.data.traceId.slice(0, 8)}</span>
                  </span>
                </div>
                {query.data.warnings.length > 0 && (
                  <div className="warning-pills">
                    {query.data.warnings.map((warning) => (
                      <StatusBadge value={warning} key={warning} />
                    ))}
                  </div>
                )}
              </div>
              <RetrievalDiagnostics
                bundle={query.data}
                requestedTestMode={Boolean(query.variables?.testMode)}
              />
              <RetrievalObservationPanel
                report={executionObservation.data}
                loading={executionObservation.isPending}
                refreshing={executionObservation.isFetching}
                error={executionObservation.error}
                expectedPurpose={
                  (query.data.testMode ?? Boolean(query.variables?.testMode))
                    ? 'TEST_PLAZA'
                    : 'ONLINE'
                }
                onRetry={() => {
                  void executionObservation.refetch()
                }}
              />
              <div className="evidence-list">
                {query.data.evidences.map((evidence, index) => (
                  <article className="evidence-card" key={evidence.id}>
                    <header>
                      <div className="rank">{index + 1}</div>
                      <div>
                        <strong>{evidence.citation.title}</strong>
                        <span>{evidence.citation.sectionPath.join(' / ') || '正文'}</span>
                      </div>
                      <div className="score">
                        <strong>{Math.round(evidence.relevance * 100)}%</strong>
                        <span>融合相关度</span>
                      </div>
                    </header>
                    <p>{evidence.content}</p>
                    <footer>
                      <div className="channel-pills">
                        {evidence.channels.map((channel) => (
                          <span key={channel}>{channel}</span>
                        ))}
                      </div>
                      {safeSourceUri(evidence.citation.sourceUri) ? (
                        <a
                          href={evidence.citation.sourceUri}
                          target="_blank"
                          rel="noreferrer"
                        >
                          查看来源 <ExternalLink size={13} />
                        </a>
                      ) : (
                        <span title="来源协议未被控制台允许">来源不可打开</span>
                      )}
                    </footer>
                  </article>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {overrideDialogOpen && (
        <RetrievalConfigurationOverrideDialog
          initialOverride={configurationOverride}
          onApply={(configuration) => {
            setConfigurationOverride(configuration)
            setTestMode(true)
            setOverrideDialogOpen(false)
          }}
          onClose={() => setOverrideDialogOpen(false)}
        />
      )}
    </div>
  )
}

function RetrievalDiagnostics({
  bundle,
  requestedTestMode,
}: {
  bundle: EvidenceBundle
  requestedTestMode: boolean
}) {
  return (
    <div className="retrieval-diagnostics" aria-label="本次检索执行诊断">
      <div>
        <Fingerprint size={15} />
        <span>有效配置指纹</span>
        {bundle.configurationFingerprints?.length ? (
          <span className="retrieval-fingerprint-values">
            {bundle.configurationFingerprints.map((fingerprint, index) => (
              <code title={fingerprint} key={`${index}:${fingerprint}`}>
                {bundle.visitedSpaceIds?.[index]
                  ? `${bundle.visitedSpaceIds[index]}: ${fingerprint}`
                  : fingerprint}
              </code>
            ))}
          </span>
        ) : (
          <em>服务端尚未返回</em>
        )}
      </div>
      <div>
        <Route size={15} />
        <span>实际访问 Space</span>
        {bundle.visitedSpaceIds ? (
          <strong>{bundle.visitedSpaceIds.join(' → ') || '无'}</strong>
        ) : (
          <em>服务端尚未返回</em>
        )}
      </div>
      <div>
        <CheckCircle2 size={15} />
        <span>终态</span>
        <strong title={bundle.stopReason}>
          {bundle.terminalStatus
            ? `${bundle.terminalStatus}${bundle.stopReason ? ` · ${bundle.stopReason}` : ''}`
            : '服务端尚未返回'}
        </strong>
      </div>
      <div>
        <FlaskConical size={15} />
        <span>观测用途</span>
        <strong>
          {(bundle.testMode ?? requestedTestMode) ? 'TEST_PLAZA' : 'ONLINE'}
          {bundle.degraded ? ' · DEGRADED' : ''}
        </strong>
      </div>
    </div>
  )
}

function safeSourceUri(value: string): boolean {
  try {
    return ['http:', 'https:', 'obsidian:'].includes(new URL(value).protocol)
  } catch {
    return false
  }
}

function submit(
  event: FormEvent<HTMLFormElement>,
  spaceIds: string[],
  mutate: (value: KnowledgeQueryInput) => void,
  configurationOverride?: RetrievalConfigurationOverride,
  testMode = false,
  evidenceRequirements: EvidenceRequirementInput[] = [],
) {
  event.preventDefault()
  const form = new FormData(event.currentTarget)
  const filters: Record<string, string> = {}
  const relaxableFilters: Record<string, string> = {}
  const narrowingFilters: Record<string, string> = {}
  for (const name of ['language', 'sourceType']) {
    const value = String(form.get(name) ?? '').trim()
    if (!value) continue
    const mode = String(form.get(`${name}ConstraintMode`) ?? 'HARD')
    if (mode === 'RELAXABLE') relaxableFilters[name] = value
    else if (mode === 'NARROWING') narrowingFilters[name] = value
    else filters[name] = value
  }
  mutate({
    query: String(form.get('query')).trim(),
    spaceIds,
    topK: Number(form.get('topK') ?? 8),
    filters,
    constraints: { relaxableFilters, narrowingFilters },
    retrievalTarget: String(form.get('retrievalTarget') ?? '').trim(),
    evidenceRequirements: evidenceRequirements
      .map((requirement) => ({
        id: requirement.id,
        description: requirement.description.trim(),
      }))
      .filter((requirement) => requirement.description.length > 0),
    configurationOverride,
    testMode,
  })
}
