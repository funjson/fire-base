package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.document.OriginalSourceService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 经授权的文档原始文件元数据与内容访问 API。 */
@RestController
@RequestMapping("/api/v1/documents")
public final class DocumentSourceController {

    private final JwtPrincipalContextFactory principalFactory;
    private final OriginalSourceService sourceService;

    /**
     * 创建文档原件访问 API。
     *
     * @param principalFactory 把已验证 JWT 转换为知识系统授权主体的工厂
     * @param sourceService 原文件查询与下载服务
     */
    public DocumentSourceController(
            JwtPrincipalContextFactory principalFactory,
            OriginalSourceService sourceService
    ) {
        this.principalFactory = principalFactory;
        this.sourceService = sourceService;
    }

    /** 返回经授权的原始源元数据，而不公开对象存储标识符。 */
    @GetMapping("/{documentId}/source")
    public SourceObjectResponse metadata(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return SourceObjectResponse.from(
                sourceService.metadata(principalFactory.create(jwt), documentId)
        );
    }

    /** 以下载或沙盒隔离的安全内联预览形式，流式传输来自已授权源的内容。 */
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
