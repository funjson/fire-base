package dev.infinityknowledge.agent.client;

import java.io.Serial;

/** 对 Agent 暴露稳定分类、但不包含服务端正文或凭证的调用失败。 */
public final class KnowledgeClientException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String code;
    private final String requestId;
    private final boolean retryable;

    public KnowledgeClientException(
            int statusCode,
            String code,
            String message,
            String requestId,
            boolean retryable
    ) {
        this(statusCode, code, message, requestId, retryable, null);
    }

    public KnowledgeClientException(
            int statusCode,
            String code,
            String message,
            String requestId,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
        this.requestId = requestId;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    public boolean retryable() {
        return retryable;
    }
}
