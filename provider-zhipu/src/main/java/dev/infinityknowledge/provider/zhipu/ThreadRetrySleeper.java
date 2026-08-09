package dev.infinityknowledge.provider.zhipu;

import java.time.Duration;
import java.util.Objects;

/**
 * 使用当前线程等待实现生产环境退避。
 */
public final class ThreadRetrySleeper implements RetrySleeper {

    /**
     * 使用 Java Duration 精度等待，并把中断转换为稳定失败。
     *
     * @param duration 等待时长
     */
    @Override
    public void sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding retry was interrupted", interrupted);
        }
    }
}

