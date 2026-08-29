package dev.infinityknowledge.provider.zhipu;

/**
 * 智谱 Tokenizer 的稳定失败类型。
 *
 * <p>消息只允许描述失败类别或 HTTP 状态，不得包含 API Key、Prompt 或响应正文。</p>
 */
public final class TokenizerProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TokenizerProviderException(String message) {
        super(message);
    }

    public TokenizerProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
