import { FileSearch } from 'lucide-react'
import type { AvailableParser, SpaceParserSelection } from '../../lib/api'
import {
  capabilityUnavailableReason,
  parserCapabilityLabel,
  parsersForMediaType,
} from './document-processing-config-model'

/** 展示媒体类型到 Parser 的映射，以及所选 Adapter 保证提供的输出能力。 */
export function ParserConfigurationSection({
  selections,
  availableParsers,
  disabled,
  onChange,
}: {
  selections: SpaceParserSelection[]
  availableParsers: AvailableParser[]
  disabled: boolean
  onChange: (selections: SpaceParserSelection[]) => void
}) {
  return (
    <section className="processing-config-section">
      <header>
        <FileSearch size={18} />
        <div>
          <h3>Parser 映射</h3>
          <p>
            能力目录包含 {availableParsers.length} 个 Parser，其中{' '}
            {availableParsers.filter((parser) => parser.available).length} 个当前可用；
            每种规范媒体类型只能选择一个解析器。
          </p>
        </div>
      </header>
      <div className="parser-selection-list">
        {selections.map((selection, index) => {
          const options = parsersForMediaType(
            availableParsers,
            selection.mediaType,
          )
          const selectedParser = options.find(
            (parser) => parser.id === selection.parserId,
          )
          const selectedKnown = Boolean(selectedParser)
          return (
            <label className="parser-selection-row" key={selection.mediaType}>
              <span>
                <strong>{mediaTypeLabel(selection.mediaType)}</strong>
                <code>{selection.mediaType}</code>
              </span>
              <div className="parser-selection-control">
                <select
                  aria-label={`${selection.mediaType} Parser`}
                  value={selection.parserId}
                  disabled={disabled}
                  onChange={(event) =>
                    onChange(
                      selections.map((item, itemIndex) =>
                        itemIndex === index
                          ? { ...item, parserId: event.target.value }
                          : item,
                      ),
                    )
                  }
                >
                  {!selectedKnown && (
                    <option value={selection.parserId} disabled>
                      {selection.parserId}（当前部署不可用）
                    </option>
                  )}
                  {options.map((parser) => (
                    <option
                      value={parser.id}
                      key={parser.id}
                      disabled={!parser.available}
                    >
                      {parser.id} · {parser.version}
                      {!parser.available ? '（当前部署不可用）' : ''}
                    </option>
                  ))}
                </select>
                {selectedParser && !selectedParser.available && (
                  <small>
                    {capabilityUnavailableReason(
                      selectedParser.unavailableReason,
                    )}
                  </small>
                )}
                <ParserCapabilities parser={selectedParser} />
              </div>
            </label>
          )
        })}
      </div>
      <ParserCapabilityCatalog parsers={availableParsers} />
    </section>
  )
}

function ParserCapabilityCatalog({ parsers }: { parsers: AvailableParser[] }) {
  return (
    <div
      className="processing-capability-catalog"
      aria-label="Parser 能力目录"
    >
      <div className="processing-capability-catalog-title">
        Parser 能力目录（{parsers.length}）
      </div>
      <div className="processing-capability-cards">
        {parsers.map((parser) => (
          <article key={parser.id}>
            <div>
              <strong>{parser.id}</strong>
              {parser.defaultSelection && <span>默认</span>}
              {!parser.available && <span>不可用</span>}
            </div>
            <code>{parser.canonicalMediaType}</code>
            <small>版本 {parser.version}</small>
            {!parser.available && (
              <small>
                {capabilityUnavailableReason(parser.unavailableReason)}
              </small>
            )}
            <ParserCapabilities parser={parser} />
          </article>
        ))}
      </div>
    </div>
  )
}

function ParserCapabilities({ parser }: { parser: AvailableParser | undefined }) {
  if (!parser) {
    return (
      <div className="parser-capability-list unavailable">
        当前 Parser 的输出能力清单不可用
      </div>
    )
  }
  return (
    <div className="parser-capability-list" aria-label="Parser 输出能力">
      {parser.outputCapabilities.map((capability) => (
        <span title={capability} key={capability}>
          {parserCapabilityLabel(capability)}
        </span>
      ))}
    </div>
  )
}

function mediaTypeLabel(mediaType: string) {
  const labels: Record<string, string> = {
    'text/markdown': 'Markdown',
    'text/plain': '纯文本',
    'text/html': 'HTML',
    'application/xhtml+xml': 'XHTML',
    'application/pdf': 'PDF',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
      'DOCX',
  }
  return labels[mediaType.toLowerCase()] || mediaType
}
