import { useQuery } from '@tanstack/react-query'
import type { RetrievalObservabilityFilter } from '../../lib/api'
import { useApi } from '../../lib/use-api'
import { EmptyState, ErrorState, LoadingState, Panel } from '../State'
import {
  BranchDiagnosticsTable,
  ChainDiagnosticsTable,
  SpecialStageCards,
} from './StageDetailViews'
import { StageLatencyChart, StageMatrix } from './StageExecutionViews'

type StageDiagnosticsProps = {
  filter: RetrievalObservabilityFilter
  rangeLabel: string
  refreshKey: number
}

/** 分层诊断只编排安全聚合读模型，不把未执行的可选阶段补成传统漏斗。 */
export function StageDiagnostics({
  filter,
  rangeLabel,
  refreshKey,
}: StageDiagnosticsProps) {
  const api = useApi()
  const diagnostics = useQuery({
    queryKey: ['retrieval-observability', 'online', 'stages', filter, refreshKey],
    queryFn: () => api.retrievalStageDiagnostics(filter),
  })

  if (diagnostics.isPending) {
    return <LoadingState label="正在聚合检索阶段诊断" />
  }
  if (diagnostics.error) return <ErrorState error={diagnostics.error} />

  const value = diagnostics.data
  const hasData =
    value.stageRows.length > 0 ||
    value.branchRows.length > 0 ||
    value.chainRows.length > 0 ||
    value.fusion.executionCount > 0 ||
    value.rerank.executionCount > 0 ||
    value.coverage.executionCount > 0

  if (!hasData) {
    return (
      <Panel>
        <EmptyState
          title="当前窗口没有阶段诊断数据"
          description="可以扩大统计窗口或切换到全部 Space。未执行的可选阶段不会生成虚拟记录。"
        />
      </Panel>
    )
  }

  return (
    <>
      <div className="observability-section-heading">
        <div>
          <span className="truth-badge runtime">运行事实</span>
          <h3>执行路径与阶段耗时</h3>
        </div>
        <p>{rangeLabel} · 每一行均显示自身执行样本</p>
      </div>

      <div className="observability-chart-grid stage-grid">
        <Panel
          title="阶段耗时分布"
          description="P50/P95 来自实际执行样本，不跨阶段累加"
        >
          <StageLatencyChart rows={value.stageRows} />
        </Panel>
        <Panel
          title="阶段执行矩阵"
          description="可选阶段按自身执行次数计算成功率"
        >
          <StageMatrix rows={value.stageRows} />
        </Panel>
      </div>

      <Panel
        title="召回分支"
        description="按策略、组件模型与索引版本分组，避免不同实现互相平均"
      >
        <BranchDiagnosticsTable rows={value.branchRows} />
      </Panel>

      <SpecialStageCards value={value} />

      <Panel
        title="优化 Chain 节点"
        description="Coverage 增量属于在线代理信号，真实能力增益需在离线评测中确认"
      >
        <ChainDiagnosticsTable rows={value.chainRows} />
      </Panel>
    </>
  )
}
