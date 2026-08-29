package dev.infinityknowledge.domain.retrieval.configuration;

/**
 * 表示 Space 配置或请求覆盖试图突破部署级检索硬上限。
 */
public final class RetrievalConfigurationLimitExceededException
        extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** 创建包含稳定字段语义的越界异常。 */
    public RetrievalConfigurationLimitExceededException(String message) {
        super(message);
    }
}
