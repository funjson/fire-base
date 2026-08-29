package dev.infinityknowledge.controlplane.api.common;

import dev.infinityknowledge.controlplane.application.common.OperationInProgressException;
import dev.infinityknowledge.controlplane.application.document.KnowledgeDocumentNotFoundException;
import dev.infinityknowledge.controlplane.application.common.WorkQueueSaturatedException;
import dev.infinityknowledge.controlplane.application.graph.GraphCapabilityUnavailableException;
import dev.infinityknowledge.controlplane.application.governance.KnowledgeSpaceConflictException;
import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigMissingException;
import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationConflictException;
import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationNotFoundException;
import dev.infinityknowledge.controlplane.application.retrieval.RetrievalObservationReportNotFoundException;
import dev.infinityknowledge.controlplane.application.ingestion.extraction.ExtractionRunNotFoundException;
import dev.infinityknowledge.spi.extraction.ActiveExtractionRunException;
import dev.infinityknowledge.spi.extraction.ExtractionPublicationInProgressException;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.wiki.KnowledgePageConflictException;
import dev.infinityknowledge.spi.management.DocumentLifecycleConflictException;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingException;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
            DocumentParseException.class,
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

    /** 报告活动文档或原件缺失，且不泄露对象存储细节。 */
    @ExceptionHandler(KnowledgeDocumentNotFoundException.class)
    ResponseEntity<ApiError> documentNotFound(KnowledgeDocumentNotFoundException missing) {
        return response(
                HttpStatus.NOT_FOUND,
                "KNOWLEDGE_DOCUMENT_NOT_FOUND",
                "The requested authorized document source does not exist"
        );
    }

    /** 抽取任务或 Item 缺失时不泄露其他空间和租户资源。 */
    @ExceptionHandler(ExtractionRunNotFoundException.class)
    ResponseEntity<ApiError> extractionRunNotFound(ExtractionRunNotFoundException missing) {
        return response(
                HttpStatus.NOT_FOUND,
                "EXTRACTION_RUN_NOT_FOUND",
                "The requested extraction run or item does not exist"
        );
    }

    /** 同一空间已有活动试验任务时返回稳定 Single-flight 冲突。 */
    @ExceptionHandler(ActiveExtractionRunException.class)
    ResponseEntity<ApiError> activeExtractionRun(ActiveExtractionRunException conflict) {
        return response(
                HttpStatus.CONFLICT,
                "EXTRACTION_RUN_ACTIVE",
                "An extraction run for this space is already active"
        );
    }

    /** 正式发布窗口不接受取消，避免页面终态与已提交数据相互矛盾。 */
    @ExceptionHandler(ExtractionPublicationInProgressException.class)
    ResponseEntity<ApiError> extractionPublicationInProgress(
            ExtractionPublicationInProgressException conflict
    ) {
        return response(
                HttpStatus.CONFLICT,
                conflict.code(),
                "The extraction item is publishing and cannot be cancelled"
        );
    }

    /** 将 Multipart 大小限制映射为稳定的负载过大响应。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException tooLarge) {
        return response(
                HttpStatus.CONTENT_TOO_LARGE,
                "SOURCE_TOO_LARGE",
                "The uploaded source exceeds the configured limit"
        );
    }

    /** 将应用层逐文件或批次字节门禁映射为同一 413 契约。 */
    @ExceptionHandler(SourceSizeLimitExceededException.class)
    ResponseEntity<ApiError> sourceTooLarge(SourceSizeLimitExceededException tooLarge) {
        return response(
                HttpStatus.CONTENT_TOO_LARGE,
                "SOURCE_TOO_LARGE",
                "The uploaded source exceeds the configured limit"
        );
    }

    /** 将同一资源的 Single-flight 冲突映射为业务冲突。 */
    @ExceptionHandler(OperationInProgressException.class)
    ResponseEntity<ApiError> operationInProgress(OperationInProgressException conflict) {
        return response(
                HttpStatus.CONFLICT,
                "OPERATION_IN_PROGRESS",
                "An operation for this resource is already running"
        );
    }

    /** 同一标识已被不同不可变配置占用时返回稳定的 Space 创建冲突。 */
    @ExceptionHandler(KnowledgeSpaceConflictException.class)
    ResponseEntity<ApiError> knowledgeSpaceConflict(
            KnowledgeSpaceConflictException conflict
    ) {
        return response(
                HttpStatus.CONFLICT,
                conflict.code(),
                "The knowledge space already exists with a different configuration"
        );
    }

    /** 旧 Space 缺失不可变处理配置时拒绝静默回填，并给出可执行的重建提示。 */
    @ExceptionHandler(SpaceDocumentProcessingConfigMissingException.class)
    ResponseEntity<ApiError> spaceDocumentProcessingConfigMissing(
            SpaceDocumentProcessingConfigMissingException missing
    ) {
        return response(
                HttpStatus.CONFLICT,
                SpaceDocumentProcessingConfigMissingException.CODE,
                SpaceDocumentProcessingConfigMissingException.USER_MESSAGE
        );
    }

    /** 已授权 Space 尚无检索配置时返回稳定 404。 */
    @ExceptionHandler(SpaceRetrievalConfigurationNotFoundException.class)
    ResponseEntity<ApiError> spaceRetrievalConfigurationNotFound(
            SpaceRetrievalConfigurationNotFoundException missing
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "SPACE_RETRIEVAL_CONFIGURATION_NOT_FOUND",
                "The requested space retrieval configuration does not exist"
        );
    }

    /** 配置期望修订过期或同修订内容不一致时返回稳定 409。 */
    @ExceptionHandler(SpaceRetrievalConfigurationConflictException.class)
    ResponseEntity<ApiError> spaceRetrievalConfigurationConflict(
            SpaceRetrievalConfigurationConflictException conflict
    ) {
        return response(
                HttpStatus.CONFLICT,
                "SPACE_RETRIEVAL_CONFIGURATION_REVISION_CONFLICT",
                "The space retrieval configuration changed; reload and retry"
        );
    }

    /** 当前 JWT 租户内不存在指定 request 观测时返回稳定 404。 */
    @ExceptionHandler(RetrievalObservationReportNotFoundException.class)
    ResponseEntity<ApiError> retrievalObservationNotFound(
            RetrievalObservationReportNotFoundException missing
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "RETRIEVAL_OBSERVATION_NOT_FOUND",
                "The requested retrieval observation does not exist"
        );
    }

    /** 将知识页乐观锁和生命周期冲突映射为稳定的 409 响应。 */
    @ExceptionHandler(KnowledgePageConflictException.class)
    ResponseEntity<ApiError> knowledgePageConflict(KnowledgePageConflictException conflict) {
        return response(
                HttpStatus.CONFLICT,
                "KNOWLEDGE_PAGE_CONFLICT",
                "The knowledge page changed or cannot perform this transition"
        );
    }

    /** 将文档乐观锁和非法生命周期流转映射为 409。 */
    @ExceptionHandler(DocumentLifecycleConflictException.class)
    ResponseEntity<ApiError> documentLifecycleConflict(
            DocumentLifecycleConflictException conflict
    ) {
        return response(
                HttpStatus.CONFLICT,
                "DOCUMENT_LIFECYCLE_CONFLICT",
                "The document changed or cannot perform this transition"
        );
    }

    /** 将有界执行器饱和映射为可重试的服务状态。 */
    @ExceptionHandler(WorkQueueSaturatedException.class)
    ResponseEntity<ApiError> workQueueSaturated(WorkQueueSaturatedException saturated) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WORK_QUEUE_SATURATED",
                "The service is temporarily unable to accept background work"
        );
    }

    /** 报告当前部署有意关闭的可选图能力。 */
    @ExceptionHandler(GraphCapabilityUnavailableException.class)
    ResponseEntity<ApiError> graphUnavailable(GraphCapabilityUnavailableException unavailable) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GRAPH_CAPABILITY_UNAVAILABLE",
                "The graph capability is not enabled for this deployment"
        );
    }

    /**
     * 报告无法在既定模型预算和截止时间内安全完成的语义切分。
     *
     * <p>调用方不能把本次失败静默解释为确定性切分成功；运维可重试，或显式切换
     * 到具有不同处理指纹的基线策略。</p>
     */
    @ExceptionHandler(SemanticChunkingException.class)
    ResponseEntity<ApiError> semanticChunkingUnavailable(
            SemanticChunkingException unavailable
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                unavailable.code(),
                "Semantic chunking could not be completed within its configured limits"
        );
    }

    /** Space 固化实现与当前部署发生漂移时拒绝继续摄取。 */
    @ExceptionHandler(DocumentProcessingContractMismatchException.class)
    ResponseEntity<ApiError> documentProcessingContractMismatch(
            DocumentProcessingContractMismatchException mismatch
    ) {
        return response(
                HttpStatus.CONFLICT,
                DocumentProcessingContractMismatchException.CODE,
                "The current processing implementation differs from the space contract"
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
        String requestId = RequestCorrelationFilter.currentRequestId();
        LOGGER.error(
                "Unhandled API failure: requestId={}, diagnostic={}",
                requestId,
                LogSafeExceptionDiagnostic.format(failure)
        );
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
