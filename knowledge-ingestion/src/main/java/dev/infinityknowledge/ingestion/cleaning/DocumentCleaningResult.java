package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示一次确定性内容治理后的元素去向和可审计结果。
 *
 * <p>{@code indexableElements} 是唯一允许进入 Chunker 的列表；
 * {@code metadataOnlyElements} 只供后续治理元数据持久化使用。两组元素保留
 * Parser 创建的元素身份、顺序、正文和来源定位；已排除结构标题会从
 * 后续元素的章节路径中移除，仅元数据元素会补充稳定的治理属性。</p>
 *
 * @param indexableElements 保持来源顺序的可索引元素
 * @param metadataOnlyElements 保持来源顺序但不得进入 Chunker 的治理元数据元素
 * @param reasonCodeCounts 各稳定决策原因码的元素命中数
 * @param contract 本次清洗使用的稳定处理契约
 * @param decisions 对所有 Parser Element 的无正文、可追溯去向决定
 */
public record DocumentCleaningResult(
        List<KnowledgeElement> indexableElements,
        List<KnowledgeElement> metadataOnlyElements,
        Map<String, Integer> reasonCodeCounts,
        String contract,
        List<ElementCleaningDecision> decisions
) {

    /** 防御性复制结果，防止审计信息与实际发布内容在返回后发生漂移。 */
    public DocumentCleaningResult {
        indexableElements = List.copyOf(Objects.requireNonNull(
                indexableElements,
                "indexableElements must not be null"
        ));
        metadataOnlyElements = List.copyOf(Objects.requireNonNull(
                metadataOnlyElements,
                "metadataOnlyElements must not be null"
        ));
        Objects.requireNonNull(reasonCodeCounts, "reasonCodeCounts must not be null");
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        reasonCodeCounts.forEach((reasonCode, count) -> {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
            if (count == null || count < 1) {
                throw new IllegalArgumentException("reasonCode count must be positive");
            }
            counts.put(reasonCode, count);
        });
        reasonCodeCounts = Collections.unmodifiableMap(counts);
        Objects.requireNonNull(contract, "contract must not be null");
        contract = contract.strip();
        if (contract.isEmpty()) {
            throw new IllegalArgumentException("contract must not be blank");
        }
        decisions = List.copyOf(Objects.requireNonNull(
                decisions,
                "decisions must not be null"
        ));
    }

    /**
     * 为只返回两组保留元素的策略物化完整去向；REMOVE 必须使用完整构造显式报告。
     */
    public DocumentCleaningResult(
            List<KnowledgeElement> indexableElements,
            List<KnowledgeElement> metadataOnlyElements,
            Map<String, Integer> reasonCodeCounts,
            String contract
    ) {
        this(
                indexableElements,
                metadataOnlyElements,
                reasonCodeCounts,
                contract,
                retainedDecisions(indexableElements, metadataOnlyElements)
        );
    }

    private static List<ElementCleaningDecision> retainedDecisions(
            List<KnowledgeElement> indexableElements,
            List<KnowledgeElement> metadataOnlyElements
    ) {
        List<ElementCleaningDecision> values = new ArrayList<>();
        Map<UUID, Integer> ordinals = new HashMap<>();
        Objects.requireNonNull(indexableElements, "indexableElements must not be null")
                .forEach(element -> {
                    requireUniqueOrdinal(ordinals, element);
                    values.add(new ElementCleaningDecision(
                            element.id(),
                            element.type(),
                            element.attributes().get("role"),
                            DocumentCleaningConfiguration.Action.KEEP,
                            "KEPT"
                    ));
                });
        Objects.requireNonNull(metadataOnlyElements, "metadataOnlyElements must not be null")
                .forEach(element -> {
                    requireUniqueOrdinal(ordinals, element);
                    values.add(new ElementCleaningDecision(
                            element.id(),
                            element.type(),
                            element.attributes().get("role"),
                            DocumentCleaningConfiguration.Action.METADATA_ONLY,
                            element.attributes().getOrDefault(
                                    DocumentCleaningPolicy.CLEANING_REASON_CODE_ATTRIBUTE,
                                    "POLICY_METADATA_ONLY"
                            )
                    ));
                });
        values.sort(Comparator.comparingInt(element -> ordinals.get(element.elementId())));
        return List.copyOf(values);
    }

    private static void requireUniqueOrdinal(
            Map<UUID, Integer> ordinals,
            KnowledgeElement element
    ) {
        if (ordinals.putIfAbsent(element.id(), element.ordinal()) != null) {
            throw new IllegalArgumentException(
                    "cleaning result contains an element in multiple destinations"
            );
        }
    }
}
