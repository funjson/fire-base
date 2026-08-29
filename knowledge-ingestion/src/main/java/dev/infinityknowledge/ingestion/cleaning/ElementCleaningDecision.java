package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration.Action;

import java.util.Objects;
import java.util.UUID;

/**
 * Cleaner 对一个 Parser Element 作出的无正文审计决定。
 *
 * <p>决定只保存稳定 ElementId、结构类型、可选角色、去向和原因码。正文仍只存在于
 * Parser 制品；即使去向是 REMOVE，ExtractionEngine 也能借 ElementId 关联来源范围，
 * 而不会为了审计再复制已排除内容。</p>
 *
 * @param elementId Parser 创建的元素标识
 * @param elementType 不含正文的结构类型
 * @param role 可选的 Parser 业务角色
 * @param action 清洗后的明确去向
 * @param reasonCode 稳定、非敏感的决定原因码
 */
public record ElementCleaningDecision(
        UUID elementId,
        ElementType elementType,
        String role,
        Action action,
        String reasonCode
) {

    /** 拒绝缺失身份、去向或不可观测原因。 */
    public ElementCleaningDecision {
        Objects.requireNonNull(elementId, "elementId must not be null");
        Objects.requireNonNull(elementType, "elementType must not be null");
        if (role != null) {
            role = role.strip();
            if (role.isEmpty() || role.length() > 128) {
                throw new IllegalArgumentException("role is blank or too long");
            }
        }
        Objects.requireNonNull(action, "cleaning action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        reasonCode = reasonCode.strip();
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable uppercase reason code"
            );
        }
    }
}
