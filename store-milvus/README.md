# store-milvus

Milvus 2.6 vector-index adapter.

The adapter uses one collection per immutable embedding generation and physical
schema generation. `metadata_v2` adds exact `language` and `source_type` fields
without trying to reuse collections created with the earlier schema. `tenant_id`
is a partition key and every search expression combines the exact tenant,
authorized space/document scope, and supported metadata filters. Runtime
authorization validates all returned candidates again.
