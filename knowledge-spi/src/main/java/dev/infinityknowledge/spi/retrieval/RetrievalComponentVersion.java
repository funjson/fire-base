package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 描述一次检索中实际可执行组件的稳定合同，不包含端点、凭据或 Prompt 正文。
 *
 * <p>该对象由组件安装端提供，并与具体执行实例绑定；Runtime 只能从绑定关系中
 * 生成观测版本，不能把请求里的任意字符串直接当作已执行组件。</p>
 *
 * @param component 稳定组件角色
 * @param provider Provider 或运行时实现标识
 * @param model 模型、算法或后端实现标识
 * @param version Prompt、模板或适配器合同版本
 */
public record RetrievalComponentVersion(
        String component,
        String provider,
        String model,
        String version
) {
    /** 规范化安全标识，禁止动态正文进入指标维度。 */
    public RetrievalComponentVersion {
        component = identifier(component, "component", 64);
        provider = identifier(provider, "provider", 64);
        model = identifier(model, "model", 128);
        version = identifier(version, "version", 128);
    }

    private static String identifier(String value, String name, int maximumLength) {
        String normalized = DomainChecks.requiredText(value, name, maximumLength);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " contains unsafe characters");
        }
        return normalized;
    }
}
