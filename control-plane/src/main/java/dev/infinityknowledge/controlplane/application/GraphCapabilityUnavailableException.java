package dev.infinityknowledge.controlplane.application;

/** Raised when the optional graph capability is disabled for this deployment. */
public final class GraphCapabilityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GraphCapabilityUnavailableException() {
        super("graph capability is not enabled");
    }
}
