package dev.infinityknowledge.retrieval.component;

/**
 * 表示 Space 生效配置选择了当前部署没有安装的检索组件。
 */
public final class RetrievalComponentUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /** 使用不包含查询正文或模型响应的稳定配置错误说明创建异常。 */
    public RetrievalComponentUnavailableException(String message) {
        super(message);
    }
}
