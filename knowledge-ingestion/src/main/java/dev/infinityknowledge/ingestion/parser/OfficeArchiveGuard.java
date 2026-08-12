package dev.infinityknowledge.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Performs bounded archive preflight before an Office parser allocates its object model. */
final class OfficeArchiveGuard {

    private OfficeArchiveGuard() {
    }

    /** Rejects path traversal, excessive entry counts, expansion and known compression bombs. */
    static void verify(byte[] source, DocumentParseLimits limits) {
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
