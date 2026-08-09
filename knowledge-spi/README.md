# knowledge-spi

该模块定义 Knowledge Gateway、Source Connector、Embedding、Retriever、索引、元数据存储和 Trace Sink 等端口。

SPI 只依赖 `knowledge-domain`，不暴露 PostgreSQL、Elasticsearch、Milvus、Neo4j、MinIO 或智谱客户端类型。

