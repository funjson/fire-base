import type { ExtractionPreview } from '../../lib/api'

/** 展示数据库返回的有界结构、正文片段以及 Element/Artifact 双坐标。 */
export function ExtractionArtifactPreview({ preview }: { preview: ExtractionPreview }) {
  return (
    <details className="extraction-artifact-preview">
      <summary>
        内容与边界预览 · Element {preview.totalElementCount} / Chunk{' '}
        {preview.totalChunkCount} {preview.truncated ? '· 已按安全上限截断' : ''}
      </summary>
      <div className="extraction-preview-columns">
        <section>
          <header>Element 结构</header>
          {preview.elements.map((element) => (
            <article key={element.id}>
              <div>
                <code>#{element.ordinal} {element.type}</code>
                <span>{element.sectionPath.join(' / ') || '根节点'}</span>
                <code>{element.cleaningAction} · {element.cleaningReasonCode}</code>
              </div>
              <small>
                Artifact {range(element.sourceRange)}
                {element.parentId ? ` · parent ${element.parentId.slice(0, 8)}` : ''}
              </small>
              <pre>{element.text}{element.textTruncated ? '…' : ''}</pre>
            </article>
          ))}
        </section>
        <section>
          <header>Chunk 边界</header>
          {preview.chunks.map((chunk) => (
            <article key={chunk.id}>
              <div>
                <code>Chunk #{chunk.ordinal}</code>
                <span>{chunk.sectionPath.join(' / ') || '根节点'}</span>
              </div>
              <pre>{chunk.text}{chunk.textTruncated ? '…' : ''}</pre>
              <div className="extraction-preview-spans">
                {chunk.sourceSpans.map((span, index) => (
                  <code key={`${span.elementId}:${span.startOffset}:${index}`}>
                    E {span.elementId.slice(0, 8)} [{span.startOffset},{span.endOffset})
                    {' → '}A {range(span.artifactRange)}
                  </code>
                ))}
              </div>
            </article>
          ))}
        </section>
      </div>
    </details>
  )
}

function range(value: { startOffset: number; endOffset: number; pageNumber: number | null } | null) {
  if (!value) return '未提供（不推测）'
  return `[${value.startOffset},${value.endOffset})${value.pageNumber ? ` · p${value.pageNumber}` : ''}`
}
