package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReport;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 查询一次 request 的最新检索观测报告，并执行租户与 Space 双重隔离。
 *
 * <p>只读端口必须先按 JWT 租户查询；报告返回后再对事件、visit 和指标涉及的全部
 * Space 执行当前访问策略。授权失败不会返回部分报告，避免指标侧信道泄露。</p>
 */
@Service
public final class RetrievalObservationReportService {

    private final RetrievalObservationReportReader reader;
    private final AccessPolicy accessPolicy;

    /** 创建只依赖评测读端口和统一访问策略的应用服务。 */
    public RetrievalObservationReportService(
            RetrievalObservationReportReader reader,
            AccessPolicy accessPolicy
    ) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy,
                "accessPolicy must not be null"
        );
    }

    /** 返回当前主体可读取的最新 execution 报告。 */
    public RetrievalObservationReport latest(
            PrincipalContext principal,
            UUID requestId
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        RetrievalObservationReport report = reader.findLatestExecution(
                principal.tenantId(),
                requestId
        ).orElseThrow(RetrievalObservationReportNotFoundException::new);
        if (!principal.tenantId().equals(report.tenantId())) {
            throw new KnowledgeAccessDeniedException(
                    "retrieval observation report belongs to another tenant"
            );
        }
        authorizeSpaces(principal, report.involvedSpaceIds());
        return report;
    }

    private void authorizeSpaces(
            PrincipalContext principal,
            Set<KnowledgeSpaceId> involvedSpaces
    ) {
        if (involvedSpaces.isEmpty()) {
            return;
        }
        AccessScope scope = accessPolicy.resolve(principal, involvedSpaces);
        boolean everySpaceAllowed = involvedSpaces.stream().allMatch(scope::allowsSpace);
        if (!principal.tenantId().equals(scope.tenantId()) || !everySpaceAllowed) {
            throw new KnowledgeAccessDeniedException(
                    "retrieval observation spaces are not accessible"
            );
        }
    }
}
