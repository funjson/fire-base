import { CircleDashed, ShieldAlert } from 'lucide-react'
import { Panel } from '../State'

const gates = [
  ['Parse 成功率', '100%', '任一文件解析失败都不得通过'],
  ['Token 越界', '0', '精确 Tokenizer 下不允许 Chunk 突破硬上限'],
  ['无效 SourceSpan', '0', '所有引用必须回到有效原文范围'],
  ['源内容对账', '100%', '保留、移除和元数据范围必须可解释'],
  ['静默截断', '0', '文件或元素不得无诊断地丢失'],
  ['语义降级', '0', '语义策略失败不得在同版本下静默回退'],
] as const

/** 作为验收依据的静态硬门禁说明；不表示当前 Run 已经通过。 */
export function ExtractionAcceptanceGates() {
  return (
    <Panel
      title="企业级验收硬门禁（规则说明）"
      description="以下是目标值，不是当前 Run 的通过结果；未由后端返回的指标一律不判定为通过。"
    >
      <div className="extraction-gate-intro">
        <ShieldAlert size={18} />
        <span>
          任一硬门禁失败，该配置都不应进入正式重处理和索引发布。
        </span>
      </div>
      <div className="extraction-gate-list">
        {gates.map(([name, target, explanation]) => (
          <div key={name}>
            <CircleDashed size={15} />
            <span>
              <strong>{name}</strong>
              <small>{explanation}</small>
            </span>
            <code>目标 {target}</code>
          </div>
        ))}
      </div>
    </Panel>
  )
}
