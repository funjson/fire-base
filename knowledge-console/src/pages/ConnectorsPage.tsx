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
  const [selectedRunId, setSelectedRunId] = useState<string>()
  const connectors = useQuery({
    queryKey: ['connectors'],
    queryFn: api.connectors,
    refetchInterval: (query) =>
      query.state.data?.some(
        (connector) => connector.lastRunStatus === 'RUNNING',
      )
        ? 3_000
        : false,
  })
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const recoveredRunId = connectors.data?.find(
    (connector) =>
      connector.lastRunStatus === 'RUNNING' && connector.lastRunId,
  )?.lastRunId
  const activeRunId = selectedRunId ?? recoveredRunId
  const activeRun = useQuery({
    queryKey: ['connector-run', activeRunId],
    queryFn: () => api.connectorRun(activeRunId!),
    enabled: Boolean(activeRunId),
    refetchInterval: (query) =>
      activeRunId &&
      (!query.state.data || query.state.data.status === 'RUNNING')
        ? 1_000
        : false,
  })
  const synchronizationRunning =
    Boolean(activeRunId) &&
    (!activeRun.data || activeRun.data.status === 'RUNNING')
  const create = useMutation({
    mutationFn: api.configureObsidian,
    onSuccess: async () => {
      setCreating(false)
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
          <h2>统一管理 API、Obsidian 和后续外部数据源</h2>
          <p>连接器状态、同步游标和运行历史不会与知识正文混在一起。</p>
        </div>
        <button className="primary-button" onClick={() => setCreating(true)}>
          <Plus size={17} />
          添加 Obsidian
        </button>
      </div>

      <Panel>
        {!connectors.data.length ? (
          <EmptyState
            title="尚未配置连接器"
            description="创建知识空间时会自动建立 API Upload 连接器。"
          />
        ) : (
          <div className="connector-grid">
            {connectors.data.map((connector) => (
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
                      connector.lastRunStatus === 'RUNNING' ||
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
        <strong>本地目录采用服务器白名单</strong>
        <span>
          启动 API 前必须通过 KNOWLEDGE_OBSIDIAN_ALLOWED_ROOTS 配置允许读取的
          Vault 父目录。控制台提交的路径无法越过该白名单。
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
            {activeRun.data.recordsChanged}
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
          <Panel className="modal" title="添加 Obsidian Vault">
            <form
              className="form-stack"
              onMouseDown={(event) => event.stopPropagation()}
              onSubmit={(event) => submitConnector(event, create.mutate)}
            >
              <button
                className="icon-button modal-close"
                type="button"
                onClick={() => setCreating(false)}
              >
                <X size={17} />
              </button>
              <label>
                知识空间
                <select name="spaceId" required defaultValue="">
                  <option value="">请选择</option>
                  {spaces.data.map((space) => (
                    <option key={space.id} value={space.id}>
                      {space.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                连接器 ID
                <input name="connectorId" required placeholder="obsidian-product" />
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
                  保存连接器
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
    connectorId: String(data.get('connectorId')).trim(),
    spaceId: String(data.get('spaceId')).trim(),
    displayName: String(data.get('displayName')).trim(),
    vaultName: String(data.get('vaultName')).trim(),
    vaultPath: String(data.get('vaultPath')).trim(),
    authority: Number(data.get('authority') ?? 70),
  })
}
