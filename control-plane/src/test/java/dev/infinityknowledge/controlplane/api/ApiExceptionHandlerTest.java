package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.OperationInProgressException;
import dev.infinityknowledge.controlplane.application.WorkQueueSaturatedException;
import dev.infinityknowledge.spi.wiki.KnowledgePageConflictException;
import dev.infinityknowledge.spi.management.DocumentLifecycleConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证异步任务接纳失败使用稳定、可重试的 HTTP 语义。
 */
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
}
