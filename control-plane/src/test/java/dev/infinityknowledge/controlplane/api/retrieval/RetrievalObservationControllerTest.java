package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.RetrievalObservationReportService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReport;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 request 下钻路径、JWT 编排和不会泄露 payload 的响应契约。 */
class RetrievalObservationControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void exposesExactEventCountAndMetricsContractThroughJwtPrincipal() throws Exception {
        UUID requestId = UUID.randomUUID();
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        RetrievalObservationReportService service = mock(
                RetrievalObservationReportService.class
        );
        Jwt jwt = mock(Jwt.class);
        when(principals.create(jwt)).thenReturn(principal());
        when(service.latest(principal(), requestId)).thenReturn(report(requestId));
        var controller = new RetrievalObservationController(principals, service);

        RetrievalObservationReportView view = controller.latest(requestId, jwt);

        assertEquals(1, view.eventCount());
        assertEquals(1, view.events().size());
        assertEquals(1, view.metrics().size());
        assertEquals(
                RetrievalObservationStatus.SUCCEEDED.name(),
                view.metrics().getFirst().dimensions().technicalStatus()
        );
        assertEquals(
                RetrievalTerminalStatus.SUFFICIENT.name(),
                view.metrics().getFirst().dimensions().terminalStatus()
        );
        assertEquals(
                RetrievalObservationStatus.SUCCEEDED.name(),
                view.metrics().getFirst().dimensions().status()
        );
        assertEquals(
                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED.name(),
                view.metrics().getFirst().dimensions().stopReason()
        );
        verify(service).latest(principal(), requestId);

        Set<String> responseFields = Arrays.stream(
                        RetrievalObservationReportView.class.getRecordComponents()
                )
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(responseFields.contains("eventCount"));
        assertTrue(responseFields.contains("metrics"));
        assertFalse(responseFields.contains("metricFacts"));
        assertFalse(responseFields.contains("payload"));
        Set<String> eventFields = Arrays.stream(
                        RetrievalObservationReportView.EventView.class.getRecordComponents()
                )
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertFalse(eventFields.contains("payload"));
        assertFalse(eventFields.contains("queryFingerprint"));

        RequestMapping mapping = RetrievalObservationController.class.getAnnotation(
                RequestMapping.class
        );
        assertNotNull(mapping);
        assertArrayEquals(
                new String[]{"/api/v1/retrieval-observations/requests"},
                mapping.value()
        );
        GetMapping get = RetrievalObservationController.class
                .getMethod("latest", UUID.class, Jwt.class)
                .getAnnotation(GetMapping.class);
        assertNotNull(get);
        assertArrayEquals(new String[]{"/{requestId}"}, get.value());
    }

    private static RetrievalObservationReport report(UUID requestId) {
        UUID eventId = UUID.randomUUID();
        return new RetrievalObservationReport(
                principal().tenantId(),
                requestId,
                UUID.randomUUID(),
                RetrievalObservationPurpose.ONLINE,
                ObservationCompleteness.INCOMPLETE,
                Set.of(ObservationIncompleteReason.TERMINAL_MISSING),
                List.of(),
                List.of(),
                List.of(new RetrievalObservationReport.EventFact(
                        eventId,
                        0L,
                        Set.of(),
                        0,
                        0,
                        RetrievalObservationStage.EXECUTION_STARTED,
                        RetrievalObservationStatus.STARTED,
                        "NONE",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        NOW,
                        NOW,
                        0L,
                        1,
                        0,
                        RetrievalObservation.CURRENT_SCHEMA_VERSION
                )),
                List.of(new RetrievalObservationReport.MetricFactView(
                        eventId,
                        RetrievalObservationReport.MetricType.RUNTIME,
                        "retrieval.execution.count",
                        1,
                        MetricFact.Aggregation.COUNT,
                        1.0D,
                        MetricDimensions.builder()
                                .config("a".repeat(64))
                                .status(RetrievalObservationStatus.SUCCEEDED)
                                .terminalStatus(RetrievalTerminalStatus.SUFFICIENT)
                                .stopReason(
                                        RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED
                                )
                                .purpose(RetrievalObservationPurpose.ONLINE)
                                .timeSlice(
                                        NOW,
                                        MetricDimensions.TimeSliceGranularity.HOUR
                                )
                                .build(),
                        NOW,
                        null
                ))
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("reader-a"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );
    }
}
