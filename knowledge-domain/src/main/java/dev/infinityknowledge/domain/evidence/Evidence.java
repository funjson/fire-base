package dev.infinityknowledge.domain.evidence;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 表示已经授权、融合并可直接交给 Agent 的一条证据。
 *
 * @param id 证据标识
 * @param content 证据正文
 * @param relevance 融合后的相关性
 * @param authority 权威等级
 * @param channels 支持该证据的召回通道
 * @param citation 原文引用
 */
public record Evidence(
        UUID id,
        String content,
        double relevance,
        int authority,
        Set<RetrievalChannel> channels,
        Citation citation
) {

    /**
     * 校验证据评分、权威等级和引用。
     */
    public Evidence {
        Objects.requireNonNull(id, "evidence id must not be null");
        content = DomainChecks.requiredText(content, "evidence content", 100_000);
        relevance = DomainChecks.unitScore(relevance, "relevance");
        if (authority < 0 || authority > 100) {
            throw new IllegalArgumentException("authority must be between 0 and 100");
        }
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("evidence must contain at least one channel");
        }
        Objects.requireNonNull(citation, "citation must not be null");
    }
}

