# store-elasticsearch

Elasticsearch keyword projection and retrieval adapter. The adapter applies tenant, space,
document and metadata filters inside Elasticsearch before any candidate reaches the runtime.

The adapter uses Elasticsearch's stable HTTP/JSON API through the JDK HTTP client, keeping
vendor-specific transport types outside `knowledge-domain` and `knowledge-spi`.
