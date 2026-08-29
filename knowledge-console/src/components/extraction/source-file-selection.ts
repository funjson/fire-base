/** 文件选择结果；ZIP 与当前 Parser 配置不支持的扩展名分别计数。 */
export type SourceFileSelection = {
  accepted: File[]
  rejectedArchives: number
  rejectedUnsupported: number
}

/**
 * 按当前入口明确选择的 Parser 扩展名过滤文件。
 * `accept` 只是浏览器提示，拖放和“所有文件”仍必须经过同一校验。
 */
export function selectSourceFiles(
  incoming: File[],
  acceptedExtensions: string[],
): SourceFileSelection {
  const extensions = new Set(
    acceptedExtensions.map(normalizeExtension).filter(Boolean),
  )
  const accepted: File[] = []
  let rejectedArchives = 0
  let rejectedUnsupported = 0

  for (const file of incoming) {
    const normalizedName = file.name.toLowerCase()
    if (normalizedName.endsWith('.zip')) {
      rejectedArchives += 1
    } else if (
      extensions.size > 0 &&
      ![...extensions].some((extension) => normalizedName.endsWith(extension))
    ) {
      rejectedUnsupported += 1
    } else {
      accepted.push(file)
    }
  }
  return { accepted, rejectedArchives, rejectedUnsupported }
}

/** 生成不包含文件正文或路径的稳定选择错误说明。 */
export function sourceFileSelectionError(
  selection: SourceFileSelection,
  archiveExplanation: string,
) {
  const messages: string[] = []
  if (selection.rejectedArchives > 0) {
    messages.push(`已拒绝 ${selection.rejectedArchives} 个 ZIP；${archiveExplanation}`)
  }
  if (selection.rejectedUnsupported > 0) {
    messages.push(
      `已拒绝 ${selection.rejectedUnsupported} 个当前 Parser 配置不支持的文件。`,
    )
  }
  return messages.length > 0 ? messages.join(' ') : undefined
}

function normalizeExtension(value: string) {
  const normalized = value.trim().toLowerCase()
  if (!normalized) return ''
  return normalized.startsWith('.') ? normalized : `.${normalized}`
}
