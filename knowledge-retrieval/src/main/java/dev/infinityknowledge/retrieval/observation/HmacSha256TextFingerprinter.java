package dev.infinityknowledge.retrieval.observation;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

/** 使用可外部轮换密钥的 HMAC-SHA256 生成检索文本观测指纹。 */
public final class HmacSha256TextFingerprinter implements RetrievalTextFingerprinter {
    private static final String JCA_ALGORITHM = "HmacSHA256";
    private static final String OBSERVATION_ALGORITHM = "HMAC_SHA256";
    private final byte[] secret;
    private final String keyVersion;

    /**
     * 创建指纹器；密钥只保存在内存中，不得写入日志、Trace 或观测事件。
     *
     * @param secret 至少 32 字节的外部密钥
     * @param keyVersion 不含密钥内容的轮换版本
     */
    public HmacSha256TextFingerprinter(String secret, String keyVersion) {
        Objects.requireNonNull(secret, "secret must not be null");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32 || this.secret.length > 4_096) {
            throw new IllegalArgumentException(
                    "retrieval fingerprint secret must contain between 32 and 4096 UTF-8 bytes"
            );
        }
        this.keyVersion = DomainChecks.requiredText(keyVersion, "keyVersion", 64);
        if (!this.keyVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "keyVersion contains unsupported characters"
            );
        }
    }

    /** 每次调用创建独立 Mac，保证并发执行没有共享可变加密状态。 */
    @Override
    public RetrievalObservationPayload.TextFingerprint fingerprint(String text) {
        Objects.requireNonNull(text, "text must not be null");
        try {
            Mac mac = Mac.getInstance(JCA_ALGORITHM);
            mac.init(new SecretKeySpec(secret, JCA_ALGORITHM));
            return new RetrievalObservationPayload.TextFingerprint(
                    OBSERVATION_ALGORITHM,
                    keyVersion,
                    HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)))
            );
        } catch (GeneralSecurityException unavailableAlgorithm) {
            throw new IllegalStateException(
                    "HMAC-SHA256 is unavailable in the current Java runtime",
                    unavailableAlgorithm
            );
        }
    }
}
