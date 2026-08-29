package dev.infinityknowledge.controlplane.application.graph;

/** 当本部署未启用可选图谱能力时抛出。 */
public final class GraphCapabilityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GraphCapabilityUnavailableException() {
        super("graph capability is not enabled");
    }
}
