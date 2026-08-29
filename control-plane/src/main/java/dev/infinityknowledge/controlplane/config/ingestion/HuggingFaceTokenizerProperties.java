package dev.infinityknowledge.controlplane.config.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 本地 HuggingFace Tokenizer 的部署级配置。
 *
 * <p>文件路径和指纹由运维控制，不允许 Space 管理员提交任意路径或远程模型地址。</p>
 */
@ConfigurationProperties("infinity.knowledge.ingestion.tokenizers.huggingface")
public record HuggingFaceTokenizerProperties(
        boolean enabled,
        String id,
        String modelProfileId,
        Path tokenizerJson,
        String sha256,
        boolean addSpecialTokens
) {
    private static final Pattern STABLE_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern PROFILE_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._/@-]{0,255}"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    public HuggingFaceTokenizerProperties {
        id = normalize(id);
        modelProfileId = normalize(modelProfileId);
        sha256 = normalize(sha256);
        if (enabled) {
            if (!STABLE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("HuggingFace tokenizer id has invalid format");
            }
            if (!PROFILE_ID.matcher(modelProfileId).matches()) {
                throw new IllegalArgumentException(
                        "HuggingFace tokenizer modelProfileId has invalid format"
                );
            }
            Objects.requireNonNull(tokenizerJson, "tokenizerJson must not be null when enabled");
            if (!SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException(
                        "HuggingFace tokenizer sha256 must contain 64 hex characters"
                );
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
