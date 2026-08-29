package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.ChunkerProviderConfigurationJson;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把控制台配置字段映射为唯一的强类型处理配置。
 *
 * <p>Space 创建与 TEST_ONLY 临时覆盖必须复用这里，避免两条 HTTP 入口对清洗枚举、
 * Provider JSON 或媒体类型规范化采用不同规则。</p>
 */
public final class DocumentProcessingConfigRequestMapper {

    private DocumentProcessingConfigRequestMapper() {
    }

    /** 创建一套尚未持久化的完整配置，调用方决定固化到 Space 或本次运行。 */
    public static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            map(
                    PrincipalContext principal,
                    String requestedSpaceId,
                    DocumentProcessingConfigRequest request
            ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(request, "request must not be null");
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                principal.tenantId(),
                new KnowledgeSpaceId(requestedSpaceId),
                parserSelections(request.parserSelections()),
                cleaning(request.cleaning()),
                chunker(request.chunker()),
                0L,
                principal.principalId(),
                Instant.EPOCH
        );
    }

    /** 规范化媒体类型并拒绝重复选择。 */
    public static Map<String, String> parserSelections(
            List<DocumentProcessingConfigRequest.ParserSelection> values
    ) {
        if (values == null) {
            throw new IllegalArgumentException("parserSelections must not be null");
        }
        Map<String, String> selections = new LinkedHashMap<>();
        for (DocumentProcessingConfigRequest.ParserSelection selection : values) {
            if (selection == null) {
                throw new IllegalArgumentException(
                        "parserSelections must not contain null"
                );
            }
            String mediaType = selection.mediaType().strip().toLowerCase(Locale.ROOT);
            String previous = selections.put(mediaType, selection.parserId().strip());
            if (previous != null) {
                throw new IllegalArgumentException(
                        "parserSelections contains a duplicate mediaType"
                );
            }
        }
        return Map.copyOf(selections);
    }

    /** 把页面清洗动作转换为稳定枚举。 */
    public static SpaceDocumentProcessingConfigStore.CleaningConfiguration cleaning(
            DocumentProcessingConfigRequest.CleaningConfiguration value
    ) {
        if (value == null) {
            throw new IllegalArgumentException("cleaning must not be null");
        }
        return new SpaceDocumentProcessingConfigStore.CleaningConfiguration(
                cleaningAction(value.header()),
                cleaningAction(value.footer()),
                cleaningAction(value.pageNumber()),
                cleaningAction(value.watermark()),
                cleaningAction(value.frontMatter())
        );
    }

    /** 规范化 Provider 标识并生成 Provider 权威 canonical JSON。 */
    public static SpaceDocumentProcessingConfigStore.ChunkerConfiguration chunker(
            DocumentProcessingConfigRequest.ChunkerConfiguration value
    ) {
        if (value == null) {
            throw new IllegalArgumentException("chunker must not be null");
        }
        String providerId = value.providerId().strip().toUpperCase(Locale.ROOT);
        return new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                providerId,
                value.tokenizerId().strip(),
                value.minimumTokens(),
                value.targetTokens(),
                value.maximumTokens(),
                value.overlapTokens(),
                ChunkerProviderConfigurationJson.encode(
                        providerId,
                        value.providerConfig()
                )
        );
    }

    private static SpaceDocumentProcessingConfigStore.CleaningAction cleaningAction(
            String value
    ) {
        if (value == null) {
            throw new IllegalArgumentException("cleaning action must not be null");
        }
        try {
            return SpaceDocumentProcessingConfigStore.CleaningAction.valueOf(
                    value.strip().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException invalidAction) {
            throw new IllegalArgumentException(
                    "unsupported document cleaning action",
                    invalidAction
            );
        }
    }
}
