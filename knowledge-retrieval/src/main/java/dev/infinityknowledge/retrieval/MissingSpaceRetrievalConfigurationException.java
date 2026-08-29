package dev.infinityknowledge.retrieval;

/** Space 未物化检索配置时的稳定配置错误。 */
public final class MissingSpaceRetrievalConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MissingSpaceRetrievalConfigurationException(String message) {
        super(message);
    }
}
