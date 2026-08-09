package dev.infinityknowledge.spi.access;

/**
 * 表示认证主体在当前租户中没有任何满足请求的知识读取权限。
 */
public final class KnowledgeAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 创建不泄露资源是否存在的拒绝异常。
     *
     * @param message 稳定拒绝原因
     */
    public KnowledgeAccessDeniedException(String message) {
        super(message);
    }
}
