package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.FileIngestionService;
import dev.infinityknowledge.controlplane.application.OriginalSourceService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Multipart ingestion and authorized original-source access APIs. */
@RestController
@RequestMapping("/api/v1/documents")
public final class DocumentSourceController {

    private final JwtPrincipalContextFactory principalFactory;
    private final FileIngestionService ingestionService;
    private final OriginalSourceService sourceService;

    /** Creates the document source API. */
    public DocumentSourceController(
            JwtPrincipalContextFactory principalFactory,
            FileIngestionService ingestionService,
            OriginalSourceService sourceService
    ) {
        this.principalFactory = principalFactory;
        this.ingestionService = ingestionService;
        this.sourceService = sourceService;
    }

    /** Retains, parses and publishes a supported rich document. */
    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileDocumentResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("spaceId") String spaceId,
            @RequestParam("externalId") String externalId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "language", defaultValue = "zh-CN") String language,
            @RequestParam(value = "authority", defaultValue = "80") int authority,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ingestionService.ingest(
                principalFactory.create(jwt),
                file,
                spaceId,
                externalId,
                title,
                language,
                authority
        );
    }

    /** Returns authorized original-source metadata without exposing object-store identifiers. */
    @GetMapping("/{documentId}/source")
    public SourceObjectResponse metadata(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return SourceObjectResponse.from(
                sourceService.metadata(principalFactory.create(jwt), documentId)
        );
    }

    /** Streams an authorized source as a download or sandboxed safe inline preview. */
    @GetMapping("/{documentId}/source/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable UUID documentId,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var download = sourceService.open(principalFactory.create(jwt), documentId);
        var source = download.source().sourceObject();
        boolean safeInline = inline && OriginalSourceServiceMediaTypes.previewable(source.mediaType());
        try {
            ContentDisposition disposition = (safeInline
                    ? ContentDisposition.inline()
                    : ContentDisposition.attachment())
                    .filename(source.originalFileName(), StandardCharsets.UTF_8)
                    .build();
            var response = ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.parseMediaType(source.mediaType()))
                    .contentLength(source.contentLength());
            if (safeInline) {
                response.header("Content-Security-Policy", "sandbox");
            }
            return response.body(new InputStreamResource(download.stored().content()));
        } catch (RuntimeException failure) {
            try {
                download.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
