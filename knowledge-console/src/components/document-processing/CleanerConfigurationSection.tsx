import { Filter } from 'lucide-react'
import type {
  DocumentCleaningAction,
  SpaceDocumentCleaningConfiguration,
} from '../../lib/api'

const cleaningRules: Array<{
  key: keyof SpaceDocumentCleaningConfiguration
  role: string
  label: string
  description: string
}> = [
  {
    key: 'header',
    role: 'HEADER',
    label: '页眉',
    description: '治理 Parser 明确识别出的页眉，例如每页重复出现的章节或机构名称。',
  },
  {
    key: 'footer',
    role: 'FOOTER',
    label: '页脚',
    description: '治理 Parser 明确识别出的页脚，例如版权声明和每页重复说明。',
  },
  {
    key: 'pageNumber',
    role: 'PAGE_NUMBER',
    label: '页码',
    description: '治理独立页码元素；引用所需的来源页码不会因此丢失。',
  },
  {
    key: 'watermark',
    role: 'WATERMARK',
    label: '水印',
    description: '治理 Parser 明确识别出的水印文本，例如密级或内部使用标识。',
  },
  {
    key: 'frontMatter',
    role: 'FRONT_MATTER',
    label: '前置元数据',
    description: '治理 Markdown 等格式的 Front Matter，不影响普通正文段落。',
  },
]

const cleaningActions: Array<{
  value: DocumentCleaningAction
  label: string
}> = [
  { value: 'KEEP', label: '保留并参与检索' },
  { value: 'REMOVE', label: '移除' },
  { value: 'METADATA_ONLY', label: '仅保留为元数据' },
]

/** 配置 Parser 已识别文档角色的确定性治理方式。 */
export function CleanerConfigurationSection({
  cleaning,
  disabled,
  onChange,
}: {
  cleaning: SpaceDocumentCleaningConfiguration
  disabled: boolean
  onChange: (cleaning: SpaceDocumentCleaningConfiguration) => void
}) {
  return (
    <section className="processing-config-section">
      <header>
        <Filter size={18} />
        <div>
          <h3>Cleaner 内容治理</h3>
          <p>只处理 Parser 明确标注的文档角色，不对正文执行模糊匹配删除。</p>
        </div>
      </header>
      <div className="cleaning-rule-list">
        {cleaningRules.map((rule) => (
          <label className="cleaning-rule-row" key={rule.key}>
            <span>
              <strong>{rule.label}</strong>
              <code>{rule.role}</code>
              <small>{rule.description}</small>
            </span>
            <select
              aria-label={`${rule.label}处理方式`}
              value={cleaning[rule.key]}
              disabled={disabled}
              onChange={(event) =>
                onChange({
                  ...cleaning,
                  [rule.key]: event.target.value as DocumentCleaningAction,
                })
              }
            >
              {cleaningActions.map((action) => (
                <option value={action.value} key={action.value}>
                  {action.label}
                </option>
              ))}
            </select>
          </label>
        ))}
      </div>
      <div className="cleaning-action-help">
        <span>
          “移除”不会进入后续切分；“仅保留为元数据”不参与检索，但保留治理结果。
          清洗后如果没有任何可索引正文，本次摄取会明确失败，不会发布空知识文档。
        </span>
      </div>
    </section>
  )
}
