package dev.infinityknowledge.store.elasticsearch;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.keyword.KeywordIndex;
import dev.infinityknowledge.spi.indexing.ActiveRevisionCandidates;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幂等写入 Elasticsearch，并提供按 ACL 过滤的关键词检索。
 */
public final class ElasticsearchKeywordIndex implements KeywordIndex, Retriever {

    private static final String MAPPING = """
            {
              "settings": {
                "analysis": {
                  "analyzer": {
                    "knowledge_cjk": {"type": "cjk"}
                  }
                }
              },
              "mappings": {
                "dynamic": "strict",
                "properties": {
                  "tenant_id": {"type": "keyword"},
                  "space_id": {"type": "keyword"},
                  "document_id": {"type": "keyword"},
                  "revision_id": {"type": "keyword"},
                  "chunk_id": {"type": "keyword"},
                  "title": {
                    "type": "text",
                    "analyzer": "knowledge_cjk",
                    "fields": {"raw": {"type": "keyword", "ignore_above": 512}}
                  },
                  "section_path": {"type": "text", "analyzer": "knowledge_cjk"},
                  "content": {"type": "text", "analyzer": "knowledge_cjk"},
                  "source_spans": {"type": "keyword", "index": false},
                  "source_uri": {"type": "keyword", "index": false},
                  "source_type": {"type": "keyword"},
                  "language": {"type": "keyword"},
                  "authority": {"type": "integer"}
                }
              }
            }
            """;

    /**
     * 对既有 strict mapping 的最小增量升级。新增字段在 Elasticsearch 中幂等，
     * 因而不需要删除历史索引或停机重建。
     */
    private static final String SOURCE_SPANS_MAPPING = """
            {
              "properties": {
                "source_spans": {"type": "keyword", "index": false}
              }
            }
            """;
    private static final String MAPPING_CONTRACT_FINGERPRINT = sha256(String.join(
            "\u001F",
            MAPPING,
            SOURCE_SPANS_MAPPING
    ));

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final ElasticsearchConfig config;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final AtomicBoolean indexReady = new AtomicBoolean();

    /**
     * 创建 Elasticsearch 适配器。
     */
    public ElasticsearchKeywordIndex(
            HttpClient httpClient,
            JsonMapper jsonMapper,
            ElasticsearchConfig config,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
    }

    @Override
    public RetrievalChannel channel() {
        return RetrievalChannel.KEYWORD;
    }

    /** 返回 Elasticsearch BM25 与 CJK 分词合同的稳定组件版本。 */
    @Override
    public RetrievalComponentVersion componentVersion() {
        return new RetrievalComponentVersion(
                "retriever-keyword",
                "elasticsearch",
                "bm25-cjk",
                "v1"
        );
    }

    /**
     * 校验连通性；不存在时创建配置的索引。
     *
     * <p>严格运行配置借此在启动阶段暴露缺失的 Elasticsearch 通道，避免第一次
     * 查询才失败。</p>
     */
    public void ensureReady() {
        ensureIndex();
    }

    /**
     * 返回当前实际索引名与完整映射内容的稳定指纹。
     *
     * <p>调用方把该值写入统一索引代际合同；修改索引名、分词配置或字段映射后，旧活动
     * 代际将无法继续通过检索前校验。指纹不包含端点与认证信息。</p>
     */
    public String physicalTargetFingerprint() {
        return physicalTargetFingerprint(
                config.indexName(),
                MAPPING_CONTRACT_FINGERPRINT
        );
    }

    /** 包级测试入口：证明索引名和映射指纹任一变化都会切换物理目标。 */
    static String physicalTargetFingerprint(
            String indexName,
            String mappingContractFingerprint
    ) {
        return sha256(String.join(
                "\u001F",
                "elasticsearch-keyword-v1",
                Objects.requireNonNull(indexName, "indexName must not be null"),
                Objects.requireNonNull(
                        mappingContractFingerprint,
                        "mappingContractFingerprint must not be null"
                )
        ));
    }

