package dev.infinityknowledge.ingestion.parser;

/**
 * 描述一次 Parser 选择解析出的规范格式。
 *
 * @param mediaType 进入修订指纹的规范媒体类型
 * @param parserId Parser 实现标识
 * @param parserVersion Parser 契约版本
 */
public record DocumentFormat(
        String mediaType,
        String parserId,
        String parserVersion
) {
}
