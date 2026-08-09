package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 空间 ACL 的精确授权描述。
 */
public record SpaceAclRequest(
        @NotBlank @Size(max = 32) String subjectType,
        @NotBlank @Size(max = 128) String subjectId,
        @NotBlank @Size(max = 32) String permission
) {
}
