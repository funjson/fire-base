package dev.infinityknowledge.controlplane.application.retrieval;

/**
 * 表示检索配置的期望修订已经过期，或同一修订出现不同内容。
 */
public final class SpaceRetrievalConfigurationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 创建不暴露存储实现的乐观并发冲突。 */
    public SpaceRetrievalConfigurationConflictException() {
        super("space retrieval configuration revision conflict");
    }
}
