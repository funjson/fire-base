package dev.infinityknowledge.store.minio;

import dev.infinityknowledge.spi.objectstorage.ObjectAddress;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Derives a tenant-isolated physical object key without trusting path fragments. */
final class MinioObjectKey {

    private MinioObjectKey() {
    }

    /** Returns a deterministic path whose user-controlled scope segments are encoded. */
    static String from(ObjectAddress address) {
        return "tenants/" + segment(address.tenantId().value())
                + "/spaces/" + segment(address.spaceId().value())
                + "/objects/" + address.objectId();
    }

    private static String segment(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
