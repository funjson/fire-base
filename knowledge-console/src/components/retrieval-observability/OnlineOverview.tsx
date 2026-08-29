import { useQuery } from '@tanstack/react-query'
import type { RetrievalObservabilityFilter } from '../../lib/api'
import { useApi } from '../../lib/use-api'
import { EmptyState, ErrorState, LoadingState, Panel } from '../State'
import {
  ProxyMetricSummary,
  RuntimeMetricSummary,
  VersionScopeNotice,
} from './OverviewMetricSummary'
import {
  CoverageTrend,
  LatencyTrend,
  RuntimeTrend,
} from './OverviewTrendCharts'

type OnlineOverviewProps = {
  filter: RetrievalObservabilityFilter
  rangeLabel: string
  refreshKey: number
}

/** 在线总览只负责查询编排，指标摘要和趋势图由各自展示组件承担。 */
export function OnlineOverview({
  filter,
  rangeLabel,
  refreshKey,
}: OnlineOverviewProps) {
  const api = useApi()
  const overview = useQuery({
    queryKey: ['retrieval-observability', 'online', 'overview', filter, refreshKey],
    queryFn: () => api.onlineRetrievalOverview(filter),
  })

  if (overview.isPending) {
    return <LoadingState label="正在聚合在线检索指标" />
  }
  if (overview.error) return <ErrorState error={overview.error} />

  const value = overview.data

  return (
    <>
      <RuntimeMetricSummary value={value} rangeLabel={rangeLabel} />

      <VersionScopeNotice
        requestCount={value.requestCount}
        configFingerprints={value.configFingerprints}
        dataIndexVersions={value.dataIndexVersions}
      />

      <ProxyMetricSummary value={value} />

      {value.requestCount === 0 ? (
        <Panel>
          <EmptyState
            title="当前窗口没有 ONLINE 请求"
            description="可以扩大统计窗口或切换到全部 Space。测试广场和离线评测请求不会进入这里。"
          />
        </Panel>
      ) : (
        <div className="observability-chart-grid">
          <Panel
            title="运行健康趋势"
            description={`聚合粒度 ${value.granularity}；仅绘制有请求的 UTC 桶，空桶不补 0`}
          >
            <RuntimeTrend data={value.series} />
          </Panel>
          <Panel
            title="端到端延迟趋势"
            description="P50/P95 只使用已形成终态的延迟样本"
          >
            <LatencyTrend data={value.series} />
          </Panel>
          <Panel
            title="代理质量趋势"
            description="首轮与最终 Coverage 的充分率变化"
          >
            <CoverageTrend data={value.series} />
          </Panel>
        </div>
      )}
    </>
  )
}
