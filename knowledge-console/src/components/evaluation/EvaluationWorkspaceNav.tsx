import { FlaskConical, TestTubeDiagonal } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { useAuth } from '../../lib/auth-context'

/**
 * 评测中心的二级导航。测试广场按当前用户权限执行检索，数据集管理仅对知识管理员开放。
 */
export function EvaluationWorkspaceNav() {
  const auth = useAuth()
  const isAdmin = auth.roles.includes('knowledge-admin')

  return (
    <nav className="evaluation-workspace-nav" aria-label="评测中心功能">
      <NavLink to="/evaluations/playground">
        <TestTubeDiagonal size={16} />
        <span>
          <strong>测试广场</strong>
          <small>单次查询调参和分层观测</small>
        </span>
      </NavLink>
      {isAdmin && (
        <NavLink to="/evaluations/datasets">
          <FlaskConical size={16} />
          <span>
            <strong>数据集评测</strong>
            <small>可复现回归和质量门禁</small>
          </span>
        </NavLink>
      )}
    </nav>
  )
}
