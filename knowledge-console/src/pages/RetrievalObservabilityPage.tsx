import { useQuery } from '@tanstack/react-query'
import { ExecutionRecords } from '../components/retrieval-observability/ExecutionRecords'
import { OnlineOverview } from '../components/retrieval-observability/OnlineOverview'
import {
  RetrievalObservabilityShell,
  type RetrievalObservabilityView,
} from '../components/retrieval-observability/RetrievalObservabilityShell'
import { StageDiagnostics } from '../components/retrieval-observability/StageDiagnostics'
import { useApi } from '../lib/use-api'

export function RetrievalObservabilityPage({
  view,
}: {
  view: RetrievalObservabilityView
}) {
  const api = useApi()
  const spaces = useQuery({
    queryKey: ['spaces', 'retrieval-observability-filter'],
    queryFn: api.spaces,
  })

  return (
    <RetrievalObservabilityShell
      view={view}
      spaces={spaces.data}
      spacesLoading={spaces.isPending}
    >
      {({ filter, rangeLabel, refreshKey }) => {
        if (view === 'overview') {
          return (
            <OnlineOverview
              filter={filter}
              rangeLabel={rangeLabel}
              refreshKey={refreshKey}
            />
          )
        }
        if (view === 'stages') {
          return (
            <StageDiagnostics
              filter={filter}
              rangeLabel={rangeLabel}
              refreshKey={refreshKey}
            />
          )
        }
        return (
          <ExecutionRecords
            filter={filter}
            rangeLabel={rangeLabel}
            refreshKey={refreshKey}
          />
        )
      }}
    </RetrievalObservabilityShell>
  )
}
