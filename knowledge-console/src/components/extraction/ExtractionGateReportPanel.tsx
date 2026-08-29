import type {
  ExtractionGateReport,
  ExtractionGateStatus,
  ExtractionRunStatus,
} from '../../lib/api'
import { StatusBadge } from '../State'

/** 明确分开展示任务执行状态与真实 Dataset Gate 结果。 */
export function ExtractionGateReportPanel({
  runStatus,
  gateStatus,
  datasetId,
  report,
}: {
  runStatus: ExtractionRunStatus
  gateStatus: ExtractionGateStatus
  datasetId: string | null
  report: ExtractionGateReport | null
}) {
  return (
    <section className="extraction-gate-report">
      <header>
        <div>
          <strong>真实硬门禁报告</strong>
          <span>
            Run <StatusBadge value={runStatus} /> 与 Gate{' '}
            <StatusBadge value={gateStatus} /> 是两个独立结论
          </span>
        </div>
        <code>{datasetId || '未选择 Dataset'}</code>
      </header>
      {!report && (
        <div className="extraction-gate-empty">
          {datasetId && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(runStatus)
            ? '抽取完成后才会调用真实 ObservationFactory 与 Dataset Runner。'
            : '本次没有真实门禁报告，不能判定为 PASSED。'}
        </div>
      )}
      {report && (
        <>
          <div className="extraction-gate-report-summary">
            <span>Dataset {report.datasetVersion}</span>
            <span>Cases {report.cases.length}</span>
            <span>{new Date(report.evaluatedAt).toLocaleString('zh-CN')}</span>
            {report.errorCode && <code>{report.errorCode}</code>}
          </div>
          <div className="extraction-gate-case-list">
            {report.cases.map((value) => (
              <details key={value.caseId} open={!value.passed}>
                <summary>
                  <StatusBadge value={value.passed ? 'PASSED' : 'FAILED'} />
                  <strong>{value.caseId}</strong>
                </summary>
                <div className="extraction-gate-metrics">
                  <GateMetric label="Parse 成功率" value={percent(value.parseSuccessRate)} />
                  <GateMetric label="Token 越界" value={value.tokenOverflowCount} />
                  <GateMetric label="无效 SourceSpan" value={value.invalidSourceSpanCount} />
                  <GateMetric label="来源核算率" value={percent(value.sourceAccountingRate)} />
                  <GateMetric label="静默截断" value={value.silentTruncationCount} />
                </div>
                {value.findings.map((finding) => (
                  <div className="extraction-gate-finding" key={finding.code}>
                    <code>{finding.code}</code>
                    <span>{finding.message}</span>
                  </div>
                ))}
              </details>
            ))}
          </div>
        </>
      )}
    </section>
  )
}

function GateMetric({ label, value }: { label: string; value: string | number }) {
  return (
    <span>
      <small>{label}</small>
      <strong>{value}</strong>
    </span>
  )
}

function percent(value: number) {
  return `${(value * 100).toFixed(value === 1 ? 0 : 1)}%`
}
