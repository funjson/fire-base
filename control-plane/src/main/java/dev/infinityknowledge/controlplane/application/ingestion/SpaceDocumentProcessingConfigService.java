package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 查询创建 Space 时固化的 Parser、内容清洗与 Chunker 配置。
 *
 * <p>处理配置从 Space 创建成功开始不可变；需要其他处理语义时必须创建新 Space。
 * 服务只暴露只读快照和创建表单所需的部署能力，不再维护可编辑状态。</p>
 */
@Service
public final class SpaceDocumentProcessingConfigService {
    private final SpaceDocumentProcessingConfigStore store;
    private final DocumentProcessingCapabilities capabilities;

    /** 创建只读空间文档处理配置用例。 */
    public SpaceDocumentProcessingConfigService(
            SpaceDocumentProcessingConfigStore store,
            DocumentProcessingCapabilities capabilities
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.capabilities = Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
    }

    /** 返回创建 Space 时已经持久化的不可变配置。 */
    public ConfigDetails get(PrincipalContext principal, String requestedSpaceId) {
        requireAdmin(principal);
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        if (!store.activeSpaceExists(principal.tenantId(), spaceId)) {
            throw new IllegalArgumentException("knowledge space does not exist or is not active");
        }
        SpaceDocumentProcessingConfig config = store.find(principal.tenantId(), spaceId)
                .orElseThrow(SpaceDocumentProcessingConfigMissingException::new);
        return new ConfigDetails(
                config,
                capabilities.snapshot(),
                runtimeContractMatched(config)
        );
    }

    /** 返回创建表单和测试广场共用的部署能力与默认值。 */
    public DocumentProcessingCapabilities.Snapshot capabilities(
            PrincipalContext principal
    ) {
        requireAdmin(principal);
        return capabilities.snapshot();
    }

    /** 供 HTTP Adapter 使用的稳定应用结果。 */
    public record ConfigDetails(
            SpaceDocumentProcessingConfig config,
            DocumentProcessingCapabilities.Snapshot capabilities,
            boolean runtimeContractMatched
    ) {

        public ConfigDetails {
            Objects.requireNonNull(config, "config must not be null");
            if (config.version() != 1L
                    || config.processingContract() == null
                    || config.updatedBy() == null) {
                throw new IllegalArgumentException(
                        "space document processing config must be a persisted creation snapshot"
                );
            }
            Objects.requireNonNull(capabilities, "capabilities must not be null");
        }
    }

    /** 能力下线或实现升级只影响可执行状态，不能阻止管理员读取固化快照。 */
    private boolean runtimeContractMatched(SpaceDocumentProcessingConfig config) {
        try {
            return config.processingContract().equals(
                    capabilities.processingContract(config)
            );
        } catch (RuntimeException unavailableOrInvalid) {
            return false;
        }
    }

    /** 当前入口仅开放给租户知识管理员或受信系统主体。 */
    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
