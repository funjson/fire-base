package dev.infinityknowledge.ingestion.parser;

import java.util.List;
import java.util.Objects;

/**
 * 配置页面可展示的 Parser 能力。
 *
 * @param parserId 稳定实现标识
 * @param parserVersion 处理契约版本
 * @param canonicalMediaType 规范媒体类型
 * @param supportedMediaTypes 支持的媒体类型和别名
 * @param supportedExtensions 支持的文件扩展名
 * @param outputCapabilities Adapter 保证提供的结构和来源定位能力
 * @param defaultSelection 当前部署是否把它作为该规范格式的默认实现
 */
public record ParserCapability(
        String parserId,
        String parserVersion,
        String canonicalMediaType,
        List<String> supportedMediaTypes,
        List<String> supportedExtensions,
        List<ParserOutputCapability> outputCapabilities,
        boolean defaultSelection
) {

    /** 防止能力描述被调用方修改。 */
    public ParserCapability {
        Objects.requireNonNull(parserId, "parserId must not be null");
        Objects.requireNonNull(parserVersion, "parserVersion must not be null");
        Objects.requireNonNull(canonicalMediaType, "canonicalMediaType must not be null");
        supportedMediaTypes = List.copyOf(Objects.requireNonNull(
                supportedMediaTypes,
                "supportedMediaTypes must not be null"
        ));
        supportedExtensions = List.copyOf(Objects.requireNonNull(
                supportedExtensions,
                "supportedExtensions must not be null"
        ));
        outputCapabilities = List.copyOf(Objects.requireNonNull(
                outputCapabilities,
                "outputCapabilities must not be null"
        ));
    }
}
