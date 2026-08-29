package dev.infinityknowledge.jobs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeJobsConfigurationTest {

    @Test
    void recoveryTriggerInvokesEachBoundedActionOnce() {
        AtomicInteger connectorRuns = new AtomicInteger();
        AtomicInteger evaluationRuns = new AtomicInteger();
        var trigger = new KnowledgeJobsConfiguration().recoveryJobsTrigger(
                connectorRuns::incrementAndGet,
                evaluationRuns::incrementAndGet
        );

        trigger.run();

        assertThat(connectorRuns).hasValue(1);
        assertThat(evaluationRuns).hasValue(1);
    }

    @Test
    void projectionTriggerInvokesOnlyOnePollingCycle() {
        AtomicInteger pollingRuns = new AtomicInteger();
        var trigger = new KnowledgeJobsConfiguration().projectionPollingTrigger(
                pollingRuns::incrementAndGet
        );

        trigger.run();

        assertThat(pollingRuns).hasValue(1);
    }

    @Test
    void extractionTriggerInvokesOnlyOnePollingCycle() {
        AtomicInteger pollingRuns = new AtomicInteger();
        var trigger = new KnowledgeJobsConfiguration().extractionPollingTrigger(
                pollingRuns::incrementAndGet
        );

        trigger.run();

        assertThat(pollingRuns).hasValue(1);
    }
}
