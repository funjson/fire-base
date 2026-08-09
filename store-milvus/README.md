# store-milvus

Milvus 2.6 vector-index adapter.

The adapter uses one collection per immutable embedding generation. `tenant_id`
is a partition key and every search expression includes an exact tenant filter
plus the authorized knowledge-space filter. Runtime authorization validates all
returned candidates again.
