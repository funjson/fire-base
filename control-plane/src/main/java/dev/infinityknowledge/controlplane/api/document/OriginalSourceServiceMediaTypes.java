package dev.infinityknowledge.controlplane.api.document;

import java.util.Set;

/** Shared safe-inline media-type policy for source metadata and content responses. */
final class OriginalSourceServiceMediaTypes {

    private static final Set<String> SAFE_INLINE = Set.of(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    private OriginalSourceServiceMediaTypes() {
    }

    /** Returns whether browsers may render the type in a sandboxed response. */
    static boolean previewable(String mediaType) {
        return SAFE_INLINE.contains(mediaType.toLowerCase(java.util.Locale.ROOT));
    }
}
