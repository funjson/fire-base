package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 表示一次受 Space 术语资源约束的在线等价术语扩展请求。
 *
 * <p>请求只携带独立逻辑查询、物化资源标识和硬数量上限，不携带 Tenant、ACL
 * 或会话；术语实现不得据此扩大授权范围或执行通用查询改写。</p>
 *
 * @param originalQuery 规范化后的 Q0
 * @param terminologyResourceId 当前 Space 物化配置选择的术语资源
 * @param maximumExpansionTerms 单次最多采用的等价术语数
 */
public record TerminologyExpansionRequest(
        String originalQuery,
        String terminologyResourceId,
        int maximumExpansionTerms
) {

    /** 校验查询、资源身份和查询放大边界。 */
    public TerminologyExpansionRequest {
        originalQuery = DomainChecks.requiredText(originalQuery, "originalQuery", 16_000);
        terminologyResourceId = DomainChecks.requiredText(
                terminologyResourceId,
                "terminologyResourceId",
                128
        );
        if (!terminologyResourceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "terminologyResourceId contains unsafe characters"
            );
        }
        if (maximumExpansionTerms < 1 || maximumExpansionTerms > 64) {
            throw new IllegalArgumentException(
                    "maximumExpansionTerms must be between 1 and 64"
            );
        }
    }
}
