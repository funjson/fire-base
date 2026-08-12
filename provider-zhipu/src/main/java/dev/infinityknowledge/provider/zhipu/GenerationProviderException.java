package dev.infinityknowledge.provider.zhipu;

/**
 * Stable structured-generation failure that never embeds credentials, source text or provider
 * response bodies in its message.
 */
public final class GenerationProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GenerationProviderException(String message) {
        super(message);
    }

    public GenerationProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
