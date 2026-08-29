import type { OnlineOverviewPoint } from '../../lib/api'

/** 图表数据只保留绘图所需的低基数聚合值及其真实分母。 */
export type TrendPoint = {
  bucketStart: string
  bucketTimestamp: number
  requestCount: number
  technicalSuccessRate: number | null
  degradedRate: number | null
  observationCompleteRate: number | null
  terminalObservationRate: number | null
  firstCoverageSufficientRate: number | null
  finalCoverageSufficientRate: number | null
  p50LatencyMillis: number | null
  p95LatencyMillis: number | null
  latencySampleCount: number
  denominators: Record<string, number>
}

export function toTrendPoint(value: OnlineOverviewPoint): TrendPoint {
  return {
    bucketStart: value.bucketStart,
    bucketTimestamp: new Date(value.bucketStart).getTime(),
    requestCount: value.requestCount,
    technicalSuccessRate: value.technicalSuccessRate.value,
    degradedRate: value.degradedRate.value,
    observationCompleteRate: value.observationCompleteRate.value,
    terminalObservationRate: value.terminalObservationRate.value,
    firstCoverageSufficientRate: value.firstCoverageSufficientRate.value,
    finalCoverageSufficientRate: value.finalCoverageSufficientRate.value,
    p50LatencyMillis: value.latencyMillis.p50,
    p95LatencyMillis: value.latencyMillis.p95,
    latencySampleCount: value.latencyMillis.sampleCount,
    denominators: {
      technicalSuccessRate: value.technicalSuccessRate.denominator,
      degradedRate: value.degradedRate.denominator,
      observationCompleteRate: value.observationCompleteRate.denominator,
      terminalObservationRate: value.terminalObservationRate.denominator,
      firstCoverageSufficientRate:
        value.firstCoverageSufficientRate.denominator,
      finalCoverageSufficientRate:
        value.finalCoverageSufficientRate.denominator,
    },
  }
}

export function formatBucket(value: string | number) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
