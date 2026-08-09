package dev.infinityknowledge.domain;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.Citation;
import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证领域对象在进入 Runtime 前拒绝不安全或不可解释的数据。
 */
class DomainInvariantTest {

    /**
     * 验证查询预算不能无限放大。
     */
    @Test
    void rejectsUnboundedTopK() {
        PrincipalContext principal = principal("tenant-a");
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeQuery(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                101,
                Map.of()
        ));
    }

    /**
     * Agent 直连 Runtime 时也不能通过未知过滤键静默放宽查询。
     */
    @Test
    void rejectsUnknownRetrievalFilters() {
        PrincipalContext principal = principal("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> new KnowledgeQuery(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                8,
                Map.of("department", "engineering")
        ));
    }

    /**
     * 非 HTTP Agent 调用也应把空过滤键作为领域错误，而不是泄漏 NPE。
     */
    @Test
    void rejectsNullRetrievalFilterKeys() {
        PrincipalContext principal = principal("tenant-a");
        Map<String, String> filters = new HashMap<>();
        filters.put(null, "zh-CN");

        assertThrows(IllegalArgumentException.class, () -> new KnowledgeQuery(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                8,
                filters
        ));
    }

    /**
     * 验证非法相关性评分不会进入证据包。
     */
    @Test
    void rejectsNonFiniteEvidenceScore() {
        Citation citation = new Citation(
                DocumentId.random(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "订单故障手册",
                List.of("超时处理"),
                "https://knowledge.example/doc"
        );
        assertThrows(IllegalArgumentException.class, () -> new Evidence(
                UUID.randomUUID(),
                "检查连接池配置。",
                Double.NaN,
                80,
                Set.of(RetrievalChannel.KEYWORD),
                citation
        ));
    }

    /**
     * 验证租户标识会被规范化但不会改变业务值。
     */
    @Test
    void normalizesTenantId() {
        assertEquals("tenant-a", new TenantId(" tenant-a ").value());
    }

    /**
     * 创建测试主体。
     *
     * @param tenant 租户值
     * @return 主体上下文
     */
    private PrincipalContext principal(String tenant) {
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId("user-1"),
                Set.of("reader"),
                Set.of("engineering"),
                false
        );
    }
}
