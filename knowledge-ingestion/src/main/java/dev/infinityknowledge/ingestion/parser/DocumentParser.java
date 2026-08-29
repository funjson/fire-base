package dev.infinityknowledge.ingestion.parser;

import java.util.Set;

/**
 * 把具体文档解析实现接入统一摄取主线的 Adapter SPI。
 *
 * <p>实现负责第三方协议、SDK 和响应校验，对内只返回 {@link ParsedDocument}；
 * 不得让厂商对象逃逸到领域模型。实现标识、版本、支持格式和输出能力都必须稳定，
 * 因为注册表会将它们写入处理契约。</p>
 */
public interface DocumentParser {

    /** 用于摄取诊断的稳定实现标识。 */
    String id();

    /** 进入不可变修订身份的 Parser 契约版本。 */
    String version();

    /** 返回写入修订指纹和原件目录的唯一规范媒体类型。 */
    String canonicalMediaType();

    /** 该 Parser 支持的全部小写媒体类型及别名。 */
    Set<String> supportedMediaTypes();

    /** 作为回退选择依据的小写扩展名，必须包含前导点。 */
    Set<String> supportedExtensions();

    /**
     * 返回每次成功解析都能保证提供的输出能力。
     *
     * <p>所有 Adapter 默认且必须提供标准元素。只有需要层级、页码或原生产物等额外
     * 保证的下游能力，才依赖显式声明。</p>
     */
    default Set<ParserOutputCapability> outputCapabilities() {
        return Set.of(ParserOutputCapability.STANDARD_ELEMENTS);
    }

    /** 把一个已经受大小限制的来源解析为有序结构元素。 */
    ParsedDocument parse(DocumentParseInput input);
}
