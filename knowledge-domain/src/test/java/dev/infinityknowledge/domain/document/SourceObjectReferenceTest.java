package dev.infinityknowledge.domain.document;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceObjectReferenceTest {

    @Test
    void retainsOpaqueStorageAndIntegrityMetadata() {
        SourceObjectReference reference = new SourceObjectReference(
                UUID.randomUUID(),
                "minio://knowledge-source/opaque-key",
                "架构设计.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                128,
                "A".repeat(64),
                Instant.parse("2026-08-11T00:00:00Z")
        );

        assertEquals("a".repeat(64), reference.checksumSha256());
        assertEquals("架构设计.docx", reference.originalFileName());
    }

    @Test
    void rejectsInvalidDigest() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new SourceObjectReference(
                UUID.randomUUID(),
                "object-1",
                "source.txt",
                "text/plain",
                1,
                "not-a-digest",
                Instant.now()
        ));
        assertTrue(failure.getMessage().contains("64 hexadecimal"));
    }
}
