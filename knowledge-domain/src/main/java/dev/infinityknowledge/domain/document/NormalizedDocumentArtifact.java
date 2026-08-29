package dev.infinityknowledge.domain.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Parser 产生的规范化文本制品，也是 Element、Chunk 与验收标签共用的坐标空间。
 *
 * <p>二进制原件不能使用字节偏移表达文本范围，Markdown 语法文本与富文档阅读顺序
 * 也不是同一种坐标。因而来源引用统一绑定本制品的 UTF-16 半开区间，而原件仍通过
 * 摄取请求的 Source SHA-256 单独识别。制品正文只在抽取链路内部流转，不得写入日志、
 * Trace 或错误消息。</p>
 *
 * @param id 由合同和正文哈希确定的稳定制品标识
 * @param contract 规范化算法与分隔规则合同
 * @param text 供 Element 范围定位的完整规范化文本
 * @param sha256 规范化文本 UTF-8 字节的 SHA-256
 */
public record NormalizedDocumentArtifact(
        UUID id,
        String contract,
        String text,
        String sha256
) {

    /** 校验标识、合同和正文指纹一致，避免坐标落到另一份制品。 */
    public NormalizedDocumentArtifact {
        Objects.requireNonNull(id, "artifact id must not be null");
        contract = required(contract, "artifact contract");
        text = Objects.requireNonNull(text, "artifact text must not be null");
        sha256 = requiredSha256(sha256);
        String actualHash = sha256(text);
        if (!actualHash.equals(sha256)) {
            throw new IllegalArgumentException("artifact sha256 does not match artifact text");
        }
        UUID expectedId = identifier(contract, sha256);
        if (!expectedId.equals(id)) {
            throw new IllegalArgumentException("artifact id does not match contract and sha256");
        }
    }

    /** 从规范化合同与正文创建稳定制品。 */
    public static NormalizedDocumentArtifact create(String contract, String text) {
        String normalizedContract = required(contract, "artifact contract");
        String normalizedText = Objects.requireNonNull(text, "artifact text must not be null");
        String hash = sha256(normalizedText);
        return new NormalizedDocumentArtifact(
                identifier(normalizedContract, hash),
                normalizedContract,
                normalizedText,
                hash
        );
    }

    private static UUID identifier(String contract, String hash) {
        return UUID.nameUUIDFromBytes(
                (contract + '\u001f' + hash).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    private static String requiredSha256(String value) {
        String normalized = required(value, "artifact sha256");
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be lowercase hexadecimal");
        }
        return normalized;
    }
}
