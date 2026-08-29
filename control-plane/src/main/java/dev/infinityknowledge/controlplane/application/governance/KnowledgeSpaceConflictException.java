package dev.infinityknowledge.controlplane.application.governance;

import java.util.Objects;

/** Space 创建请求与已经固化的不可变处理配置冲突。 */
public final class KnowledgeSpaceConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private final String code;

    /** 创建带稳定业务错误码的冲突。 */
    public KnowledgeSpaceConflictException(String code, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /** 返回可供 API 与页面稳定识别的冲突错误码。 */
    public String code() {
        return code;
    }
}
