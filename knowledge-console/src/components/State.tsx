import { AlertTriangle, Inbox, LoaderCircle } from 'lucide-react'
import type { PropsWithChildren, ReactNode } from 'react'
import { ApiError } from '../lib/api'

export function LoadingState({ label = '正在加载数据' }: { label?: string }) {
  return (
    <div className="state-card">
      <LoaderCircle className="spin" size={20} />
      <span>{label}</span>
    </div>
  )
}

export function ErrorState({ error }: { error: unknown }) {
  const apiError = error instanceof ApiError ? error : undefined
  return (
    <div className="state-card state-error" role="alert">
      <AlertTriangle size={20} />
      <div className="state-error-details">
        <strong>{error instanceof Error ? error.message : '数据加载失败'}</strong>
        {apiError && (
          <span>
            {apiError.code} · HTTP {apiError.status}
          </span>
        )}
        {apiError?.requestId && (
          <code>Request ID: {apiError.requestId}</code>
        )}
      </div>
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <Inbox size={28} />
      <strong>{title}</strong>
      <span>{description}</span>
      {action}
    </div>
  )
}

export function Panel({
  title,
  description,
  action,
  children,
  className = '',
}: PropsWithChildren<{
  title?: string
  description?: string
  action?: ReactNode
  className?: string
}>) {
  return (
    <section className={`panel ${className}`}>
      {(title || action) && (
        <header className="panel-header">
          <div>
            {title && <h2>{title}</h2>}
            {description && <p>{description}</p>}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  )
}

export function StatusBadge({ value }: { value: string }) {
  const normalized = value.toLowerCase()
  const tone =
    normalized.includes('succeed') ||
    normalized.includes('active') ||
    normalized === 'ready'
      ? 'success'
      : normalized.includes('fail') ||
          normalized.includes('dead') ||
          normalized.includes('error')
        ? 'danger'
        : normalized.includes('pending') ||
            normalized.includes('running') ||
            normalized.includes('retry') ||
            normalized.includes('leased')
          ? 'warning'
          : 'neutral'
  return <span className={`status status-${tone}`}>{value}</span>
}
