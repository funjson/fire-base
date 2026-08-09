package dev.infinityknowledge.provider.zhipu;

import java.time.Duration;

/**
 * 抽象重试等待，允许测试使用无等待实现。
 */
@FunctionalInterface
public interface RetrySleeper {

    /**
     * 等待指定时长；中断时实现必须恢复线程中断标记并终止调用。
     *
     * @param duration 等待时长
     */
    void sleep(Duration duration);
}

