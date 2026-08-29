package dev.infinityknowledge.controlplane.application.common;

/**
 * 表示目标资源已被同类单飞操作占用。
 */
public final class OperationInProgressException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public OperationInProgressException(String message) {
        super(message);
    }
}
