package dev.infinityknowledge.controlplane.application.governance;

import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * 为经过身份提供方授权的租户管理员创建知识空间。
 */
@Service
public class TenantProvisioningService {

    private final KnowledgeGovernanceStore governanceStore;
    private final SpaceDocumentProcessingConfigStore processingConfigStore;
    private final SpaceRetrievalConfigurationStore retrievalConfigStore;
    private final DocumentProcessingCapabilities capabilities;
    private final Clock clock;

    /**
     * 创建租户初始化服务。
     *
     * @param governanceStore 知识治理持久化端口
     * @param processingConfigStore 不可变文档处理配置存储
     * @param retrievalConfigStore 不可变检索配置修订存储
     * @param capabilities 当前部署的 Parser、Chunker 与 Tokenizer 能力
     * @param clock UTC 时钟
     */
    public TenantProvisioningService(
            KnowledgeGovernanceStore governanceStore,
            SpaceDocumentProcessingConfigStore processingConfigStore,
            SpaceRetrievalConfigurationStore retrievalConfigStore,
            DocumentProcessingCapabilities capabilities,
            Clock clock
    ) {
        this.governanceStore = Objects.requireNonNull(
                governanceStore,
                "governanceStore must not be null"
        );
        this.processingConfigStore = Objects.requireNonNull(
                processingConfigStore,
                "processingConfigStore must not be null"
        );
        this.retrievalConfigStore = Objects.requireNonNull(
                retrievalConfigStore,
                "retrievalConfigStore must not be null"
        );
        this.capabilities = Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 原子创建租户、空间治理资源、不可变文档处理配置和检索基线修订。
     *
     * <p>只有数据库确认这是新 Space 后才按当前部署能力校验；相同重复请求只比较
     * 创建时快照，避免 Adapter 后续停用破坏幂等性。新建校验失败由外层事务回滚
     * 已写入的治理行、ACL、处理配置、检索配置与 Connector。</p>
     *
     * @param principal 已认证主体
     * @param requestedSpaceId 空间标识
     * @param name 显示名称
     * @param description 空间用途描述
     * @param processingConfig 已由 HTTP Adapter 规范化的完整配置
     */
    @Transactional
    public void createSpace(
            PrincipalContext principal,
            String requestedSpaceId,
            String name,
            String description,
            SpaceDocumentProcessingConfig processingConfig
    ) {
        requireAdmin(principal);
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        requireMatchingScope(principal, spaceId, processingConfig);
        KnowledgeGovernanceStore.CreateSpaceResult outcome =
                governanceStore.createSpace(
                principal,
                spaceId,
                name,
                description,
                clock.instant()
        );
        if (outcome.created()) {
            capabilities.validateForCreation(
                    processingConfig.parserSelections(),
                    processingConfig.chunker()
            );
            var frozenConfig = processingConfig.withProcessingContract(
                    capabilities.processingContract(processingConfig)
            );
            var configOutcome = processingConfigStore.createImmutable(frozenConfig);
            if (configOutcome
                    != SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED) {
                throw new IllegalStateException(
                        "new knowledge space already has a processing config"
                );
            }
            materializeInitialRetrievalConfiguration(principal, spaceId);
            return;
        }
        if (!"ACTIVE".equals(outcome.status())
                || !name.strip().equals(outcome.name())
                || !description.strip().equals(outcome.description())) {
            throw existingSpaceConflict();
        }
        SpaceDocumentProcessingConfig existing = processingConfigStore
                .find(principal.tenantId(), spaceId)
                .orElseThrow(TenantProvisioningService::existingSpaceConflict);
        if (!existing.hasSameProcessingConfiguration(processingConfig)) {
            throw existingSpaceConflict();
        }
    }

    /**
     * 在 Space 创建事务内固化确定性检索基线，避免系统默认值后续变化影响已有 Space。
     */
    private void materializeInitialRetrievalConfiguration(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId
    ) {
        SpaceRetrievalConfiguration initial = SpaceRetrievalConfiguration.create(
                principal.tenantId(),
                spaceId,
                1L,
                RetrievalConfiguration.deterministicBaseline(),
                principal.principalId(),
                clock.instant()
        );
        SpaceRetrievalConfigurationStore.ActivationOutcome outcome =
                retrievalConfigStore.appendAndActivate(initial, 0L);
        if (outcome != SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED) {
            throw new IllegalStateException(
                    "new knowledge space already has a retrieval configuration"
            );
        }
    }

    private static KnowledgeSpaceConflictException existingSpaceConflict() {
        return new KnowledgeSpaceConflictException(
                "SPACE_ALREADY_EXISTS_WITH_DIFFERENT_CONFIGURATION",
                "knowledge space already exists with a different immutable definition"
        );
    }

    /** 防止 Adapter 构造的配置越过当前认证租户或目标 Space。 */
    private static void requireMatchingScope(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SpaceDocumentProcessingConfig processingConfig
    ) {
        Objects.requireNonNull(processingConfig, "processingConfig must not be null");
        if (!principal.tenantId().equals(processingConfig.tenantId())
                || !spaceId.equals(processingConfig.spaceId())
                || processingConfig.version() != 0L
                || !principal.principalId().equals(processingConfig.updatedBy())) {
            throw new IllegalArgumentException(
                    "document processing config does not match the creation scope"
            );
        }
    }

    /**
     * 确保当前主体拥有租户管理员角色。
     */
    private static void requireAdmin(PrincipalContext principal) {
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
