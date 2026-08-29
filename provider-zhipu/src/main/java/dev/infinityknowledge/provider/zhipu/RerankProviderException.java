package dev.infinityknowledge.provider.zhipu;

/**
 * 表示智谱文本重排协议、网络或响应校验失败。
 *
 * <p>异常消息只描述稳定错误类别，不得包含 API Key、查询正文、候选正文或上游响应正文。</p>
 */
public final class RerankProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 创建不包含上游敏感正文的异常。
     *
     * @param message 稳定错误描述
     */
    public RerankProviderException(String message) {
        super(message);
    }

    /**
     * 创建保留本地异常原因的安全异常。
     *
     * @param message 稳定错误描述
     * @param cause 本地异常原因
     */
    public RerankProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
