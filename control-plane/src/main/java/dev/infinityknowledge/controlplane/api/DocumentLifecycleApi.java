package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/** Stable HTTP contract for reversible document lifecycle operations. */
public final class DocumentLifecycleApi {
    private DocumentLifecycleApi() {
    }

    public record Request(
            @NotNull @Pattern(regexp = "ACTIVE|ARCHIVED|DELETED") String status,
            @Min(0) long expectedVersion
    ) {
    }

    public record Response(
            UUID documentId,
            String spaceId,
            String status,
            long version,
            Instant updatedAt,
            boolean changed
    ) {
    }
}
