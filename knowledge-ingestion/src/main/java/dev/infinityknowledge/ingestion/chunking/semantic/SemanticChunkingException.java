package dev.infinityknowledge.ingestion.chunking.semantic;

import java.util.Objects;

/**
 * 表示语义切分无法安全完成，调用方必须重试或显式选择确定性基线。
 */
public final class SemanticChunkingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * 创建不携带底层异常的稳定失败。
     *
     * @param code 可观测且稳定的失败代码
     * @param message 不包含正文和模型原始响应的安全摘要
     */
    public SemanticChunkingException(String code, String message) {
        super(message);
        this.code = requiredCode(code);
    }

    /**
     * 创建保留底层原因的稳定失败。
     *
     * @param code 可观测且稳定的失败代码
     * @param message 不包含正文和模型原始响应的安全摘要
     * @param cause 底层异常，仅供受控日志和诊断链使用
     */
    public SemanticChunkingException(String code, String message, Throwable cause) {
        super(message, Objects.requireNonNull(cause, "cause must not be null"));
        this.code = requiredCode(code);
    }

    /** 返回稳定失败代码。 */
    public String code() {
        return code;
    }

    private static String requiredCode(String code) {
        Objects.requireNonNull(code, "code must not be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        return code;
    }
}
