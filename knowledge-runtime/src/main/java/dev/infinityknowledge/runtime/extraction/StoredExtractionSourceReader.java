package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 从 OSS 加载并验证一个任务 Item 的不可变来源。
 *
 * <p>测试抽取与正式摄取共用这条完整性边界。每次只读取一个受预算约束的文件，
 * 不聚合批次字节，也不把对象存储响应、物理地址或正文写入异常。</p>
 */
public final class StoredExtractionSourceReader {

    private final ObjectStorage objectStorage;
    private final DocumentParseLimits limits;

    /** 创建来源读取边界。 */
    public StoredExtractionSourceReader(
            ObjectStorage objectStorage,
            DocumentParseLimits limits
    ) {
        this.objectStorage = Objects.requireNonNull(
                objectStorage,
                "objectStorage must not be null"
        );
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /** 读取一个来源并校验目录元数据、长度与 SHA-256。 */
    public byte[] read(RunItem item) {
        Objects.requireNonNull(item, "item must not be null");
        var asset = item.sourceAsset();
        if (asset.contentLength() > limits.maximumSourceBytes()) {
            throw new SourceReadException("SOURCE_TOO_LARGE");
        }
        ObjectAddress address = new ObjectAddress(
                asset.tenantId(),
                asset.spaceId(),
                asset.objectId()
        );
        var stored = objectStorage.get(address)
                .orElseThrow(() -> new SourceReadException("SOURCE_NOT_FOUND"));
        try (stored; InputStream input = stored.content()) {
            if (stored.metadata().contentLength() != asset.contentLength()
                    || !stored.metadata().checksumSha256().equals(asset.checksumSha256())) {
                throw new SourceReadException("SOURCE_METADATA_MISMATCH");
            }
            byte[] source = input.readNBytes(limits.maximumSourceBytes() + 1);
            if (source.length > limits.maximumSourceBytes()) {
                throw new SourceReadException("SOURCE_TOO_LARGE");
            }
            if (source.length != asset.contentLength()) {
                throw new SourceReadException("SOURCE_LENGTH_MISMATCH");
            }
            if (!IngestionIdentity.sha256(source).equals(asset.checksumSha256())) {
                throw new SourceReadException("SOURCE_CHECKSUM_MISMATCH");
            }
            return source;
        } catch (IOException failure) {
            throw new SourceReadException("SOURCE_READ_FAILED", failure);
        }
    }

    /** 只暴露稳定错误码的来源读取失败。 */
    public static final class SourceReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private SourceReadException(String code) {
            super(code);
            this.code = code;
        }

        private SourceReadException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        /** 返回可持久化且不含 Provider 内容的稳定错误码。 */
        public String code() {
            return code;
        }
    }
}
