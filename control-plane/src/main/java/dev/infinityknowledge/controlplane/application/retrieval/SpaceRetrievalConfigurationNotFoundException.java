package dev.infinityknowledge.controlplane.application.retrieval;

/**
 * 表示已授权请求对应的 Space 尚无可读取的检索配置。
 *
 * <p>异常不携带租户、Space 或数据库细节，由 HTTP Adapter 映射为稳定 404。</p>
 */
public final class SpaceRetrievalConfigurationNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 创建不暴露资源标识的缺失异常。 */
    public SpaceRetrievalConfigurationNotFoundException() {
        super("space retrieval configuration does not exist");
    }
}
