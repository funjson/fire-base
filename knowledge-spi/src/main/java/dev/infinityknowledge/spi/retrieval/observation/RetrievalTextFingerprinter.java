package dev.infinityknowledge.spi.retrieval.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;

/**
 * 把查询和变体文本转换成可关联但不可直接还原的观测指纹。
 *
 * <p>实现必须使用部署方持有的密钥；普通无密钥摘要不能抵抗低熵查询的字典反推。</p>
 */
@FunctionalInterface
public interface RetrievalTextFingerprinter {

    /**
     * 计算不包含原文本的版本化指纹。
     *
     * @param text 查询或变体文本
     * @return 带算法和密钥版本的指纹
     */
    RetrievalObservationPayload.TextFingerprint fingerprint(String text);
}
