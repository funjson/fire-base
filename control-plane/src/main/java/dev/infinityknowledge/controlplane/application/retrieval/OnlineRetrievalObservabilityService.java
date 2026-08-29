package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.controlplane.config.RetrievalProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalStageDiagnostics;
import dev.infinityknowledge.evaluation.observation.query.RetrievalExecutionPage;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在线检索观测应用服务，统一时间窗、成熟度和 Space 授权口径。
 *
 * <p>文档白名单主体不能读取 Space 汇总，否则计数变化可能形成侧信道；P0 仅允许
 * {@link AccessScope.Mode#ALL}。近期执行在总请求量中可见，但只有超过检索总超时和
 * 事件投递宽限期后才进入终态观测率、观测完整率分母。</p>
 */
@Service
public final class OnlineRetrievalObservabilityService {
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final Duration EVENT_DELIVERY_GRACE = Duration.ofSeconds(30);
    private static final Duration CLIENT_CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(2);

    private final OnlineRetrievalObservabilityReader reader;
    private final AccessPolicy accessPolicy;
    private final Clock clock;
    private final Duration maturityDelay;

    /** 创建复用检索总超时并增加固定事件投递宽限期的应用服务。 */
    public OnlineRetrievalObservabilityService(
            OnlineRetrievalObservabilityReader reader,
            AccessPolicy accessPolicy,
            Clock clock,
            RetrievalProperties retrievalProperties
    ) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy,
                "accessPolicy must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(retrievalProperties, "retrievalProperties must not be null");
        this.maturityDelay = retrievalProperties.requestTimeout().plus(EVENT_DELIVERY_GRACE);
    }

    /** 查询固定 ONLINE 用途的窗口总览。 */
    public OnlineRetrievalOverview overview(PrincipalContext principal, Filters filters) {
        return reader.overview(query(principal, filters));
    }

    /** 查询固定 ONLINE 用途的分层诊断。 */
    public OnlineRetrievalStageDiagnostics stages(
            PrincipalContext principal,
            Filters filters
    ) {
        return reader.stages(query(principal, filters));
    }

    /** 查询固定 ONLINE 用途的安全执行分页。 */
    public RetrievalExecutionPage executions(
            PrincipalContext principal,
            Filters filters,
            int page,
            int size,
            RetrievalTerminalStatus terminalStatus,
            RetrievalStopReason stopReason
    ) {
        return reader.executions(
                query(principal, filters),
                new OnlineRetrievalObservabilityReader.ExecutionPageRequest(
                        page,
                        size,
                        Optional.ofNullable(terminalStatus),
                        Optional.ofNullable(stopReason)
                )
        );
    }

    private OnlineRetrievalObservabilityQuery query(
            PrincipalContext principal,
            Filters filters
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(filters, "filters must not be null");
        Instant now = clock.instant();
        Instant to = filters.to() == null ? now : filters.to();
        if (to.isAfter(now)) {
            if (to.isAfter(now.plus(CLIENT_CLOCK_SKEW_TOLERANCE))) {
                throw new IllegalArgumentException(
                        "online observability to exceeds the allowed client clock skew"
                );
            }
            // 浏览器与服务端的秒级时钟偏差不应让整页查询失败；仅对有限偏差收敛到服务端时间。
            to = now;
        }
        Instant from = filters.from() == null ? to.minus(DEFAULT_WINDOW) : filters.from();
        Optional<KnowledgeSpaceId> selectedSpace = optionalText(filters.spaceId())
                .map(KnowledgeSpaceId::new);
        AccessScope scope = accessPolicy.resolve(principal, Set.of());
        if (!principal.tenantId().equals(scope.tenantId())
                || scope.mode() != AccessScope.Mode.ALL
                || scope.spaceIds().isEmpty()
                || selectedSpace.filter(spaceId -> !scope.allowsSpace(spaceId)).isPresent()) {
            throw new KnowledgeAccessDeniedException(
                    "online aggregate observability requires full Space access"
            );
        }
        Instant maturityCutoff = minimum(to, now.minus(maturityDelay));
        return new OnlineRetrievalObservabilityQuery(
                principal.tenantId(),
                scope.spaceIds(),
                selectedSpace,
                from,
                to,
                maturityCutoff,
                optionalText(filters.configFingerprint()),
                optionalText(filters.dataIndexVersion())
        );
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(value.strip());
    }

    private static Instant minimum(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    /** 控制台允许覆盖的低基数筛选，不包含用途和任意指标标签。 */
    public record Filters(
            Instant from,
            Instant to,
            String spaceId,
            String configFingerprint,
            String dataIndexVersion
    ) {
    }
}
