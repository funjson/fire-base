import { useMutation, useQuery } from '@tanstack/react-query'
import { Network, Search } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { EmptyState, ErrorState, Panel } from '../components/State'
import type { GraphEdge, GraphNode } from '../lib/api'
import { useApi } from '../lib/use-api'

type PositionedNode = GraphNode & { x: number; y: number }

export function GraphPage() {
  const api = useApi()
  const spaces = useQuery({ queryKey: ['spaces'], queryFn: api.spaces })
  const [query, setQuery] = useState('')
  const [spaceId, setSpaceId] = useState('')
  const [maxHops, setMaxHops] = useState(2)
  const search = useMutation({ mutationFn: api.graphSearch })

  if (spaces.error) return <ErrorState error={spaces.error} />

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalized = query.trim()
    if (!normalized) return
    search.mutate({
      query: normalized,
      spaceIds: spaceId ? [spaceId] : [],
      maxHops,
      limit: 100,
    })
  }

  const edges = search.data?.edges ?? []
  const nodes = positionNodes(edges)

  return (
    <div className="page-stack">
      <div className="page-intro">
        <div>
          <span className="eyebrow">SOURCE-BACKED GRAPH</span>
          <h2>企业知识关系探索</h2>
          <p>按实体、系统或概念探索有限深度关系；每条边都必须回到活动修订中的原始 Chunk。</p>
        </div>
      </div>

      <Panel>
        <form className="graph-search-form" onSubmit={submit}>
          <label className="graph-query-field">
            实体或关系问题
            <input
              value={query}
              maxLength={16_000}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="例如：订单服务依赖哪些组件？"
            />
          </label>
          <label>
            知识空间
            <select value={spaceId} onChange={(event) => setSpaceId(event.target.value)}>
              <option value="">全部可管理空间</option>
              {spaces.data?.map((space) => (
                <option key={space.id} value={space.id}>{space.name}</option>
              ))}
            </select>
          </label>
          <label>
            最大跳数
            <select
              value={maxHops}
              onChange={(event) => setMaxHops(Number(event.target.value))}
            >
              <option value={1}>1 跳</option>
              <option value={2}>2 跳</option>
              <option value={3}>3 跳</option>
            </select>
          </label>
          <button
            className="primary-button"
            disabled={!query.trim() || search.isPending}
            type="submit"
          >
            <Search size={17} /> {search.isPending ? '探索中…' : '探索关系'}
          </button>
        </form>
        {search.error && <ErrorState error={search.error} />}
      </Panel>

      {!search.data ? (
        <EmptyState
          title="输入实体开始探索"
          description="图谱只使用已发布、仍为活动修订且当前主体有权访问的来源。"
        />
      ) : !edges.length ? (
        <EmptyState
          title="没有找到可验证关系"
          description="可以尝试实体别名、缩小知识空间，或先检查 Graph 投影是否完成。"
        />
      ) : (
        <>
          <Panel>
            <div className="panel-heading">
              <div><Network size={18} /><strong>关系视图</strong></div>
              <span>{nodes.length} 个节点 · {edges.length} 条来源关系</span>
            </div>
            <GraphCanvas nodes={nodes} edges={edges} />
          </Panel>
          <Panel>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>来源实体</th>
                    <th>关系</th>
                    <th>目标实体</th>
                    <th>跳数</th>
                    <th>证据来源</th>
                    <th>置信度</th>
                  </tr>
                </thead>
                <tbody>
                  {edges.map((edge) => (
                    <tr key={edge.relationId}>
                      <td><strong>{edge.source.name}</strong><span>{edge.source.type}</span></td>
                      <td><code>{edge.type}</code></td>
                      <td><strong>{edge.target.name}</strong><span>{edge.target.type}</span></td>
                      <td>{edge.depth}</td>
                      <td title={edge.provenance.sourceUri}>
                        <strong>{edge.provenance.documentTitle}</strong>
                        <span>{edge.provenance.excerpt}</span>
                      </td>
                      <td>{Math.round(edge.provenance.confidence * 100)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </>
      )}
    </div>
  )
}

function GraphCanvas({ nodes, edges }: { nodes: PositionedNode[]; edges: GraphEdge[] }) {
  const positions = new Map(nodes.map((node) => [node.id, node]))
  return (
    <div className="graph-canvas" role="img" aria-label="知识关系图">
      <svg viewBox="0 0 800 420">
        <defs>
          <marker id="graph-arrow" viewBox="0 0 10 10" refX="9" refY="5"
            markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" />
          </marker>
        </defs>
        {edges.map((edge) => {
          const source = positions.get(edge.source.id)
          const target = positions.get(edge.target.id)
          if (!source || !target) return null
          return (
            <g key={edge.relationId}>
              <line x1={source.x} y1={source.y} x2={target.x} y2={target.y}
                markerEnd="url(#graph-arrow)" />
              <text x={(source.x + target.x) / 2} y={(source.y + target.y) / 2 - 5}>
                {edge.type}
              </text>
            </g>
          )
        })}
        {nodes.map((node) => (
          <g key={node.id} className="graph-node">
            <circle cx={node.x} cy={node.y} r="34" />
            <text x={node.x} y={node.y - 2}>{shorten(node.name, 13)}</text>
            <text className="graph-node-type" x={node.x} y={node.y + 14}>{node.type}</text>
            <title>{node.name} · {node.type}</title>
          </g>
        ))}
      </svg>
    </div>
  )
}

function positionNodes(edges: GraphEdge[]): PositionedNode[] {
  const unique = new Map<string, GraphNode>()
  edges.forEach((edge) => {
    unique.set(edge.source.id, edge.source)
    unique.set(edge.target.id, edge.target)
  })
  const nodes = [...unique.values()]
  const radius = Math.min(160, 55 + nodes.length * 7)
  return nodes.map((node, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(nodes.length, 1) - Math.PI / 2
    return {
      ...node,
      x: 400 + Math.cos(angle) * radius,
      y: 210 + Math.sin(angle) * radius,
    }
  })
}

function shorten(value: string, length: number) {
  return value.length > length ? `${value.slice(0, length - 1)}…` : value
}
