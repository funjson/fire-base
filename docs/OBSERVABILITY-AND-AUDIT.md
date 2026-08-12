# Retrieval observability and mutation audit

## Scope

This slice adds a deliberately small production baseline. It does not introduce an event bus,
AOP advice, request-body buffering, or a second trace store.

## Retrieval metrics

`MeteredTraceSink` wraps the existing transactional `PostgresTraceSink`, so retrieval traces still
use the same durable data model. Prometheus exposes:

- `infinity_knowledge_retrieval_duration_seconds`: end-to-end timer, tagged only by
  `outcome=SUCCEEDED|DEGRADED`.
- `infinity_knowledge_retrieval_results`: evidence result-count distribution, tagged only by the
  same bounded outcome.
- `infinity_knowledge_retrieval_step_duration_seconds`: per-step timer, tagged by a fixed step and
  fixed status vocabulary.

Unknown future step names or statuses are exported as `OTHER`. Tenant, principal, document,
request, trace, query, and source identifiers are never metric tags.

## Mutation audit

The authenticated security chain records `POST`, `PUT`, `PATCH`, and `DELETE` requests below
`/api/v1/**`. It persists only:

- tenant, principal, and request UUID;
- HTTP method and normalized Spring route pattern;
- derived action (`METHOD route-pattern`);
- HTTP status, stable outcome, duration, and completion time.

The filter never reads or stores the request body, query string, bearer token, concrete URI,
document ID, chunk content, or response body. If no trusted route template is available it records
the fixed `/api/v1/unmatched` route. Audit persistence failure is logged without changing the
already-computed business response.

Migration `V13__create_mutation_audit_event.sql` adds the durable table and tenant-first indexes.
Only a tenant administrator or trusted system principal can read events:

```http
GET /api/v1/admin/audit-events?limit=50&offset=0
```

The API is tenant-bound from the authenticated JWT. Limits are restricted to `1..200` and offsets
to `0..1000000`; the persistence query also contains an explicit tenant predicate.

## Focused verification

The implementation is covered without starting Docker or application services:

```powershell
.\mvnw.cmd -T1 -pl control-plane -am `
  '-Dtest=MeteredTraceSinkTest,MutationAuditFilterTest,AuditApplicationServiceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

.\mvnw.cmd -T1 -pl store-postgres -am `
  '-Dtest=PostgresAuditStoreTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```
