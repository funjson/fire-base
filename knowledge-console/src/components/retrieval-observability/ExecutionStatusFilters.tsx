import { Search } from 'lucide-react'
import { type FormEvent, useState } from 'react'

/** 执行终态筛选只负责表单草稿，URL 生效规则由列表编排组件维护。 */
export function ExecutionStatusFilters({
  terminalStatus,
  stopReason,
  onApply,
}: {
  terminalStatus?: string
  stopReason?: string
  onApply: (terminalStatus: string, stopReason: string) => void
}) {
  const [terminalValue, setTerminalValue] = useState(terminalStatus ?? '')
  const [reasonValue, setReasonValue] = useState(stopReason ?? '')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    onApply(terminalValue, reasonValue)
  }
  return (
    <form className="execution-filter-row" onSubmit={submit}>
      <label>
        <span>业务终态</span>
        <select
          aria-label="业务终态"
          value={terminalValue}
          onChange={(event) => setTerminalValue(event.target.value)}
        >
          <option value="">全部终态</option>
          <option value="SUFFICIENT">证据充分</option>
          <option value="INSUFFICIENT">证据不足</option>
          <option value="NOT_EVALUATED">未评估</option>
          <option value="EVIDENCE_REQUIREMENTS_MISSING">缺少证据要求</option>
          <option value="CHECK_FAILED">Coverage 失败</option>
          <option value="TECHNICAL_FAILED">技术失败</option>
        </select>
      </label>
      <label>
        <span>停止原因</span>
        <select
          aria-label="停止原因"
          value={reasonValue}
          onChange={(event) => setReasonValue(event.target.value)}
        >
          <option value="">全部原因</option>
          <option value="SUFFICIENCY_THRESHOLD_REACHED">达到充分阈值</option>
          <option value="COVERAGE_DISABLED">Coverage 已关闭</option>
          <option value="EVIDENCE_REQUIREMENTS_MISSING">缺少证据要求</option>
          <option value="COVERAGE_CHECK_FAILED">Coverage 检查失败</option>
          <option value="RETRIEVAL_BUDGET_EXHAUSTED">检索预算耗尽</option>
          <option value="OPTIMIZATION_CHAIN_EXHAUSTED">优化 Chain 已耗尽</option>
          <option value="NO_APPLICABLE_OPTIMIZATION_NODE">没有可执行节点</option>
          <option value="TECHNICAL_FAILURE">技术失败</option>
        </select>
      </label>
      <button type="submit" className="secondary-button">应用状态筛选</button>
      <div className="execution-filter-hint">
        <Search size={15} />
        <span>查询正文不会保存；可用 Request ID 从执行详情继续定位。</span>
      </div>
    </form>
  )
}
