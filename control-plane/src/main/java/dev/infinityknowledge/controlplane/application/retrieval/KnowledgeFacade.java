package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.controlplane.api.retrieval.KnowledgeQueryRequest;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.retrieval.EvidenceRequirement;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.KnowledgeGateway;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 为 HTTP 控制器构造不可伪造身份的领域查询。
 */
@Service
public final class KnowledgeFacade {
    private final KnowledgeGateway gateway;
    private final JwtPrincipalContextFactory principalFactory;

    /**
     * 创建知识应用服务。
     *
     * @param gateway 知识运行时入口
     * @param principalFactory JWT 主体工厂
     */
    public KnowledgeFacade(
            KnowledgeGateway gateway,
            JwtPrincipalContextFactory principalFactory
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.principalFactory = Objects.requireNonNull(
                principalFactory,
                "principalFactory must not be null"
        );
    }

    /**
     * 将 API 请求与已验证 JWT 组合后执行检索。
     *
     * @param request API 请求
     * @param jwt 已验证 JWT
     * @param requestId HTTP 入口生成的统一请求标识
     * @return 证据包
     */
    public EvidenceBundle query(
            KnowledgeQueryRequest request,
            Jwt jwt,
            UUID requestId
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        return gateway.retrieve(new KnowledgeQuery(
                requestId,
                principalFactory.create(jwt),
                request.query(),
                request.spaceIds().stream()
                        .map(KnowledgeSpaceId::new)
                        .collect(Collectors.toUnmodifiableSet()),
                request.topK() == null ? 8 : request.topK(),
                request.filters(),
                new RetrievalConstraintInput(
                        request.constraints().relaxableFilters(),
                        request.constraints().narrowingFilters()
                ),
                request.retrievalTarget(),
                request.evidenceRequirements().stream()
                        .map(value -> new EvidenceRequirement(
                                value.id(), value.description()
                        ))
                        .toList(),
                request.configurationOverride().toDomain(),
                Boolean.TRUE.equals(request.testMode())
                        ? RetrievalObservationPurpose.TEST_PLAZA
                        : RetrievalObservationPurpose.ONLINE
        ));
    }
}
