package dev.infinityknowledge.controlplane.application.ingestion;

/**
 * 表示活动 Space 缺少创建时必须物化的不可变文档处理配置。
 *
 * <p>这通常来自旧版本遗留数据。读取接口不能用当前部署默认值静默回填，否则同一
 * Space 的历史文档会混用不同处理语义；管理员应使用目标配置重新创建 Space。</p>
 */
public final class SpaceDocumentProcessingConfigMissingException
        extends RuntimeException {

    public static final String CODE = "SPACE_PROCESSING_CONFIG_MISSING";
    public static final String USER_MESSAGE =
            "当前知识空间缺少创建时物化的文档处理配置，请使用目标配置重新创建知识空间。";

    /** 创建不携带内部数据库细节的稳定业务异常。 */
    public SpaceDocumentProcessingConfigMissingException() {
        super(USER_MESSAGE);
    }
}
