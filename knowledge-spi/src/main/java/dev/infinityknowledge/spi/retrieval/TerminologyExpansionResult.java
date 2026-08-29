package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.Objects;
import java.util.Optional;

/**
 * 表示术语资源查找的有界结果和可观测实现版本。
 *
 * @param expansion 有有效增量时的唯一 Q1 候选
 * @param provider 术语 Provider 标识
 * @param resourceVersion 实际读取的术语资源版本
 */
public record TerminologyExpansionResult(
        Optional<TerminologyExpansion> expansion,
        String provider,
        String resourceVersion
) {

    /** 校验外部实现的结果和稳定身份。 */
    public TerminologyExpansionResult {
        expansion = Objects.requireNonNull(expansion, "expansion must not be null");
        provider = DomainChecks.requiredText(provider, "provider", 64);
        resourceVersion = DomainChecks.requiredText(
                resourceVersion,
                "resourceVersion",
                128
        );
    }

    /** 返回资源不可用或没有有效等价术语时的空结果。 */
    public static TerminologyExpansionResult none(String provider, String resourceVersion) {
        return new TerminologyExpansionResult(
                Optional.empty(),
                provider,
                resourceVersion
        );
    }
}
