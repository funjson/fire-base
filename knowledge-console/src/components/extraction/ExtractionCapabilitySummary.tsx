import { Braces, Eraser, ScanText, Split } from 'lucide-react'
import type { PropsWithChildren, ReactNode } from 'react'
import { Panel, StatusBadge } from '../State'
import type { SpaceDocumentProcessingConfig } from '../../lib/api'

/** 用与真实执行相同的 Space 配置展示本次抽取的能力基线。 */
export function ExtractionCapabilitySummary({
  config,
  onConfigure,
}: {
  config: SpaceDocumentProcessingConfig
  onConfigure: () => void
}) {
  const chunker = config.availableChunkers.find(
    (candidate) => candidate.id === config.chunker.providerId,
  )
  const tokenizer = config.availableTokenizers.find(
    (candidate) => candidate.id === config.chunker.tokenizerId,
  )

  return (
    <Panel
      title="当前抽取能力"
      description={`Space 固定配置版本 ${config.version}；每次运行都会固化实际执行配置的不可变快照。`}
      action={
        <button onClick={onConfigure}>
          查看 Space 配置
        </button>
      }
    >
      <div className="extraction-capability-grid">
        <CapabilityCard
          icon={<ScanText size={17} />}
          title="Parser"
          status={`能力目录 ${config.availableParsers.length} / 可用 ${config.availableParsers.filter((parser) => parser.available).length}`}
        >
          <ul>
            {config.parserSelections.map((selection) => (
              <li key={selection.mediaType}>
                <code>{shortMediaType(selection.mediaType)}</code>
                <span>{selection.parserId || '未选择'}</span>
              </li>
            ))}
          </ul>
        </CapabilityCard>
        <CapabilityCard
          icon={<Eraser size={17} />}
          title="Cleaner"
          status="确定性规则"
        >
          <ul>
            {Object.entries(config.cleaning).map(([role, action]) => (
              <li key={role}>
                <code>{cleanerRoleLabel(role)}</code>
                <span>{cleaningActionLabel(action)}</span>
              </li>
            ))}
          </ul>
        </CapabilityCard>
        <CapabilityCard
          icon={<Split size={17} />}
          title="Chunker"
          status={chunker?.available ? '可用' : '不可用'}
          warning={!chunker?.available}
        >
          <strong>{config.chunker.providerId}</strong>
          <span>
            {config.chunker.minimumTokens} / {config.chunker.targetTokens} /{' '}
            {config.chunker.maximumTokens} Token
          </span>
          <small>
            Overlap {config.chunker.overlapTokens}
            {!chunker?.available &&
              ` · ${chunker?.unavailableReason || '当前部署无法执行'}`}
          </small>
        </CapabilityCard>
        <CapabilityCard
          icon={<Braces size={17} />}
          title="Tokenizer"
          status={
            !tokenizer?.available
              ? '不可用'
              : tokenizer.exactModelTokens
                ? '模型精确'
                : '预算估算'
          }
          warning={!tokenizer?.available || !tokenizer.exactModelTokens}
        >
          <strong>{config.chunker.tokenizerId}</strong>
          <span>{tokenizer?.version || '当前部署不可用'}</span>
          <small>
            {!tokenizer?.available
              ? tokenizer?.unavailableReason || '当前部署不可执行'
              : tokenizer?.modelProfileId
              ? `运维固定绑定 ${tokenizer.modelProfileId}`
              : tokenizer?.description || '无能力说明'}
          </small>
        </CapabilityCard>
      </div>
    </Panel>
  )
}

function CapabilityCard({
  icon,
  title,
  status,
  warning = false,
  children,
}: PropsWithChildren<{
  icon: ReactNode
  title: string
  status: string
  warning?: boolean
}>) {
  return (
    <article className="extraction-capability-card">
      <header>
        <div>
          {icon}
          <strong>{title}</strong>
        </div>
        <StatusBadge value={warning ? `WARNING · ${status}` : status} />
      </header>
      <div className="extraction-capability-body">{children}</div>
    </article>
  )
}

function shortMediaType(value: string) {
  if (value.includes('wordprocessingml')) return 'DOCX'
  return value.split('/').at(-1)?.toUpperCase() || value
}

function cleanerRoleLabel(value: string) {
  const labels: Record<string, string> = {
    header: '页眉',
    footer: '页脚',
    pageNumber: '页码',
    watermark: '水印',
    frontMatter: 'Front Matter',
  }
  return labels[value] || value
}

function cleaningActionLabel(value: string) {
  const labels: Record<string, string> = {
    KEEP: '保留',
    REMOVE: '移除',
    METADATA_ONLY: '仅元数据',
  }
  return labels[value] || value
}
