import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { OnlineOverviewPoint } from '../../lib/api'
import { formatBucket, toTrendPoint } from './overview-trend-model'
import {
  ChartEmpty,
  LatencyTooltip,
  MetricTooltip,
} from './overview-trend-support'

export function RuntimeTrend({ data }: { data: OnlineOverviewPoint[] }) {
  if (data.length === 0) return <ChartEmpty />
  const chartData = data.map(toTrendPoint)
  return (
    <div className="observability-chart" aria-label="运行健康趋势图">
      <ResponsiveContainer width="100%" height={310}>
        <ComposedChart data={chartData} margin={{ top: 10, right: 20, left: 0, bottom: 2 }}>
          <CartesianGrid stroke="#1b2a43" strokeDasharray="4 4" />
          <XAxis
            dataKey="bucketTimestamp"
            type="number"
            domain={['dataMin', 'dataMax']}
            tickFormatter={formatBucket}
            stroke="#71839f"
            fontSize={9}
          />
          <YAxis
            yAxisId="rate"
            domain={[0, 1]}
            tickFormatter={(value: number) => `${Math.round(value * 100)}%`}
            stroke="#71839f"
            fontSize={9}
          />
          <YAxis
            yAxisId="count"
            orientation="right"
            allowDecimals={false}
            stroke="#71839f"
            fontSize={9}
          />
          <Tooltip content={<MetricTooltip />} />
          <Legend wrapperStyle={{ fontSize: 10 }} />
          <Bar
            yAxisId="count"
            dataKey="requestCount"
            name="已观测执行"
            fill="rgba(119, 168, 255, 0.28)"
            radius={[3, 3, 0, 0]}
          />
          <Line
            yAxisId="rate"
            dataKey="technicalSuccessRate"
            name="技术成功率"
            type="monotone"
            stroke="#63d6b5"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
          <Line
            yAxisId="rate"
            dataKey="degradedRate"
            name="降级率"
            type="monotone"
            stroke="#f4b860"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
          <Line
            yAxisId="rate"
            dataKey="terminalObservationRate"
            name="终态形成率"
            type="monotone"
            stroke="#d6b36a"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
          <Line
            yAxisId="rate"
            dataKey="observationCompleteRate"
            name="观测完整率"
            type="monotone"
            stroke="#77a8ff"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

export function LatencyTrend({ data }: { data: OnlineOverviewPoint[] }) {
  if (data.length === 0) return <ChartEmpty />
  const chartData = data.map(toTrendPoint)
  return (
    <div className="observability-chart" aria-label="端到端延迟趋势图">
      <ResponsiveContainer width="100%" height={310}>
        <ComposedChart data={chartData} margin={{ top: 10, right: 20, left: 0, bottom: 2 }}>
          <CartesianGrid stroke="#1b2a43" strokeDasharray="4 4" />
          <XAxis
            dataKey="bucketTimestamp"
            type="number"
            domain={['dataMin', 'dataMax']}
            tickFormatter={formatBucket}
            stroke="#71839f"
            fontSize={9}
          />
          <YAxis stroke="#71839f" fontSize={9} unit=" ms" />
          <Tooltip content={<LatencyTooltip />} />
          <Legend wrapperStyle={{ fontSize: 10 }} />
          <Line
            dataKey="p50LatencyMillis"
            name="P50"
            type="monotone"
            stroke="#77a8ff"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
          <Line
            dataKey="p95LatencyMillis"
            name="P95"
            type="monotone"
            stroke="#f4b860"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

export function CoverageTrend({ data }: { data: OnlineOverviewPoint[] }) {
  if (data.length === 0) return <ChartEmpty />
  const chartData = data.map(toTrendPoint)
  return (
    <div className="observability-chart" aria-label="代理质量趋势图">
      <ResponsiveContainer width="100%" height={310}>
        <ComposedChart data={chartData} margin={{ top: 10, right: 20, left: 0, bottom: 2 }}>
          <CartesianGrid stroke="#1b2a43" strokeDasharray="4 4" />
          <XAxis
            dataKey="bucketTimestamp"
            type="number"
            domain={['dataMin', 'dataMax']}
            tickFormatter={formatBucket}
            stroke="#71839f"
            fontSize={9}
          />
          <YAxis
            domain={[0, 1]}
            tickFormatter={(value: number) => `${Math.round(value * 100)}%`}
            stroke="#71839f"
            fontSize={9}
          />
          <Tooltip content={<MetricTooltip />} />
          <Legend wrapperStyle={{ fontSize: 10 }} />
          <Line
            dataKey="firstCoverageSufficientRate"
            name="首轮充分率"
            type="monotone"
            stroke="#a892ff"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
          <Line
            dataKey="finalCoverageSufficientRate"
            name="最终充分率"
            type="monotone"
            stroke="#63d6b5"
            strokeWidth={2}
            connectNulls={false}
            dot={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}
