package dev.infinityknowledge.store.milvus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveRevisionCandidates;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorIndexRecord;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 使用 Milvus 2.6 实现的向量索引，所有查询强制包含租户和知识空间条件。
 */
public final class MilvusVectorIndex implements VectorIndex {

    // 增加来源范围字段后使用新集合，避免修改已存在的不可变 Milvus schema。
    private static final String SCHEMA_GENERATION = "metadata_v3";
    private static final String ID = "id";
    private static final String TENANT_ID = "tenant_id";
    private static final String SPACE_ID = "space_id";
    private static final String DOCUMENT_ID = "document_id";
    private static final String REVISION_ID = "revision_id";
    private static final String TITLE = "title";
    private static final String SOURCE_URI = "source_uri";
    private static final String SOURCE_TYPE = "source_type";
    private static final String LANGUAGE = "language";
    private static final String SECTION_PATH = "section_path";
    private static final String CONTENT = "content";
    private static final String SOURCE_SPANS = "source_spans";
    private static final String AUTHORITY = "authority";
    private static final String VECTOR = "vector";
    private static final List<String> OUTPUT_FIELDS = List.of(
            TENANT_ID,
            SPACE_ID,
            DOCUMENT_ID,
            REVISION_ID,
            TITLE,
            SOURCE_URI,
            SOURCE_TYPE,
            LANGUAGE,
            SECTION_PATH,
            CONTENT,
            SOURCE_SPANS,
            AUTHORITY
    );

    private final MilvusClientV2 client;
    private final String collectionPrefix;
    private final int partitionCount;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final Map<String, Boolean> ensuredCollections = new ConcurrentHashMap<>();

    /**
     * 创建 Milvus 适配器。
     *
     * @param client Milvus 客户端
     * @param collectionPrefix 物理 Collection 前缀
     * @param partitionCount 租户分区键的哈希分区数
     */
    public MilvusVectorIndex(
            MilvusClientV2 client,
            String collectionPrefix,
            int partitionCount,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.collectionPrefix = safeIdentifier(collectionPrefix, "collectionPrefix");
        if (partitionCount < 1 || partitionCount > 4_096) {
            throw new IllegalArgumentException("partitionCount must be between 1 and 4096");
        }
        this.partitionCount = partitionCount;
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
    }

    @Override
    public void ensureGeneration(EmbeddingSpec spec, String generation) {
        Objects.requireNonNull(spec, "spec must not be null");
        String collectionName = collectionName(spec, generation);
        if (ensuredCollections.putIfAbsent(collectionName, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (!client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()
            )) {
                createCollection(collectionName, spec.dimensions());
            }
        } catch (RuntimeException failure) {
            ensuredCollections.remove(collectionName);
            throw failure;
        }
    }

