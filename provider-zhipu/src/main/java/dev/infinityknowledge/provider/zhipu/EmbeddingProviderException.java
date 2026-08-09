package dev.infinityknowledge.provider.zhipu;

/**
 * 表示智谱协议、网络或限流错误，异常信息不会包含密钥和响应正文。
 */
public final class EmbeddingProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 创建不携带敏感正文的 Provider 异常。
     *
     * @param message 稳定错误摘要
     */
    public EmbeddingProviderException(String message) {
        super(message);
    }

    /**
     * 创建保留异常类型但不拼接敏感正文的 Provider 异常。
     *
     * @param message 稳定错误摘要
     * @param cause 原始异常
     */
    public EmbeddingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
