# Wiki knowledge pages

Wiki pages are governed, compiled projections of immutable document chunks. They are not a new
source of truth: every page revision must retain its originating document, revision, chunk,
content hash and authority.

## Lifecycle

`DRAFT -> IN_REVIEW -> PUBLISHED -> ARCHIVED`

- Compilation creates or updates a draft without replacing the current active revision.
- Publishing promotes the latest reviewed revision to `active_revision_id`.
- Optimistic-lock conflicts return HTTP `409` with code `KNOWLEDGE_PAGE_CONFLICT`.
- Tenant and space ACL checks run in `WikiApplicationService` before content is loaded or changed.

## Agent retrieval semantics

The PAGE retriever matches only a page's active, previously published revision. It then returns
the original active document chunks referenced by that page. The page improves routing, while
the normal evidence builder, document citation and active-revision guard remain unchanged. Draft
page prose is never returned as primary evidence and no synthetic document IDs are created.

## Administration API

- `POST /api/v1/admin/wiki/pages` compiles selected document/revision/chunk sources.
- `GET /api/v1/admin/wiki/pages` lists pages in ACL-authorized spaces.
- `GET /api/v1/admin/wiki/pages/{pageId}` returns latest and active revisions for comparison.
- `POST .../{pageId}/submit-review`, `/reject`, `/publish`, `/archive` apply governed transitions.

The visual console exposes these operations at `/wiki`.

## Compiler configuration

The deterministic extractive compiler is the default and performs no model call. To enable GLM
compilation explicitly:

```text
KNOWLEDGE_WIKI_GENERATIVE_ENABLED=true
ZHIPU_API_KEY=...
KNOWLEDGE_WIKI_MODEL=glm-4-flash
```

Optional proxy variables are `KNOWLEDGE_WIKI_PROXY_HOST` and
`KNOWLEDGE_WIKI_PROXY_PORT`. Input size, retry count, output tokens and source-coverage policy
are bounded in `application.yml`.
