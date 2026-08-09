import { Component, type ErrorInfo, type ReactNode } from 'react'

type ErrorBoundaryProps = {
  children: ReactNode
}

type ErrorBoundaryState = {
  error?: Error
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = {}

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Knowledge Console rendering failed', error, info)
  }

  render() {
    if (!this.state.error) return this.props.children

    return (
      <div className="fatal-error" role="alert">
        <span className="eyebrow">CONSOLE ERROR</span>
        <h1>当前页面暂时无法显示</h1>
        <p>{this.state.error.message || '页面模块加载或渲染失败。'}</p>
        <div className="button-row">
          <button onClick={() => window.location.reload()}>重新加载</button>
          <button
            className="primary-button"
            onClick={() => window.location.assign('/')}
          >
            返回运行总览
          </button>
        </div>
      </div>
    )
  }
}
