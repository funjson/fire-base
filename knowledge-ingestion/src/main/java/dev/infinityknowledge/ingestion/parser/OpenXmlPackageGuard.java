package dev.infinityknowledge.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 在具体 Parser 分配对象模型或调用外部服务前，对 OpenXML ZIP 容器执行有界安全预检。
 *
 * <p>DOCX 等 OpenXML 文件采用 ZIP 封装；本类型只校验文档内部容器，
 * 不表示系统支持上传普通 ZIP，也不负责把一个归档包拆成多份知识文档。</p>
 */
public final class OpenXmlPackageGuard {

    private OpenXmlPackageGuard() {
    }

    /**
     * 拒绝 OpenXML 容器中的路径穿越、条目过多、过量展开和已知压缩炸弹模式。
     *
     * @param source 待解析的完整 OpenXML 源文件
     * @param limits 本次摄取统一使用的资源上限
     */
    public static void verify(byte[] source, DocumentParseLimits limits) {
        int entries = 0;
        long totalExpanded = 0;
        byte[] buffer = new byte[8_192];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maximumArchiveEntries()) {
                    throw new DocumentParseException("Office archive exceeds maximumArchiveEntries");
                }
                verifyName(entry.getName());
                long entryExpanded = 0;
                int read;
                while ((read = archive.read(buffer)) >= 0) {
                    entryExpanded += read;
                    totalExpanded += read;
                    if (entryExpanded > limits.maximumExpandedBytes()
                            || totalExpanded > limits.maximumExpandedBytes()) {
                        throw new DocumentParseException("Office archive exceeds maximumExpandedBytes");
                    }
                }
                archive.closeEntry();
                long compressedSize = entry.getCompressedSize();
                if (compressedSize > 0 && entryExpanded > 0
                        && ((double) entryExpanded / compressedSize)
                        > limits.maximumCompressionRatio()) {
                    throw new DocumentParseException("Office archive exceeds maximumCompressionRatio");
                }
            }
        } catch (DocumentParseException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DocumentParseException("failed to inspect Office archive", failure);
        }
        if (entries == 0) {
            throw new DocumentParseException("Office source is not a ZIP archive");
        }
    }

    private static void verifyName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("(?:^|.*/)\\.\\.(?:/.*|$)")) {
            throw new DocumentParseException("Office archive contains an unsafe entry path");
        }
    }
}
