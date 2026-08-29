import type { DocumentProcessingContract } from '../../lib/api'

/** 展示 Space 创建时由服务端固化的真实处理实现合同。 */
export function ProcessingContractDetails({
  contract,
  runtimeContractMatched,
}: {
  contract: DocumentProcessingContract
  runtimeContractMatched: boolean
}) {
  return (
    <section
      className="processing-config-section processing-contract-section"
      aria-labelledby="processing-contract-title"
    >
      <header>
        <div>
          <h3 id="processing-contract-title">固化处理版本</h3>
          <p>
            这些值记录创建 Space 时实际安装的实现；即使名称不变，实现升级也会生成新版本。
          </p>
        </div>
        <div className="processing-contract-statuses">
          <span className="processing-contract-badge">服务端生成</span>
          <span
            className={`processing-contract-badge ${runtimeContractMatched ? 'matched' : 'mismatched'}`}
          >
            {runtimeContractMatched ? '当前部署匹配' : '当前部署不匹配'}
          </span>
        </div>
      </header>
      {!runtimeContractMatched && (
        <p className="processing-contract-mismatch-notice" role="alert">
          当前部署中的处理实现已变化或缺失。已有数据仍可查询并完成已有投影，
          但不能使用 Space 固化版本继续正式摄取；完整的本次测试配置仍可单独验证。
          如需正式使用当前实现，请创建新 Space 并重新摄取。
        </p>
      )}
      <dl className="processing-contract-grid">
        <ContractRow label="总指纹" value={contract.fingerprint} emphasized />
        <ContractRow label="抽取流程版本" value={contract.pipelineContract} />
        <ContractRow
          label="来源规范化版本"
          value={contract.normalizerSchemaContract}
        />
        {Object.entries(contract.parserContracts).map(([mediaType, value]) => (
          <ContractRow
            key={mediaType}
            label={`解析器 · ${mediaType}`}
            value={value}
          />
        ))}
        <ContractRow label="清洗规则实现" value={contract.cleanerContract} />
        <ContractRow
          label="切分与计数实现"
          value={contract.chunkerContract}
        />
      </dl>
    </section>
  )
}

function ContractRow({
  label,
  value,
  emphasized = false,
}: {
  label: string
  value: string
  emphasized?: boolean
}) {
  return (
    <div className={emphasized ? 'fingerprint' : ''}>
      <dt>{label}</dt>
      <dd>
        <code>{value}</code>
      </dd>
    </div>
  )
}
