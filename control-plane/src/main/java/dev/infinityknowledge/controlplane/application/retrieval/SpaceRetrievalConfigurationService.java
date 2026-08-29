package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 管理 Space 当前检索配置和不可变历史修订。
 *
 * <p>读取始终先经过与在线检索相同的 Space 访问策略；写入只允许知识管理员，
 * 并通过期望修订避免控制台并发覆盖。服务只追加完整配置，不维护草稿或发布状态。</p>
 */
@Service
public final class SpaceRetrievalConfigurationService {

    private final SpaceRetrievalConfigurationStore store;
    private final AccessPolicy accessPolicy;
    private final RetrievalConfigurationResolver resolver;
    private final RetrievalConfigurationHardLimits hardLimits;
    private final Clock clock;

    /** 创建检索配置管理用例。 */
    public SpaceRetrievalConfigurationService(
            SpaceRetrievalConfigurationStore store,
            AccessPolicy accessPolicy,
            RetrievalConfigurationResolver resolver,
            RetrievalConfigurationHardLimits hardLimits,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy,
                "accessPolicy must not be null"
        );
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.hardLimits = Objects.requireNonNull(
                hardLimits,
                "hardLimits must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** 返回当前主体可读取的 Space 当前配置修订。 */
    public SpaceRetrievalConfiguration current(
            PrincipalContext principal,
            String requestedSpaceId
    ) {
        KnowledgeSpaceId spaceId = readableSpace(principal, requestedSpaceId);
        return currentConfiguration(principal, spaceId);
    }

    /** 按修订号倒序返回当前主体可读取的有限历史。 */
    public List<SpaceRetrievalConfiguration> history(
            PrincipalContext principal,
            String requestedSpaceId,
            int limit
    ) {
        KnowledgeSpaceId spaceId = readableSpace(principal, requestedSpaceId);
        List<SpaceRetrievalConfiguration> history = store.history(
                principal.tenantId(),
                spaceId,
                limit
        );
        if (history.isEmpty()) {
            throw new SpaceRetrievalConfigurationNotFoundException();
        }
        return history;
    }

    /**
     * 追加完整配置并原子切换当前修订。
     *
     * <p>相同 expectedRevision、相同下一修订内容的网络重试返回已存在修订；
     * 任何其他修订漂移都报告 409，不会覆盖别人的更新。</p>
     */
    public SpaceRetrievalConfiguration update(
            PrincipalContext principal,
            String requestedSpaceId,
            long expectedRevision,
            RetrievalConfiguration configuration
    ) {
        requireAdmin(principal);
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        Objects.requireNonNull(configuration, "configuration must not be null");
        resolver.validate(configuration, hardLimits);

        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        SpaceRetrievalConfiguration current = currentConfiguration(principal, spaceId);
        if (isIdempotentRequest(current, expectedRevision, configuration)) {
            return current;
        }
        if (current.revision() != expectedRevision) {
            throw new SpaceRetrievalConfigurationConflictException();
        }

        SpaceRetrievalConfiguration revision = SpaceRetrievalConfiguration.create(
                principal.tenantId(),
                spaceId,
                expectedRevision + 1L,
                configuration,
                principal.principalId(),
                clock.instant()
        );
        SpaceRetrievalConfigurationStore.ActivationOutcome outcome =
                store.appendAndActivate(revision, expectedRevision);
        return switch (outcome) {
            case ACTIVATED -> revision;
            case ALREADY_ACTIVE -> store.findCurrent(principal.tenantId(), spaceId)
                    .filter(existing -> existing.revision() == revision.revision())
                    .filter(existing -> existing.fingerprint().equals(revision.fingerprint()))
                    .orElseThrow(SpaceRetrievalConfigurationConflictException::new);
            case REVISION_CONFLICT ->
                    throw new SpaceRetrievalConfigurationConflictException();
        };
    }

    private SpaceRetrievalConfiguration currentConfiguration(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        return store.findCurrent(principal.tenantId(), spaceId)
                .orElseThrow(SpaceRetrievalConfigurationNotFoundException::new);
    }

    private KnowledgeSpaceId readableSpace(
            PrincipalContext principal,
            String requestedSpaceId
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        AccessScope scope = accessPolicy.resolve(principal, Set.of(spaceId));
        if (!principal.tenantId().equals(scope.tenantId()) || !scope.allowsSpace(spaceId)) {
            throw new KnowledgeAccessDeniedException("knowledge space is not accessible");
        }
        return spaceId;
    }

    /** 相同当前内容是无操作；相同下一修订内容是上一次响应丢失后的安全重试。 */
    private static boolean isIdempotentRequest(
            SpaceRetrievalConfiguration current,
            long expectedRevision,
            RetrievalConfiguration requested
    ) {
        boolean sameContent = current.fingerprint().equals(requested.fingerprint());
        return sameContent && (current.revision() == expectedRevision
                || current.revision() == expectedRevision + 1L);
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
