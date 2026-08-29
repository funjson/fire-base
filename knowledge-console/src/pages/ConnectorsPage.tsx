import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Cable, Database, FolderKanban, Play, Plus, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import { useApi } from '../lib/use-api'

export function ConnectorsPage() {
  const api = useApi()
  const client = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [draftConnectorId, setDraftConnectorId] = useState('')
  const [selectedRunId, setSelectedRunId] = useState<string>()
  const connectors = useQuery({
    queryKey: ['connectors'],
    queryFn: api.connectors,
    refetchInterval: (query) =>
      query.state.data?.some(
        (connector) => ['PENDING', 'RUNNING'].includes(connector.lastRunStatus ?? ''),
      )
        ? 3_000
        : false,
  })
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  // API 上传记录是每个 Space 的内部来源身份，不是用户需要维护的数据源。
  const externalSources =
    connectors.data?.filter(
      (connector) => !['API', 'API_UPLOAD'].includes(connector.type),
    ) ?? []
  const recoveredRunId = externalSources.find(
    (connector) =>
      ['PENDING', 'RUNNING'].includes(connector.lastRunStatus ?? '') &&
      connector.lastRunId,
  )?.lastRunId
  const activeRunId = selectedRunId ?? recoveredRunId
  const activeRun = useQuery({
    queryKey: ['connector-run', activeRunId],
    queryFn: () => api.connectorRun(activeRunId!),
    enabled: Boolean(activeRunId),
    refetchInterval: (query) =>
      activeRunId &&
      (!query.state.data || ['PENDING', 'RUNNING'].includes(query.state.data.status))
        ? 1_000
        : false,
  })
  const synchronizationRunning =
    Boolean(activeRunId) &&
    (!activeRun.data || ['PENDING', 'RUNNING'].includes(activeRun.data.status))
  const create = useMutation({
    mutationFn: api.configureObsidian,
    onSuccess: async () => {
      setCreating(false)
      setDraftConnectorId('')
      await client.invalidateQueries({ queryKey: ['connectors'] })
    },
  })
  const synchronize = useMutation({
    mutationFn: api.synchronizeConnector,
    onSuccess: async (run) => {
      setSelectedRunId(run.runId)
      await client.invalidateQueries({ queryKey: ['connectors'] })
    },
  })
  if (connectors.isPending || spaces.isPending) return <LoadingState />
  if (connectors.error || spaces.error) {
    return <ErrorState error={connectors.error ?? spaces.error} />
  }

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">EXTERNAL SOURCES</span>
          <h2>统一管理 Obsidian 和后续外部数据源</h2>
          <p>数据源状态、同步进度和运行历史不会与知识正文混在一起。</p>
        </div>
        <button
          className="primary-button"
          onClick={() => {
            setDraftConnectorId(`obsidian:${crypto.randomUUID()}`)
            setCreating(true)
          }}
        >
          <Plus size={17} />
          添加数据源
        </button>
      </div>

      <div className="notice-card">
        <strong>文件与 API 摄取是系统内置能力</strong>
        <span>
          每个知识空间创建后即可上传文件或调用摄取 API，无需创建、更新或删除连接器。
          系统内部的来源身份不会作为外部数据源展示。
        </span>
      </div>

      <Panel>
        {!externalSources.length ? (
          <EmptyState
            title="尚未添加外部数据源"
            description="可以通过右上角“添加数据源”接入一个 Obsidian Vault。"
          />
        ) : (
          <div className="connector-grid">
            {externalSources.map((connector) => (
              <article className="connector-card" key={connector.id}>
                <header>
                  <div className="connector-icon">
                    {connector.type === 'OBSIDIAN' ? (
                      <FolderKanban size={21} />
                    ) : connector.type === 'API' ? (
                      <Cable size={21} />
                    ) : (
                      <Database size={21} />
                    )}
                  </div>
                  <StatusBadge value={connector.status} />
                </header>
                <h3>{connector.displayName}</h3>
                <span className="mono">{connector.id}</span>
                <div className="connector-meta">
                  <div>
                    <span>类型</span>
                    <strong>{connector.type}</strong>
                  </div>
                  <div>
                    <span>空间</span>
                    <strong>{connector.spaceId}</strong>
                  </div>
                  <div>
                    <span>最近同步</span>
                    <strong>{connector.lastRunStatus ?? '尚未运行'}</strong>
                  </div>
                </div>
                {connector.type === 'OBSIDIAN' && (
                  <button
                    className="connector-action"
                    disabled={
                      synchronize.isPending ||
                      ['PENDING', 'RUNNING'].includes(connector.lastRunStatus ?? '') ||
                      synchronizationRunning
                    }
                    onClick={() => synchronize.mutate(connector.id)}
                  >
                    <Play size={15} />
                    立即同步
                  </button>
                )}
              </article>
            ))}
          </div>
        )}
      </Panel>
      <div className="notice-card">
        <strong>本地目录受服务器安全范围保护</strong>
        <span>
          Obsidian 只能读取管理员预先批准的服务器目录。若目标 Vault 不在可选范围内，
          请联系系统管理员调整部署配置。
        </span>
      </div>

      {synchronize.error && <ErrorState error={synchronize.error} />}
      {activeRun.error && <ErrorState error={activeRun.error} />}
      {activeRun.data && (
        <div className="notice-card">
          <strong>
            同步任务 <span className="mono">{activeRun.data.runId.slice(0, 8)}</span>
          </strong>
          <span>
            {activeRun.data.status} · 已扫描 {activeRun.data.recordsSeen} · 已变更{' '}
            {activeRun.data.recordsChanged} · 已归档 {activeRun.data.recordsDeleted}
            {activeRun.data.errorCode ? ` · ${activeRun.data.errorCode}` : ''}
          </span>
        </div>
      )}

      {creating && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setCreating(false)
          }}
        >
          <Panel className="modal" title="添加数据源">
            <form
              className="form-stack"
              onMouseDown={(event) => event.stopPropagation()}
              onSubmit={(event) =>
                submitConnector(event, draftConnectorId, create.mutate)
              }
            >
              <button
                className="icon-button modal-close"
                type="button"
                onClick={() => setCreating(false)}
              >
                <X size={17} />
              </button>
              <label>
                数据源类型
                <select name="sourceType" required defaultValue="OBSIDIAN">
                  <option value="OBSIDIAN">Obsidian Vault</option>
                </select>
                <small>当前版本仅支持 Obsidian，后续数据源将在这里扩展。</small>
              </label>
              <label>
                写入知识空间
                <select name="spaceId" required defaultValue="">
                  <option value="">请选择</option>
                  {spaces.data.map((space) => (
                    <option key={space.id} value={space.id}>
                      {space.name}
                    </option>
                  ))}
                </select>
                <small>
                  文档处理配置、访问权限和索引均按知识空间隔离，因此数据源必须指定写入目标。
                </small>
              </label>
              <label>
                显示名称
                <input name="displayName" required placeholder="产品知识 Vault" />
              </label>
              <label>
                Vault 名称
                <input name="vaultName" required placeholder="Product Wiki" />
              </label>
              <label>
                服务器本地路径
                <input
                  name="vaultPath"
                  required
                  placeholder="C:\Knowledge\ProductVault"
                />
              </label>
              <label>
                权威等级
                <input
                  name="authority"
                  type="number"
                  min={0}
                  max={100}
                  defaultValue={70}
                />
              </label>
              {create.error && <ErrorState error={create.error} />}
              <div className="form-actions">
                <button type="button" onClick={() => setCreating(false)}>
                  取消
                </button>
                <button className="primary-button" disabled={create.isPending}>
                  {create.isPending ? '添加中…' : '添加数据源'}
                </button>
              </div>
            </form>
          </Panel>
        </div>
      )}
    </div>
  )
}

function submitConnector(
  event: FormEvent<HTMLFormElement>,
  connectorId: string,
  mutate: (body: {
    connectorId: string
    spaceId: string
    displayName: string
    vaultName: string
    vaultPath: string
    authority: number
  }) => void,
) {
  event.preventDefault()
  const data = new FormData(event.currentTarget)
  mutate({
    connectorId,
    spaceId: String(data.get('spaceId')).trim(),
    displayName: String(data.get('displayName')).trim(),
    vaultName: String(data.get('vaultName')).trim(),
    vaultPath: String(data.get('vaultPath')).trim(),
    authority: Number(data.get('authority') ?? 70),
  })
}
