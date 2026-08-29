package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;

import java.util.Objects;

/**
 * 定义解析后结构元素进入检索主线前的治理方式。
 *
 * <p>配置只覆盖 Parser 已明确标注的文档角色，不接受正则表达式或脚本，
 * 避免空间级配置误判并删除普通正文。隐藏内容属于安全边界，始终由清洗策略
 * 强制排除，因此不提供可覆盖的配置项。</p>
 *
 * @param header 页眉元素的处理方式
 * @param footer 页脚元素的处理方式
 * @param pageNumber 页码元素的处理方式，包括 Parser 标注的装饰性页码
 * @param watermark 水印元素的处理方式
 * @param frontMatter 文档前置元数据元素的处理方式
 */
public record DocumentCleaningConfiguration(
        Action header,
        Action footer,
        Action pageNumber,
        Action watermark,
        Action frontMatter
) {

    /** 校验所有治理选择均已明确提供。 */
    public DocumentCleaningConfiguration {
        Objects.requireNonNull(header, "header action must not be null");
        Objects.requireNonNull(footer, "footer action must not be null");
        Objects.requireNonNull(pageNumber, "pageNumber action must not be null");
        Objects.requireNonNull(watermark, "watermark action must not be null");
        Objects.requireNonNull(frontMatter, "frontMatter action must not be null");
    }

    /**
     * 返回与当前可索引行为兼容的默认配置。
     *
     * <p>默认保留 Parser 已产出的页眉、页脚等内容，并排除 Markdown
     * Front Matter，以保持引入可配置 Cleaner 前“前置元数据不参与索引”
     * 的既有行为；隐藏内容仍由策略无条件强制排除。</p>
     */
    public static DocumentCleaningConfiguration defaults() {
        return new DocumentCleaningConfiguration(
                Action.KEEP,
                Action.KEEP,
                Action.KEEP,
                Action.KEEP,
                Action.REMOVE
        );
    }

    /**
     * 把空间持久化配置转换为 Cleaner 使用的运行时配置。
     *
     * <p>转换集中在一处，避免摄取主线与索引契约各自维护五个字段的重复映射。</p>
     *
     * @param configuration 一次摄取读取到的空间清洗配置快照
     * @return Cleaner 可直接使用的配置
     */
    public static DocumentCleaningConfiguration from(
            SpaceDocumentProcessingConfigStore.CleaningConfiguration configuration
    ) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return new DocumentCleaningConfiguration(
                action(configuration.header()),
                action(configuration.footer()),
                action(configuration.pageNumber()),
                action(configuration.watermark()),
                action(configuration.frontMatter())
        );
    }

    private static Action action(
            SpaceDocumentProcessingConfigStore.CleaningAction action
    ) {
        return Action.valueOf(Objects.requireNonNull(action, "action must not be null").name());
    }

    /** 表示结构元素在清洗后的业务去向。 */
    public enum Action {
        /** 保留在可索引元素中。 */
        KEEP,
        /** 从可索引结果和仅元数据结果中排除。 */
        REMOVE,
        /** 从可索引结果排除，但保留为治理元数据。 */
        METADATA_ONLY
    }
}
