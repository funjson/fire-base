/** 执行标识只在列表中缩写；title 与详情仍保留完整安全标识。 */
export function shortExecutionId(value: string) {
  return value.length > 12 ? value.slice(0, 12) : value
}

export function formatExecutionTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}

export function formatExecutionDuration(value: number | null) {
  if (value === null) return '尚未终止'
  return value >= 1_000 ? `${(value / 1_000).toFixed(2)} s` : `${value} ms`
}

export function nullableExecutionCount(value: number | null) {
  return value === null ? '—' : value
}
