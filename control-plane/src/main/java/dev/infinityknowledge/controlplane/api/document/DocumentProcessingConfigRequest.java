package dev.infinityknowledge.controlplane.api.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 一套完整、无版本的文档处理配置输入。
 *
 * <p>创建 Space 时该配置被固化为不可变处理契约；TEST_ONLY 请求也复用同一
 * 结构作为本次运行的临时覆盖，避免为测试配置建立第二套业务模型。</p>
 *
 * @param parserSelections 每种规范媒体类型的 Parser 选择
 * @param cleaning Parser 明确识别的文档角色处理方式
 * @param chunker Chunker Provider、Token 预算和专属参数
 */
public record DocumentProcessingConfigRequest(
        @NotEmpty @Size(max = 32) List<@Valid ParserSelection> parserSelections,
        @NotNull @Valid CleaningConfiguration cleaning,
        @NotNull @Valid ChunkerConfiguration chunker
) {

    /** 用户对一种规范媒体类型选择的稳定 Parser。 */
    public record ParserSelection(
            @NotBlank @Size(max = 128) String mediaType,
            @NotBlank @Size(max = 128) String parserId
    ) {
    }

    /** 页面允许选择的确定性内容治理方式。 */
    public record CleaningConfiguration(
            @NotBlank @Size(max = 32) String header,
            @NotBlank @Size(max = 32) String footer,
            @NotBlank @Size(max = 32) String pageNumber,
            @NotBlank @Size(max = 32) String watermark,
            @NotBlank @Size(max = 32) String frontMatter
    ) {
    }

    /**
     * 页面允许调整的 Chunker 参数。长度预算统一由所选 Token Counter 计算；
     * Provider 专属参数由控制面按已安装 Provider 契约规范化为 canonical JSON。
     */
    public record ChunkerConfiguration(
            @NotBlank @Size(max = 64) String providerId,
            @NotBlank @Size(max = 128) String tokenizerId,
            @Min(1) @Max(65_536) int minimumTokens,
            @Min(1) @Max(65_536) int targetTokens,
            @Min(1) @Max(65_536) int maximumTokens,
            @Min(0) @Max(65_535) int overlapTokens,
            @NotNull @Size(max = 16) Map<@NotBlank @Size(max = 128) String, Object>
                    providerConfig
    ) {
    }
}