    @Override
    public void upsert(List<VectorIndexRecord> records) {
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        if (records.isEmpty()) {
            return;
        }
        VectorIndexRecord first = records.getFirst();
        ensureGeneration(first.embeddingSpec(), first.generation());
        String collectionName = collectionName(first.embeddingSpec(), first.generation());
        List<JsonObject> rows = new ArrayList<>(records.size());
        for (VectorIndexRecord record : records) {
            if (!first.embeddingSpec().equals(record.embeddingSpec())
                    || !first.generation().equals(record.generation())) {
                throw new IllegalArgumentException(
                        "one vector upsert batch must target a single generation"
                );
            }
            rows.add(toRow(record));
        }
        client.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());
    }

    @Override
    public List<RetrievalCandidate> search(VectorSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        ensureGeneration(request.embeddingSpec(), request.generation());
        return ActiveRevisionCandidates.load(
                request.accessScope().tenantId(),
                request.limit(),
                limit -> search(request, limit),
                activeRevisionGuard
        );
    }

    private List<RetrievalCandidate> search(VectorSearchRequest request, int limit) {
        SearchReq search = SearchReq.builder()
                .collectionName(collectionName(request.embeddingSpec(), request.generation()))
                .annsField(VECTOR)
                .metricType(IndexParam.MetricType.COSINE)
                .limit(limit)
                .filter(filter(request))
                .filterTemplateValues(filterValues(request))
                .outputFields(OUTPUT_FIELDS)
                .data(List.of(new FloatVec(toFloats(request.vector()))))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build();
        SearchResp response = client.search(search);
        if (response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> results = response.getSearchResults().getFirst();
        List<RetrievalCandidate> candidates = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            candidates.add(toCandidate(results.get(index), index + 1));
        }
        return List.copyOf(candidates);
    }

    private void createCollection(String collectionName, int dimensions) {
        CreateCollectionReq.CollectionSchema schema =
                CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(field(ID, DataType.VarChar, 36, true, false));
        schema.addField(field(TENANT_ID, DataType.VarChar, 64, false, true));
        schema.addField(field(SPACE_ID, DataType.VarChar, 64, false, false));
        schema.addField(field(DOCUMENT_ID, DataType.VarChar, 36, false, false));
        schema.addField(field(REVISION_ID, DataType.VarChar, 36, false, false));
        schema.addField(field(TITLE, DataType.VarChar, 512, false, false));
        schema.addField(field(SOURCE_URI, DataType.VarChar, 2_048, false, false));
        schema.addField(field(SOURCE_TYPE, DataType.VarChar, 32, false, false));
        schema.addField(field(LANGUAGE, DataType.VarChar, 32, false, false));
        schema.addField(field(SECTION_PATH, DataType.VarChar, 4_096, false, false));
        schema.addField(field(CONTENT, DataType.VarChar, 65_535, false, false));
        schema.addField(field(SOURCE_SPANS, DataType.VarChar, 65_535, false, false));
        schema.addField(AddFieldReq.builder()
                .fieldName(AUTHORITY)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(VECTOR)
                .dataType(DataType.FloatVector)
                .dimension(dimensions)
                .build());
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName(VECTOR)
                .indexName("vector_hnsw")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 128))
                .build();
        try {
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("Infinity Knowledge immutable embedding generation")
                    .collectionSchema(schema)
                    .indexParams(List.of(vectorIndex))
                    .numPartitions(partitionCount)
                    .consistencyLevel(ConsistencyLevel.BOUNDED)
                    .property("partitionkey.isolation", "true")
                    .build());
        } catch (RuntimeException creationFailure) {
            if (!client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()
            )) {
                throw creationFailure;
            }
        }
    }

    private static AddFieldReq field(
            String name,
            DataType type,
            int maxLength,
            boolean primary,
            boolean partitionKey
    ) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(type)
                .maxLength(maxLength)
                .isPrimaryKey(primary)
                .isPartitionKey(partitionKey)
                .autoID(false)
                .build();
    }

    private static JsonObject toRow(VectorIndexRecord record) {
        var chunk = record.chunk();
        JsonObject row = new JsonObject();
        row.addProperty(ID, chunk.id().toString());
        row.addProperty(TENANT_ID, chunk.tenantId().value());
        row.addProperty(SPACE_ID, chunk.spaceId().value());
        row.addProperty(DOCUMENT_ID, chunk.documentId().value().toString());
        row.addProperty(REVISION_ID, chunk.revisionId().toString());
        row.addProperty(TITLE, record.title());
        row.addProperty(SOURCE_URI, record.sourceUri());
        row.addProperty(SOURCE_TYPE, record.sourceType());
        row.addProperty(LANGUAGE, record.language());
        JsonArray path = new JsonArray();
        chunk.sectionPath().forEach(path::add);
        row.addProperty(SECTION_PATH, path.toString());
        row.addProperty(CONTENT, chunk.content());
        row.addProperty(SOURCE_SPANS, sourceSpansJson(chunk.sourceSpans()));
        row.addProperty(AUTHORITY, record.authority());
        JsonArray vector = new JsonArray();
        record.vector().forEach(vector::add);
        row.add(VECTOR, vector);
        return row;
    }

    static String filter(VectorSearchRequest request) {
        StringBuilder expression = new StringBuilder()
                .append(TENANT_ID)
                .append(" == {tenantId} && ")
                .append(SPACE_ID)
                .append(" in {spaceIds}");
        if (request.accessScope().restrictsDocuments()) {
            expression.append(" && ")
                    .append(DOCUMENT_ID)
                    .append(" in {documentIds}");
        }
        appendOptionalFilter(
                expression,
                request.filters(),
                "sourceType",
                SOURCE_TYPE
        );
        appendOptionalFilter(
                expression,
                request.filters(),
                "language",
                LANGUAGE
        );
        return expression.toString();
    }

    static Map<String, Object> filterValues(VectorSearchRequest request) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("tenantId", request.accessScope().tenantId().value());
        values.put(
                "spaceIds",
                request.accessScope().spaceIds().stream()
                        .map(KnowledgeSpaceId::value)
                        .toList()
        );
        if (request.accessScope().restrictsDocuments()) {
            values.put("documentIds", List.copyOf(request.accessScope().documentIds()));
        }
        optionalFilterValue(values, request.filters(), "sourceType");
        optionalFilterValue(values, request.filters(), "language");
        return Map.copyOf(values);
    }

    private static void appendOptionalFilter(
            StringBuilder expression,
            Map<String, String> filters,
            String parameter,
            String field
    ) {
        if (filters.containsKey(parameter)) {
            expression.append(" && ")
                    .append(field)
                    .append(" == {")
                    .append(parameter)
                    .append('}');
        }
    }

    private static void optionalFilterValue(
            Map<String, Object> values,
            Map<String, String> filters,
            String name
    ) {
        String value = filters.get(name);
        if (value != null) {
            values.put(name, value.strip());
        }
    }

    private static RetrievalCandidate toCandidate(
            SearchResp.SearchResult result,
            int rank
    ) {
        Map<String, Object> entity = result.getEntity();
        return new RetrievalCandidate(
                UUID.fromString(primaryKey(result)),
                new TenantId(requiredString(entity, TENANT_ID)),
                new KnowledgeSpaceId(requiredString(entity, SPACE_ID)),
                new DocumentId(UUID.fromString(requiredString(entity, DOCUMENT_ID))),
                UUID.fromString(requiredString(entity, REVISION_ID)),
                RetrievalChannel.VECTOR,
                rank,
                normalizeScore(result.getScore()),
                requiredString(entity, TITLE),
                parseSectionPath(requiredString(entity, SECTION_PATH)),
                requiredString(entity, CONTENT),
                requiredString(entity, SOURCE_URI),
                Map.of(
                        "retriever", "milvus-2.6",
                        "authority", requiredString(entity, AUTHORITY),
                        "sourceType", requiredString(entity, SOURCE_TYPE),
                        "language", requiredString(entity, LANGUAGE)
                ),
                sourceSpans(requiredString(entity, SOURCE_SPANS))
        );
    }

    private static String primaryKey(SearchResp.SearchResult result) {
        Object id = result.getId();
        if (id != null) {
            return id.toString();
        }
        if (result.getPrimaryKey() != null && !result.getPrimaryKey().isBlank()) {
            return result.getPrimaryKey();
        }
        throw new IllegalStateException("Milvus result omitted the primary key");
    }

    private static List<String> parseSectionPath(String value) {
        JsonArray json = com.google.gson.JsonParser.parseString(value).getAsJsonArray();
        List<String> values = new ArrayList<>(json.size());
        json.forEach(item -> values.add(item.getAsString()));
        return List.copyOf(values);
    }

    private static String sourceSpansJson(List<ChunkSourceSpan> spans) {
        JsonArray values = new JsonArray();
        for (ChunkSourceSpan span : spans) {
            JsonObject value = new JsonObject();
            value.addProperty("elementId", span.elementId().toString());
            value.addProperty("startOffset", span.startOffset());
            value.addProperty("endOffset", span.endOffset());
            if (span.pageNumber() != null) {
                value.addProperty("pageNumber", span.pageNumber());
            }
            values.add(value);
        }
        return values.toString();
    }

    private static List<ChunkSourceSpan> sourceSpans(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        JsonArray values = com.google.gson.JsonParser.parseString(value).getAsJsonArray();
        List<ChunkSourceSpan> spans = new ArrayList<>(values.size());
        for (var item : values) {
            JsonObject span = item.getAsJsonObject();
            Integer pageNumber = span.has("pageNumber") && !span.get("pageNumber").isJsonNull()
                    ? span.get("pageNumber").getAsInt()
                    : null;
            spans.add(new ChunkSourceSpan(
                    UUID.fromString(span.get("elementId").getAsString()),
                    span.get("startOffset").getAsInt(),
                    span.get("endOffset").getAsInt(),
                    pageNumber
            ));
        }
        return List.copyOf(spans);
    }

    private static String requiredString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Milvus result omitted field " + field);
        }
        return value.toString();
    }

    private static double normalizeScore(Float score) {
        if (score == null || !Float.isFinite(score)) {
            throw new IllegalStateException("Milvus returned an invalid score");
        }
        return Math.max(0.0D, Math.min(1.0D, score.doubleValue()));
    }

    private static List<Float> toFloats(List<Double> values) {
        return values.stream().map(Double::floatValue).toList();
    }

    private String collectionName(EmbeddingSpec spec, String generation) {
        return String.join(
                "_",
                collectionPrefix,
                safeIdentifier(spec.providerId(), "providerId"),
                safeIdentifier(spec.modelId(), "modelId"),
                Integer.toString(spec.dimensions()),
                safeIdentifier(generation, "generation"),
                SCHEMA_GENERATION
        );
    }

    private static String safeIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException(name + " must form a 1..64 character identifier");
        }
        return normalized;
    }
}
