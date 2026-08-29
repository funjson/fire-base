package dev.infinityknowledge.controlplane.api.common;

import dev.infinityknowledge.controlplane.application.common.OperationInProgressException;
import dev.infinityknowledge.controlplane.application.common.WorkQueueSaturatedException;
import dev.infinityknowledge.controlplane.application.governance.KnowledgeSpaceConflictException;
import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationConflictException;
import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationNotFoundException;
import dev.infinityknowledge.controlplane.application.retrieval.RetrievalObservationReportNotFoundException;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.extraction.ExtractionPublicationInProgressException;
import dev.infinityknowledge.spi.wiki.KnowledgePageConflictException;
import dev.infinityknowledge.spi.management.DocumentLifecycleConflictException;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingException;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证异步任务接纳失败使用稳定、可重试的 HTTP 语义。
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler(
            Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void mapsSingleFlightConflictTo409() {
        var response = handler.operationInProgress(
                new OperationInProgressException("already running")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("OPERATION_IN_PROGRESS", response.getBody().code());
    }

    @Test
    void mapsQueueSaturationTo503() {
        var response = handler.workQueueSaturated(
                new WorkQueueSaturatedException(
                        "queue full",
                        new IllegalStateException("rejected")
                )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("WORK_QUEUE_SATURATED", response.getBody().code());
    }

    @Test
    void mapsKnowledgeSpaceConfigurationConflictToItsStable409Code() {
        var response = handler.knowledgeSpaceConflict(
                new KnowledgeSpaceConflictException(
                        "SPACE_ALREADY_EXISTS_WITH_DIFFERENT_CONFIGURATION",
                        "different definition"
                )
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "SPACE_ALREADY_EXISTS_WITH_DIFFERENT_CONFIGURATION",
                response.getBody().code()
        );
    }

    @Test
    void mapsDocumentProcessingConfigChangeToStable409Code() {
        var response = handler.documentProcessingContractMismatch(
                new DocumentProcessingContractMismatchException()
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                DocumentProcessingContractMismatchException.CODE,
                response.getBody().code()
        );
    }

    @Test
    void mapsMissingSpaceRetrievalConfigurationToStable404Code() {
        var response = handler.spaceRetrievalConfigurationNotFound(
                new SpaceRetrievalConfigurationNotFoundException()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "SPACE_RETRIEVAL_CONFIGURATION_NOT_FOUND",
                response.getBody().code()
        );
    }

    @Test
    void mapsStaleSpaceRetrievalConfigurationRevisionToStable409Code() {
        var response = handler.spaceRetrievalConfigurationConflict(
                new SpaceRetrievalConfigurationConflictException()
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "SPACE_RETRIEVAL_CONFIGURATION_REVISION_CONFLICT",
                response.getBody().code()
        );
    }

    @Test
    void mapsMissingRetrievalObservationToStable404Code() {
        var response = handler.retrievalObservationNotFound(
                new RetrievalObservationReportNotFoundException()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("RETRIEVAL_OBSERVATION_NOT_FOUND", response.getBody().code());
    }

    @Test
    void mapsKnowledgePageConflictTo409() {
        var response = handler.knowledgePageConflict(
                new KnowledgePageConflictException("version conflict")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("KNOWLEDGE_PAGE_CONFLICT", response.getBody().code());
    }

    @Test
    void mapsDocumentLifecycleConflictTo409() {
        var response = handler.documentLifecycleConflict(
                new DocumentLifecycleConflictException("version conflict")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOCUMENT_LIFECYCLE_CONFLICT", response.getBody().code());
    }

    @Test
    void mapsSemanticChunkingFailureTo503WithoutChangingItsStableCode() {
        var response = handler.semanticChunkingUnavailable(
                new SemanticChunkingException(
                        "SEMANTIC_CHUNKING_TIMEOUT",
                        "safe timeout"
                )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SEMANTIC_CHUNKING_TIMEOUT", response.getBody().code());
    }

    @Test
    void mapsApplicationSourceBudgetTo413() {
        var response = handler.sourceTooLarge(new SourceSizeLimitExceededException());

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SOURCE_TOO_LARGE", response.getBody().code());
    }

    @Test
    void mapsPublishingCancellationConflictToStable409Code() {
        var response = handler.extractionPublicationInProgress(
                new ExtractionPublicationInProgressException()
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                ExtractionPublicationInProgressException.CODE,
                response.getBody().code()
        );
    }

    @Test
    void logsUnknownFailureWithRequestIdAndSanitizedStack(
            CapturedOutput output
    ) throws Exception {
        UUID requestId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, requestId.toString());
        var servletResponse = new MockHttpServletResponse();
        var handled = new AtomicReference<ResponseEntity<ApiError>>();
        var failure = new IllegalStateException(
                "private query must not be logged",
                new SQLException(
                        "SQL containing private document text must not be logged",
                        "42703",
                        0
                )
        );

        new RequestCorrelationFilter().doFilter(
                request,
                servletResponse,
                (servletRequest, response) -> handled.set(
                        handler.unexpected(failure)
                )
        );

        assertNotNull(handled.get());
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                handled.get().getStatusCode()
        );
        assertNotNull(handled.get().getBody());
        assertEquals("INTERNAL_ERROR", handled.get().getBody().code());
        assertEquals(requestId.toString(), handled.get().getBody().requestId());
        assertTrue(output.getAll().contains("requestId=" + requestId));
        assertTrue(output.getAll().contains(IllegalStateException.class.getName()));
        assertTrue(output.getAll().contains(SQLException.class.getName()));
        assertTrue(output.getAll().contains("sqlState=42703"));
        assertTrue(output.getAll().contains("ApiExceptionHandlerTest.java"));
        assertFalse(
                output.getAll().contains("private query must not be logged")
        );
        assertFalse(
                output.getAll().contains(
                        "private document text must not be logged"
                )
        );
    }
}
