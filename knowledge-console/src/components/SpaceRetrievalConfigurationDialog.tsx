import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, RotateCcw, SlidersHorizontal } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import type {
  RetrievalConfiguration,
  Space,
  SpaceRetrievalConfiguration,
  UpdateSpaceRetrievalConfigurationRequest,
} from '../lib/api'
import { useApi } from '../lib/use-api'
import { RetrievalConfigurationFields } from './retrieval/RetrievalConfigurationFields'
import {
  cloneRetrievalConfiguration,
  sameRetrievalConfiguration,
  spaceRetrievalConfigurationQueryKey,
  validateRetrievalConfiguration,
} from './retrieval/retrieval-configuration-model'
import { ErrorState, LoadingState, Panel } from './State'

/** 查看并通过追加不可变修订更新 Space 的完整检索配置。 */
export function SpaceRetrievalConfigurationDialog({
  space,
  onClose,
}: {
  space: Space
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const queryKey = spaceRetrievalConfigurationQueryKey(space.id)
  const configQuery = useQuery({
    queryKey,
    queryFn: () => api.spaceRetrievalConfiguration(space.id),
  })
  const save = useMutation({
    mutationFn: (body: UpdateSpaceRetrievalConfigurationRequest) =>
      api.updateSpaceRetrievalConfiguration(space.id, body),
    onSuccess: (saved) => queryClient.setQueryData(queryKey, saved),
  })

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !save.isPending) onClose()
      }}
    >
      <Panel
        className="modal modal-retrieval-config"
        title={`${space.name} · 检索配置`}
        description="查看当前不可变修订，并为后续查询追加一个完整的新修订"
      >
        <div
          className="document-processing-config-content"
          onMouseDown={(event) => event.stopPropagation()}
        >
          {configQuery.isPending && (
            <LoadingState label="正在读取 Space 检索配置" />
          )}
          {configQuery.error && <ErrorState error={configQuery.error} />}
          {!configQuery.data && (
            <div className="form-actions">
              {configQuery.error && (
                <button
                  type="button"
                  disabled={configQuery.isFetching}
                  onClick={() => configQuery.refetch()}
                >
                  {configQuery.isFetching ? '重试中…' : '重新加载'}
                </button>
              )}
              <button type="button" onClick={onClose}>
                关闭
              </button>
            </div>
          )}
          {configQuery.data && (
            <SpaceRetrievalConfigurationForm
              key={`${space.id}:${configQuery.data.revision}`}
              view={configQuery.data}
              pending={save.isPending}
              error={save.error}
              saved={save.data}
              onSave={(configuration) => {
                save.reset()
                save.mutate({
                  expectedRevision: configQuery.data.revision,
                  configuration,
                })
              }}
              onClose={onClose}
            />
          )}
        </div>
      </Panel>
    </div>
  )
}

function SpaceRetrievalConfigurationForm({
  view,
  pending,
  error,
  saved,
  onSave,
  onClose,
}: {
  view: SpaceRetrievalConfiguration
  pending: boolean
  error: unknown
  saved: SpaceRetrievalConfiguration | undefined
  onSave: (configuration: RetrievalConfiguration) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState(() =>
    cloneRetrievalConfiguration(view.configuration),
  )
  const errors = useMemo(
    () => validateRetrievalConfiguration(draft),
    [draft],
  )
  const changed = !sameRetrievalConfiguration(draft, view.configuration)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!changed || errors.length > 0 || pending) return
    onSave(draft)
  }

  return (
    <form
      aria-label="Space 检索配置"
      className="document-processing-config-form"
      onSubmit={submit}
    >
      <div className="retrieval-config-impact-notice">
        <SlidersHorizontal size={17} />
        <div>
          <strong>保存会追加修订，不会修改索引</strong>
          <span>
            新修订只影响之后发起的查询。这里不包含 Embedding、Tokenizer、索引代际或维度配置，
            因此不会触发重建索引。
          </span>
        </div>
      </div>

      <RetrievalConfigurationFields value={draft} onChange={setDraft} />

      {errors.length > 0 && (
        <div className="processing-config-validation" role="alert">
          {errors.map((message) => (
            <span key={message}>{message}</span>
          ))}
        </div>
      )}
      {Boolean(error) && <ErrorState error={error} />}
      {saved && saved.revision === view.revision && (
        <div className="processing-config-save-result">
          <CheckCircle2 size={16} />
          <span>修订 {saved.revision} 已成为当前检索配置。</span>
        </div>
      )}

      <footer className="processing-config-dialog-footer">
        <div className="retrieval-config-revision">
          <span>
            当前修订 {view.revision} · {formatTime(view.createdAt)} · {view.createdBy}
          </span>
          <span>
            指纹 <code title={view.fingerprint}>{view.fingerprint}</code>
          </span>
        </div>
        <div className="form-actions">
          <button
            type="button"
            disabled={!changed || pending}
            onClick={() => setDraft(cloneRetrievalConfiguration(view.configuration))}
          >
            <RotateCcw size={14} />
            放弃修改
          </button>
          <button type="button" disabled={pending} onClick={onClose}>
            关闭
          </button>
          <button
            className="primary-button"
            disabled={!changed || errors.length > 0 || pending}
          >
            {pending ? '保存中…' : `保存为修订 ${view.revision + 1}`}
          </button>
        </div>
      </footer>
    </form>
  )
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
