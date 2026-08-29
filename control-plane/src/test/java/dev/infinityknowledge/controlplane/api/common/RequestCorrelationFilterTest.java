package dev.infinityknowledge.controlplane.api.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 HTTP、应用查询和 Trace 共享同一个规范化请求标识。
 */
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void reusesValidUuidAcrossHeaderAndRequestAttribute() throws Exception {
        UUID requestId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, requestId.toString());
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<UUID>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                observed.set((UUID) ((HttpServletRequest) servletRequest)
                        .getAttribute(RequestCorrelationFilter.ATTRIBUTE))
        );

        assertEquals(requestId, observed.get());
        assertEquals(
                requestId.toString(),
                response.getHeader(RequestCorrelationFilter.HEADER)
        );
    }

    @Test
    void replacesNonUuidCallerValueOnce() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, "acceptance-request-001");
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<UUID>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                observed.set((UUID) ((HttpServletRequest) servletRequest)
                        .getAttribute(RequestCorrelationFilter.ATTRIBUTE))
        );

        assertNotNull(observed.get());
        assertNotEquals(
                "acceptance-request-001",
                response.getHeader(RequestCorrelationFilter.HEADER)
        );
        assertEquals(
                observed.get(),
                UUID.fromString(response.getHeader(RequestCorrelationFilter.HEADER))
        );
    }

    @Test
    void exposesRequestIdInMdcOnlyWhileRequestIsRunning() throws Exception {
        UUID requestId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, requestId.toString());
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<String>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                observed.set(RequestCorrelationFilter.currentRequestId())
        );

        assertEquals(requestId.toString(), observed.get());
        assertEquals("", RequestCorrelationFilter.currentRequestId());
        assertNull(org.slf4j.MDC.get("request_id"));
    }

    @Test
    void removesMdcWhenRequestEscapesWithFailure() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<String>();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    observed.set(RequestCorrelationFilter.currentRequestId());
                    throw new IllegalStateException("synthetic failure");
                }
        ));

        assertNotNull(observed.get());
        assertEquals(
                observed.get(),
                response.getHeader(RequestCorrelationFilter.HEADER)
        );
        assertEquals("", RequestCorrelationFilter.currentRequestId());
        assertNull(org.slf4j.MDC.get("request_id"));
    }
}
