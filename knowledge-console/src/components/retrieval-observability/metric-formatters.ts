/** 空样本统一显示为“暂无样本”，不得将 null 包装成 0。 */
export function formatPercent(value: number | null) {
  return value === null
    ? '暂无样本'
    : new Intl.NumberFormat('zh-CN', {
        style: 'percent',
        maximumFractionDigits: 1,
      }).format(value)
}

export function formatMillis(value: number | null) {
  if (value === null) return '暂无样本'
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)} s`
  return `${Math.round(value)} ms`
}

export function formatDecimal(value: number | null, digits = 1) {
  return value === null ? '暂无样本' : value.toFixed(digits)
}
