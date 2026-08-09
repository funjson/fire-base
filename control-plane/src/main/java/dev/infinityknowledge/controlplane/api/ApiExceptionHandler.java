package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.OperationInProgressException;
import dev.infinityknowledge.controlplane.application.WorkQueueSaturatedException;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 将已知失败映射为稳定 HTTP 响应，避免返回数据库或 Provider 异常细节。
 */
@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock;

    /**
     * 创建使用统一时钟的异常处理器。
     *
     * @param clock UTC 时钟
     */
    public ApiExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 将知识访问拒绝映射为 403，且不暴露资源是否存在。
     *
     * @param denied 访问拒绝
     * @return 错误响应
     */
    @ExceptionHandler({
            KnowledgeAccessDeniedException.class,
            AccessDeniedException.class
    })
    ResponseEntity<ApiError> accessDenied(Exception denied) {
        return response(
                HttpStatus.FORBIDDEN,
                "KNOWLEDGE_ACCESS_DENIED",
                "The principal cannot access the requested knowledge scope"
        );
    }

    /**
     * 将请求校验和领域参数错误映射为 400。
     *
     * @param invalid 非法参数
     * @return 错误响应
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception invalid) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "The request is invalid"
        );
    }

    /**
     * Reports a single-flight conflict without presenting it as an internal failure.
     */
    @ExceptionHandler(OperationInProgressException.class)
    ResponseEntity<ApiError> operationInProgress(OperationInProgressException conflict) {
        return response(
                HttpStatus.CONFLICT,
                "OPERATION_IN_PROGRESS",
                "An operation for this resource is already running"
        );
    }

    /**
     * Reports bounded executor saturation as a retryable service condition.
     */
    @ExceptionHandler(WorkQueueSaturatedException.class)
    ResponseEntity<ApiError> workQueueSaturated(WorkQueueSaturatedException saturated) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WORK_QUEUE_SATURATED",
                "The service is temporarily unable to accept background work"
        );
    }

    /**
     * 隐藏未知基础设施错误，同时记录关联请求标识供运维排查。
     *
     * @param failure 未分类异常
     * @return 不泄露内部实现的 500 响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception failure) {
        LOGGER.error("Unhandled API failure", failure);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed"
        );
    }

    /**
     * 创建带 UTC 时间的错误响应。
     *
     * @param status HTTP 状态
     * @param code 稳定错误码
     * @param message 安全摘要
     * @return 错误响应
     */
    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(new ApiError(
                        code,
                        message,
                        RequestCorrelationFilter.currentRequestId(),
                        Instant.now(clock)
                ));
    }
}
