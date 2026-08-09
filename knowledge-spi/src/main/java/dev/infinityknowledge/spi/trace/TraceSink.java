package dev.infinityknowledge.spi.trace;

import dev.infinityknowledge.domain.trace.RetrievalTrace;

/**
 * 持久化不含知识正文的检索 Trace。
 */
@FunctionalInterface
public interface TraceSink {

    /**
     * 追加一条不可变 Trace；重复 ID 必须幂等处理。
     *
     * @param trace 检索 Trace
     */
    void append(RetrievalTrace trace);
}

