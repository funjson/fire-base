package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 负责把上传流转换为经过边界校验的不可变摄入源。
 *
 * <p>文件只读取一次，并在同一次读取中计算 SHA-256。后续对象存储和 Parser
 * 使用同一份字节，避免原件指纹与解析输入发生偏差。</p>
 */
@Component
public final class UploadedSourceReader {

    private final int maximumSourceBytes;
    private final DocumentParserRegistry parsers;

    /**
     * 按文件摄入配置建立有界读取器。
     *
     * @param properties 文件大小和解析资源预算
     * @param parsers Parser 格式注册表
     */
    public UploadedSourceReader(
            FileIngestionProperties properties,
            DocumentParserRegistry parsers
    ) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.maximumSourceBytes = properties.maximumSourceBytes();
        this.parsers = Objects.requireNonNull(parsers, "parsers must not be null");
    }

    /**
     * 校验文件名和媒体类型，并完成单次有界读取与摘要计算。
     *
     * @param file Multipart 上传文件
     * @return 可供对象存储和 Parser 共同使用的字节快照
     */
    public BufferedUpload read(MultipartFile file) {
        Objects.requireNonNull(file, "file must not be null");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String mediaType = parsers.resolveCanonicalMediaType(
                file.getContentType(),
                fileName
        );
        long declaredLength = file.getSize();
        if (declaredLength > maximumSourceBytes) {
            throw new SourceSizeLimitExceededException();
        }
        return readBytes(file, fileName, mediaType, declaredLength);
    }

    private BufferedUpload readBytes(
            MultipartFile file,
            String fileName,
            String mediaType,
            long declaredLength
    ) {
        try (InputStream input = file.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     declaredLength > 0
                             ? (int) Math.min(declaredLength, maximumSourceBytes)
                             : 8_192
             )) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumSourceBytes) {
                    throw new SourceSizeLimitExceededException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            return new BufferedUpload(
                    fileName,
                    mediaType,
                    output.toByteArray(),
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to read uploaded file", failure);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String safeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("original file name is required");
        }
        String name = rawName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || name.length() > 512) {
            throw new IllegalArgumentException("original file name is invalid");
        }
        return name;
    }

    /**
     * 一次上传的受限字节快照。
     *
     * @param fileName 去除路径信息后的安全文件名
     * @param mediaType 规范化媒体类型
     * @param bytes 原始文件字节
     * @param checksumSha256 小写十六进制 SHA-256
     */
    public record BufferedUpload(
            String fileName,
            String mediaType,
            byte[] bytes,
            String checksumSha256
    ) {

        /**
         * 防止调用方传入空值；该对象只在一次同步摄入调用内流转。
         */
        public BufferedUpload {
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
        }
    }
}
