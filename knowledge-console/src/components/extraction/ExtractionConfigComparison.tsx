import type { ExtractionConfigSnapshot } from '../../lib/api'
import { ErrorState, LoadingState } from '../State'

/** 展示本次运行的不可变配置快照，并在选择基准运行时逐字段比较。 */
export function ExtractionConfigComparison({
  current,
  baseline,
  baselinePending,
  baselineError,
}: {
  current: ExtractionConfigSnapshot
  baseline?: ExtractionConfigSnapshot
  baselinePending: boolean
  baselineError: unknown
}) {
  const currentRows = rows(current)
  const baselineRows = baseline ? new Map(rows(baseline)) : undefined
  return (
    <details className="extraction-config-comparison" open={Boolean(baseline)}>
      <summary>
        配置快照 {baseline ? '基准 / 本次运行对比' : '（本次运行）'}
      </summary>
      {baselinePending && <LoadingState label="正在加载 Baseline 配置" />}
      {Boolean(baselineError) && <ErrorState error={baselineError} />}
      <div className="extraction-config-fingerprints">
        {baseline && (
          <span>
            基准 <code>{shortHash(baseline.fingerprint)}</code>
          </span>
        )}
        <span>
          本次 <code>{shortHash(current.fingerprint)}</code>
        </span>
      </div>
      <div className="extraction-config-table">
        <div className="header">
          <strong>配置字段</strong>
          {baseline && <strong>基准</strong>}
          <strong>本次</strong>
        </div>
        {currentRows.map(([label, value]) => {
          const baselineValue = baselineRows?.get(label)
          const changed = baselineRows && baselineValue !== value
          return (
            <div className={changed ? 'changed' : ''} key={label}>
              <span>{label}</span>
              {baseline && <code>{baselineValue ?? '—'}</code>}
              <code>{value}</code>
            </div>
          )
        })}
      </div>
      <small>
        指纹只包含有效处理语义；Space 固定版本、创建主体和固化时间不参与比较。
      </small>
    </details>
  )
}

function rows(value: ExtractionConfigSnapshot): Array<[string, string]> {
  return [
    ['处理版本总指纹', value.processingContract.fingerprint],
    ['抽取流程版本', value.processingContract.pipelineContract],
    ['来源规范化版本', value.processingContract.normalizerSchemaContract],
    ['本次来源规范化实现', value.normalizerContract],
    ['解析器实现', stableJson(value.processingContract.parserContracts)],
    ['清洗规则实现', value.processingContract.cleanerContract],
    ['切分与计数实现', value.processingContract.chunkerContract],
    ['Parser 选择', stableJson(value.parserSelections)],
    ['Cleaner', stableJson(value.cleaning)],
    ['Chunker', value.chunker.providerId],
    ['Tokenizer', value.chunker.tokenizerId],
    ['最小 / 目标 / 最大', `${value.chunker.minimumTokens} / ${value.chunker.targetTokens} / ${value.chunker.maximumTokens}`],
    ['Overlap', String(value.chunker.overlapTokens)],
    ['Provider 配置', value.chunker.providerConfigurationJson],
  ]
}

function stableJson(value: object) {
  return JSON.stringify(value, Object.keys(value).sort())
}

function shortHash(value: string) {
  return `${value.slice(0, 12)}…${value.slice(-6)}`
}
