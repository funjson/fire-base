package dev.infinityknowledge.parser.docling;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 固定一次部署可接受的 Docling lossless JSON 契约。
 *
 * <p>契约使用 {@code schemaName@schemaVersion} 表示，并参与 Parser 版本指纹。
 * 服务升级若改变 JSON Schema，运维必须显式更新该值并重新处理文档，不能把新旧
 * 结构静默写入同一处理版本。</p>
 */
public final class DoclingServerContract {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final String schemaName;
    private final String schemaVersion;

    private DoclingServerContract(String schemaName, String schemaVersion) {
        this.schemaName = schemaName;
        this.schemaVersion = schemaVersion;
    }

    /** 解析部署配置，并拒绝不能同时约束 Schema 名称和版本的模糊值。 */
    public static DoclingServerContract parse(String value) {
        String normalized = Objects.requireNonNull(
                value,
                "Docling serverContract must not be null"
        ).strip();
        int separator = normalized.indexOf('@');
        if (separator < 1 || separator != normalized.lastIndexOf('@')) {
            throw new IllegalArgumentException(
                    "Docling serverContract must use schemaName@schemaVersion"
            );
        }
        String schemaName = normalized.substring(0, separator).strip();
        String schemaVersion = normalized.substring(separator + 1).strip();
        if (!PART.matcher(schemaName).matches() || !PART.matcher(schemaVersion).matches()) {
            throw new IllegalArgumentException("Docling serverContract contains an invalid part");
        }
        return new DoclingServerContract(schemaName, schemaVersion);
    }

    /** lossless JSON 的固定 Schema 名称。 */
    public String schemaName() {
        return schemaName;
    }

    /** lossless JSON 的固定 Schema 版本。 */
    public String schemaVersion() {
        return schemaVersion;
    }

    /** 返回可写入处理指纹的规范文本。 */
    public String value() {
        return schemaName + '@' + schemaVersion;
    }
}
