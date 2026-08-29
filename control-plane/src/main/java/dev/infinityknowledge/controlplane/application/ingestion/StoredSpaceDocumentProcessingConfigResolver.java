package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 从结构化存储解析创建 Space 时固化的文档处理配置。
 *
 * <p>持久化配置在执行前再次经过当前部署能力校验。这样即使部署移除了某个 Parser
 * 或语义模型，任务也会明确失败，而不会静默换用另一个处理策略。</p>
 */
@Component
public final class StoredSpaceDocumentProcessingConfigResolver
        implements SpaceDocumentProcessingConfigResolver {

    private final SpaceDocumentProcessingConfigStore store;
    private final DocumentProcessingCapabilities capabilities;

    /** 创建只负责执行期文档处理配置解析的组件。 */
    public StoredSpaceDocumentProcessingConfigResolver(
            SpaceDocumentProcessingConfigStore store,
            DocumentProcessingCapabilities capabilities
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.capabilities = Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
    }

    @Override
    public SpaceDocumentProcessingConfig resolve(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        SpaceDocumentProcessingConfig config = resolveStored(tenantId, spaceId);
        try {
            if (config.processingContract().equals(
                    capabilities.processingContract(config)
            )) {
                return config;
            }
            throw new DocumentProcessingContractMismatchException();
        } catch (DocumentProcessingContractMismatchException mismatch) {
            throw mismatch;
        } catch (RuntimeException unavailableOrInvalid) {
            throw new DocumentProcessingContractMismatchException(
                    unavailableOrInvalid
            );
        }
    }

    /**
     * 读取固定配置并校验 Space、租户和版本，不校验固定 Adapter 的当前可用性。
     * TEST_ONLY 完整覆盖会单独校验实际执行的配置。
     */
    @Override
    public SpaceDocumentProcessingConfig resolveStored(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (!store.activeSpaceExists(tenantId, spaceId)) {
            throw new IllegalArgumentException(
                    "knowledge space does not exist or is not active"
            );
        }
        SpaceDocumentProcessingConfig config = store.find(tenantId, spaceId)
                .orElseThrow(() -> new IllegalStateException(
                        "active knowledge space has no document processing config"
                ));
        requirePersistedConfig(tenantId, spaceId, config);
        return config;
    }

    /** 执行期只能使用已经建立版本栅栏的本空间权威配置。 */
    private static void requirePersistedConfig(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SpaceDocumentProcessingConfig config
    ) {
        if (!tenantId.equals(config.tenantId())
                || !spaceId.equals(config.spaceId())
                || config.version() != 1L
                || config.processingContract() == null
                || config.updatedBy() == null) {
            throw new IllegalStateException(
                    "resolved document processing config is not persisted for the space"
            );
        }
    }
}
