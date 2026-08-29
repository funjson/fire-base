package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证受控维度以及历史指标 JSON 的有限升级规则。 */
class MetricDimensionsTest {
    private static final String CONFIG = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");

    @Test
    void buildsStrongStageVisitChannelAndSeparateStatusDimensions() {
        MetricDimensions dimensions = MetricDimensions.builder()
                .config(CONFIG)
                .stage(RetrievalObservationStage.RETRIEVAL_BRANCH)
                .visitIndex(2)
                .attempt(3)
                .channel(RetrievalChannel.VECTOR)
                .status(RetrievalObservationStatus.DEGRADED)
                .purpose(RetrievalObservationPurpose.ONLINE)
                .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR)
                .build();

        assertEquals("RETRIEVAL_BRANCH", dimensions.require(MetricDimensions.Key.STAGE));
        assertEquals("2", dimensions.require(MetricDimensions.Key.VISIT_INDEX));
        assertEquals("VECTOR", dimensions.require(MetricDimensions.Key.CHANNEL));
        assertEquals("DEGRADED",
                dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
        assertEquals("DEGRADED", dimensions.require(MetricDimensions.Key.STATUS));
    }

    @Test
    void readsLegacyTechnicalStatusJsonWithoutChangingItsMeaning() {
        MetricDimensions dimensions = readLegacy("SUCCEEDED");

        assertEquals("SUCCEEDED",
                dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
        assertEquals("SUCCEEDED", dimensions.require(MetricDimensions.Key.STATUS));
    }

    @Test
    void readsLegacyBusinessTerminalJsonAsUnknownTechnicalStatus() {
        MetricDimensions dimensions = readLegacy("INSUFFICIENT");

        assertEquals("INSUFFICIENT",
                dimensions.require(MetricDimensions.Key.TERMINAL_STATUS));
        assertEquals(MetricDimensions.UNOBSERVED_TECHNICAL_STATUS,
                dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
        assertEquals(MetricDimensions.UNOBSERVED_TECHNICAL_STATUS,
                dimensions.require(MetricDimensions.Key.STATUS));
    }

    private static MetricDimensions readLegacy(String status) {
        String json = """
                {
                  "values": {
                    "CONFIG": "%s",
                    "STATUS": "%s",
                    "PURPOSE": "ONLINE",
                    "TIME_SLICE": "HOUR:2026-08-26T01:00:00Z"
                  }
                }
                """.formatted(CONFIG, status);
        return JsonMapper.builder().build().readValue(json, MetricDimensions.class);
    }
}