    @Override
    public void upsert(ProjectionSource source) {
        Objects.requireNonNull(source, "source must not be null");
        UUID revisionId = source.chunks().getFirst().revisionId();
        if (!activeRevisionGuard.isActive(
                source.document().tenantId(),
                source.document().id(),
                revisionId
        )) {
            return;
        }
        ensureIndex();
        StringBuilder bulk = new StringBuilder();
        for (var chunk : source.chunks()) {
            Map<String, Object> action = Map.of(
                    "index",
                    Map.of(
                            "_index", config.indexName(),
                            "_id", documentKey(
                                    source.document().tenantId().value(),
                                    chunk.id().toString()
                            )
                    )
            );
            Map<String, Object> document = new HashMap<>();
            document.put("tenant_id", source.document().tenantId().value());
            document.put("space_id", source.document().spaceId().value());
            document.put("document_id", source.document().id().value().toString());
            document.put("revision_id", chunk.revisionId().toString());
            document.put("chunk_id", chunk.id().toString());
            document.put("title", source.document().title());
            document.put("section_path", String.join(" / ", chunk.sectionPath()));
            document.put("content", chunk.content());
            document.put("source_spans", json(chunk.sourceSpans()));
            document.put("source_uri", source.document().source().uri());
            document.put("source_type", source.document().source().type().name());
            document.put(
                    "language",
                    source.document().metadata().getOrDefault(
                            "language",
                            chunk.metadata().getOrDefault("language", "")
                    )
            );
            document.put("authority", source.document().authority());
            bulk.append(json(action)).append('\n');
            bulk.append(json(document)).append('\n');
        }
        JsonNode response = exchange(
                "POST",
                "/_bulk?refresh=wait_for",
                bulk.toString(),
                "application/x-ndjson"
        );
        if (response.path("errors").asBoolean(true)) {
            throw new ElasticsearchStoreException("Elasticsearch bulk projection failed");
        }
        // 修订不可变；旧 Worker 可能晚于新 Worker 完成，因此此处删除“所有其他修订”不安全。
        // PostgreSQL 读取时会按活动修订头过滤。
    }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        ensureIndex();
        Map<String, Object> bool = new HashMap<>();
        bool.put("must", List.of(Map.of(
                "multi_match",
                Map.of(
                        "query", request.plan().normalizedQuery(),
                        "fields", List.of("title^3", "section_path^2", "content"),
                        "type", "best_fields",
                        "operator", "or"
                )
        )));
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(term("tenant_id", request.accessScope().tenantId().value()));
        filters.add(terms(
                "space_id",
                request.accessScope().spaceIds().stream()
                        .map(KnowledgeSpaceId::value)
                        .sorted()
                        .toList()
        ));
        if (request.accessScope().restrictsDocuments()) {
            filters.add(terms(
                    "document_id",
                    request.accessScope().documentIds().stream().sorted().toList()
            ));
        }
        optionalTerm(request.query().filters(), filters, "sourceType", "source_type");
        optionalTerm(request.query().filters(), filters, "language", "language");
        bool.put("filter", filters);
        return ActiveRevisionCandidates.load(
                request.accessScope().tenantId(),
                request.plan().candidateLimit(),
                limit -> search(bool, limit),
                activeRevisionGuard
        );
    }

    private List<RetrievalCandidate> search(
            Map<String, Object> bool,
            int limit
    ) {
        Map<String, Object> body = Map.of(
                "size", limit,
                "track_total_hits", false,
                "_source", List.of(
                        "tenant_id", "space_id", "document_id", "revision_id",
                        "chunk_id", "title", "section_path", "content", "source_spans",
                        "source_uri", "authority"
                ),
                "query", Map.of("bool", bool)
        );
        JsonNode response = exchange(
                "POST",
                "/" + config.indexName() + "/_search",
                json(body),
                "application/json"
        );
        JsonNode hits = response.path("hits").path("hits");
        if (!hits.isArray()) {
            throw new ElasticsearchStoreException("Elasticsearch search response is invalid");
        }
        double maxScore = response.path("hits").path("max_score").asDouble(0.0);
        List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
        int rank = 1;
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            double rawScore = hit.path("_score").asDouble(0.0);
            candidates.add(new RetrievalCandidate(
                    UUID.fromString(source.path("chunk_id").asString()),
                    new TenantId(source.path("tenant_id").asString()),
                    new KnowledgeSpaceId(source.path("space_id").asString()),
                    new DocumentId(UUID.fromString(source.path("document_id").asString())),
                    UUID.fromString(source.path("revision_id").asString()),
                    RetrievalChannel.KEYWORD,
                    rank++,
                    normalize(rawScore, maxScore),
                    source.path("title").asString(),
                    sectionPath(source.path("section_path").asString()),
                    source.path("content").asString(),
                    source.path("source_uri").asString(),
                    Map.of(
                            "retriever", "elasticsearch",
                            "authority", source.path("authority").asString("0")
                    ),
                    sourceSpans(optionalText(source.path("source_spans")))
            ));
        }
        return List.copyOf(candidates);
    }

    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }
            HttpResponse<String> head = rawExchange(
                    "HEAD",
                    "/" + config.indexName(),
                    "",
                    "application/json"
            );
            if (head.statusCode() == 404) {
                HttpResponse<String> created = rawExchange(
                        "PUT",
                        "/" + config.indexName(),
                        MAPPING,
                        "application/json"
                );
                if (created.statusCode() < 200 || created.statusCode() >= 300) {
                    if (created.statusCode() != 400
                            || !created.body().contains("resource_already_exists_exception")) {
                        throw httpFailure("create index", created.statusCode());
                    }
                }
            } else if (head.statusCode() < 200 || head.statusCode() >= 300) {
                throw httpFailure("inspect index", head.statusCode());
            }
            ensureSourceSpansMapping();
            indexReady.set(true);
        }
    }

    private void ensureSourceSpansMapping() {
        HttpResponse<String> response = rawExchange(
                "PUT",
                "/" + config.indexName() + "/_mapping",
                SOURCE_SPANS_MAPPING,
                "application/json"
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpFailure("upgrade source span mapping", response.statusCode());
        }
    }

    private JsonNode exchange(String method, String path, String body, String contentType) {
        HttpResponse<String> response = rawExchange(method, path, body, contentType);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpFailure("request", response.statusCode());
        }
        try {
            return response.body().isBlank()
                    ? jsonMapper.createObjectNode()
                    : jsonMapper.readTree(response.body());
        } catch (JacksonException parseFailure) {
            throw new ElasticsearchStoreException(
                    "Unable to parse Elasticsearch response",
                    parseFailure
            );
        }
    }

    private HttpResponse<String> rawExchange(
            String method,
            String path,
            String body,
            String contentType
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", contentType);
        if (!config.authorizationHeader().isEmpty()) {
            builder.header("Authorization", config.authorizationHeader());
        }
        HttpRequest request = switch (method) {
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            default -> throw new IllegalArgumentException("unsupported HTTP method");
        };
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException networkFailure) {
            throw new ElasticsearchStoreException(
                    "Elasticsearch network request failed",
                    networkFailure
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ElasticsearchStoreException(
                    "Elasticsearch request was interrupted",
                    interrupted
            );
        }
    }

    private URI uri(String path) {
        String base = config.endpoint().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException serializationFailure) {
            throw new ElasticsearchStoreException(
                    "Unable to serialize Elasticsearch request",
                    serializationFailure
            );
        }
    }

    private static Map<String, Object> term(String field, String value) {
        return Map.of("term", Map.of(field, value));
    }

    private static Map<String, Object> terms(String field, List<String> values) {
        return Map.of("terms", Map.of(field, values));
    }

    private static void optionalTerm(
            Map<String, String> source,
            List<Map<String, Object>> filters,
            String sourceName,
            String indexName
    ) {
        String value = source.get(sourceName);
        if (value != null && !value.isBlank()) {
            filters.add(term(indexName, value.strip()));
        }
    }

    private static List<String> sectionPath(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(" / ", -1));
    }

    private static String optionalText(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? "" : value.asString();
    }

    /** 反序列化仅用于回传引用范围，不参与查询或日志。 */
    private List<ChunkSourceSpan> sourceSpans(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(
                    value,
                    new tools.jackson.core.type.TypeReference<List<ChunkSourceSpan>>() { }
            );
        } catch (JacksonException failure) {
            throw new ElasticsearchStoreException("Elasticsearch source spans are invalid", failure);
        }
    }

    private static double normalize(double score, double maxScore) {
        if (score <= 0.0 || maxScore <= 0.0) {
            return 0.0;
        }
        return Math.min(score / maxScore, 0.999999);
    }

    private static String documentKey(String tenantId, String chunkId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (tenantId + "\u001F" + chunkId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ElasticsearchStoreException httpFailure(String action, int statusCode) {
        return new ElasticsearchStoreException(
                "Elasticsearch " + action + " failed with HTTP " + statusCode
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
