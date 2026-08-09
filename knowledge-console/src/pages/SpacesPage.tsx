import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Blocks,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  Users,
} from 'lucide-react'
import { useState, type FormEvent } from 'react'
import {
  EmptyState,
  ErrorState,
  LoadingState,
  Panel,
  StatusBadge,
} from '../components/State'
import { useApi } from '../lib/use-api'
import type { ProjectionRebuild, Space, SpaceGrant } from '../lib/api'

export function SpacesPage() {
  const api = useApi()
  const client = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [managedSpace, setManagedSpace] = useState<Space>()
  const [rebuildResult, setRebuildResult] = useState<{
    space: Space
    result: ProjectionRebuild
  }>()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const grants = useQuery({
    queryKey: ['space-grants', managedSpace?.id],
    queryFn: () => api.spaceGrants(managedSpace!.id),
    enabled: Boolean(managedSpace),
  })
  const create = useMutation({
    mutationFn: api.createSpace,
    onSuccess: async () => {
      setCreating(false)
      await client.invalidateQueries({ queryKey: ['spaces'] })
      await client.invalidateQueries({ queryKey: ['overview'] })
    },
  })
  const grant = useMutation({
    mutationFn: (value: SpaceGrant) => api.grantSpace(managedSpace!.id, value),
    onSuccess: async () => {
      await client.invalidateQueries({
        queryKey: ['space-grants', managedSpace?.id],
      })
      await client.invalidateQueries({ queryKey: ['accessible-spaces'] })
    },
  })
  const revoke = useMutation({
    mutationFn: (value: SpaceGrant) => api.revokeSpace(managedSpace!.id, value),
    onSuccess: async () => {
      await client.invalidateQueries({
        queryKey: ['space-grants', managedSpace?.id],
      })
      await client.invalidateQueries({ queryKey: ['accessible-spaces'] })
    },
  })
  const rebuild = useMutation({
    mutationFn: async (space: Space) => ({
      space,
      result: await api.rebuildSpaceProjections(space.id),
    }),
    onMutate: () => setRebuildResult(undefined),
    onSuccess: async (value) => {
      setRebuildResult(value)
      await client.invalidateQueries({ queryKey: ['overview'] })
      await client.invalidateQueries({ queryKey: ['documents'] })
    },
  })

  if (spaces.isPending) return <LoadingState />
  if (spaces.error) return <ErrorState error={spaces.error} />

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">TENANT BOUNDARIES</span>
          <h2>知识空间是权限和检索策略的边界</h2>
          <p>每个空间独立管理文档、成员权限、索引版本和评测数据。</p>
        </div>
        <button className="primary-button" onClick={() => setCreating(true)}>
          <Plus size={17} />
          新建空间
        </button>
      </div>

      {!spaces.data.length ? (
        <EmptyState
          title="还没有知识空间"
          description="创建第一个空间后即可上传文档并运行检索。"
          action={
            <button className="primary-button" onClick={() => setCreating(true)}>
              新建空间
            </button>
          }
        />
      ) : (
        <div className="space-grid">
          {spaces.data.map((space) => (
            <article className="space-card" key={space.id}>
              <div className="space-card-top">
                <div className="space-icon">
                  <Blocks size={20} />
                </div>
                <StatusBadge value={space.status} />
              </div>
              <h3>{space.name}</h3>
              <p>{space.description || '暂未填写空间说明'}</p>
              <div className="space-meta">
                <span className="mono">{space.id}</span>
                <strong>{space.documentCount} 篇文档</strong>
              </div>
              <footer>
                <ShieldCheck size={16} />
                <span>空间级 ACL 已启用</span>
                <button
                  className="space-acl-button push"
                  disabled={rebuild.isPending}
                  onClick={() => rebuild.mutate(space)}
                >
                  <RefreshCw
                    className={
                      rebuild.isPending && rebuild.variables?.id === space.id
                        ? 'spin'
                        : undefined
                    }
                    size={14}
                  />
                  重建索引
                </button>
                <button
                  className="space-acl-button"
                  onClick={() => setManagedSpace(space)}
                >
                  <Users size={14} />
                  管理授权
                </button>
              </footer>
            </article>
          ))}
        </div>
      )}

      {rebuild.error && <ErrorState error={rebuild.error} />}
      {rebuildResult && (
        <div className="notice-card">
          <strong>
            {rebuildResult.space.name} ·{' '}
            {rebuildResult.result.jobs > 0
              ? '索引任务已重新排队'
              : '没有需要重建的活动修订'}
          </strong>
          <span>
            共 {rebuildResult.result.jobs} 个任务 ·{' '}
            {rebuildResult.result.projectionTypes.join(' / ') || '当前无外部投影通道'}
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
          <Panel className="modal" title="新建知识空间">
            <form
              className="form-stack"
              onSubmit={(event) => submit(event, create.mutate)}
              onMouseDown={(event) => event.stopPropagation()}
            >
              <label>
                空间标识
                <input name="spaceId" required maxLength={64} placeholder="engineering" />
                <small>创建后作为 API 和索引中的稳定标识。</small>
              </label>
              <label>
                显示名称
                <input name="name" required maxLength={256} placeholder="研发知识库" />
              </label>
              {create.error && <ErrorState error={create.error} />}
              <div className="form-actions">
                <button type="button" onClick={() => setCreating(false)}>
                  取消
                </button>
                <button className="primary-button" disabled={create.isPending}>
                  {create.isPending ? '创建中…' : '创建空间'}
                </button>
              </div>
            </form>
          </Panel>
        </div>
      )}

      {managedSpace && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setManagedSpace(undefined)
          }}
        >
          <Panel
            className="modal modal-wide"
            title={`${managedSpace.name} · 空间授权`}
            description="授权立即影响可访问空间与检索范围"
          >
            <div
              className="space-acl-content"
              onMouseDown={(event) => event.stopPropagation()}
            >
              {grants.isPending && <LoadingState />}
              {grants.error && <ErrorState error={grants.error} />}
              {grants.data && (
                <div className="space-acl-list">
                  {!grants.data.length && (
                    <EmptyState
                      title="暂无空间授权"
                      description="添加 USER、ROLE、DEPARTMENT 或 TENANT 授权。"
                    />
                  )}
                  {grants.data.map((item) => (
                    <div
                      className="space-acl-row"
                      key={`${item.subjectType}:${item.subjectId}:${item.permission}`}
                    >
                      <StatusBadge value={item.subjectType} />
                      <strong>{item.subjectId}</strong>
                      <span>{item.permission}</span>
                      <button
                        className="icon-button"
                        title="撤销授权"
                        disabled={revoke.isPending}
                        onClick={() => revoke.mutate(item)}
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <form
                className="form-grid space-acl-form"
                onSubmit={(event) => submitGrant(event, grant.mutate)}
              >
                <label>
                  主体类型
                  <select name="subjectType" defaultValue="ROLE">
                    <option value="USER">用户</option>
                    <option value="ROLE">角色</option>
                    <option value="DEPARTMENT">部门</option>
                    <option value="TENANT">租户</option>
                  </select>
                </label>
                <label>
                  主体标识
                  <input
                    name="subjectId"
                    required
                    maxLength={128}
                    placeholder="knowledge-reader"
                  />
                </label>
                <label>
                  权限
                  <select name="permission" defaultValue="READ">
                    <option value="READ">读取</option>
                    <option value="WRITE">写入</option>
                    <option value="ADMIN">管理</option>
                  </select>
                </label>
                <div className="space-acl-submit">
                  <button className="primary-button" disabled={grant.isPending}>
                    {grant.isPending ? '授权中…' : '添加授权'}
                  </button>
                </div>
              </form>
              {(grant.error || revoke.error) && (
                <ErrorState error={grant.error ?? revoke.error} />
              )}
              <div className="form-actions">
                <button type="button" onClick={() => setManagedSpace(undefined)}>
                  完成
                </button>
              </div>
            </div>
          </Panel>
        </div>
      )}
    </div>
  )
}

function submitGrant(
  event: FormEvent<HTMLFormElement>,
  mutate: (value: SpaceGrant) => void,
) {
  event.preventDefault()
  const data = new FormData(event.currentTarget)
  mutate({
    subjectType: String(data.get('subjectType')) as SpaceGrant['subjectType'],
    subjectId: String(data.get('subjectId')).trim(),
    permission: String(data.get('permission')) as SpaceGrant['permission'],
  })
}

function submit(
  event: FormEvent<HTMLFormElement>,
  mutate: (value: { spaceId: string; name: string }) => void,
) {
  event.preventDefault()
  const data = new FormData(event.currentTarget)
  mutate({
    spaceId: String(data.get('spaceId')).trim(),
    name: String(data.get('name')).trim(),
  })
}
