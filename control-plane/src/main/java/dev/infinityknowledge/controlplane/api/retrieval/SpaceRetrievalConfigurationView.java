package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;

import java.time.Instant;
import java.util.Objects;

/** Space 检索配置的不可变修订视图。 */
public record SpaceRetrievalConfigurationView(
        String spaceId,
        long revision,
        String fingerprint,
        RetrievalConfigurationDto configuration,
        String createdBy,
        Instant createdAt
) {

    /** 将领域修订映射为不暴露持久化结构的 HTTP 响应。 */
    public static SpaceRetrievalConfigurationView from(
            SpaceRetrievalConfiguration value
    ) {
        Objects.requireNonNull(value, "value must not be null");
        return new SpaceRetrievalConfigurationView(
                value.spaceId().value(),
                value.revision(),
                value.fingerprint(),
                RetrievalConfigurationDto.from(value.configuration()),
                value.createdBy().value(),
                value.createdAt()
        );
    }
}
