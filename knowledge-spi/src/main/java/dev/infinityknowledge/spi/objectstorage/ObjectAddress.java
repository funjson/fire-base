package dev.infinityknowledge.spi.objectstorage;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;

/**
 * Logical address of one original source object.
 *
 * <p>Adapters derive their physical key from all three fields and must never use a
 * user-supplied file name as a path.</p>
 *
 * @param tenantId owning tenant
 * @param spaceId owning knowledge space
 * @param objectId opaque identifier unique within the space
 */
public record ObjectAddress(
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        String objectId
) {

    /** Validates the complete tenant-scoped address. */
    public ObjectAddress {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        objectId = DomainChecks.requiredText(objectId, "objectId", 128);
        if (!objectId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("objectId contains unsafe characters");
        }
    }
}
