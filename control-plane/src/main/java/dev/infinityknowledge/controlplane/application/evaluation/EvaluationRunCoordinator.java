package dev.infinityknowledge.controlplane.application.evaluation;

import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 基于持久化执行快照领取并恢复只读评测运行。
 */
@Component
public final class EvaluationRunCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationRunCoordinator.class);

    private final EvaluationStore store;
    private final Executor executor;
    private final EvaluationRunWorker worker;
    private final AsyncRunProperties properties;
    private final Clock clock;
    private final String workerId = "evaluation:" + UUID.randomUUID();

    public EvaluationRunCoordinator(
            EvaluationStore store,
            @Qualifier("evaluationExecutor") Executor executor,
            EvaluationRunWorker worker,
            AsyncRunProperties properties,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean submit(TenantId tenantId, UUID runId) {
        Instant now = clock.instant();
        var claimed = store.claim(
                tenantId, runId, workerId, now.plus(properties.leaseDuration()), now
        );
        if (claimed.isEmpty()) {
            return true;
        }
        var lease = claimed.orElseThrow();
        boolean submitted = dispatch(lease);
        if (!submitted) {
            worker.fail(lease, "WORK_QUEUE_SATURATED");
        }
        return submitted;
    }

    /** 恢复一次有界评测任务；触发频率由 knowledge-jobs 负责。 */
    public void recover() {
        Instant now = clock.instant();
        for (EvaluationStore.WorkLease lease : store.claimAvailable(
                workerId,
                properties.recoveryBatchSize(),
                now.plus(properties.leaseDuration()),
                now
        )) {
            dispatch(lease);
        }
    }

    private boolean dispatch(EvaluationStore.WorkLease lease) {
        var byId = new HashMap<UUID, EvaluationStore.Case>();
        for (EvaluationStore.Case value : store.cases(lease.tenantId(), lease.datasetId())) {
            byId.put(value.id(), value);
        }
        List<EvaluationStore.Case> cases = lease.caseIds().stream().map(byId::get).toList();
        if (cases.stream().anyMatch(Objects::isNull)) {
            worker.fail(lease, "EVALUATION_CASE_SNAPSHOT_MISSING");
            return true;
        }
        try {
            executor.execute(() -> worker.execute(lease, cases));
            return true;
        } catch (RejectedExecutionException saturated) {
            LOGGER.warn(
                    "Evaluation executor is saturated; task was not submitted: runId={}, tenantId={}",
                    lease.runId(), lease.tenantId().value()
            );
            return false;
        }
    }
}
