package dev.infinityknowledge.spi.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证厂商无关 Prompt Token 计数合同拒绝未知计数和无效消息。 */
class ModelTokenEstimateTest {

    @Test
    void requiresPositiveTokenCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelTokenEstimate(0, true, "test-v1")
        );
    }

    @Test
    void defensivelyCopiesCompleteMessages() {
        ModelMessage user = new ModelMessage(ModelMessage.Role.USER, "query");
        List<ModelMessage> messages = new ArrayList<>(List.of(user));
        ModelTokenEstimateRequest request = new ModelTokenEstimateRequest(
                "zhipu",
                "glm-5.2",
                messages
        );
        messages.clear();

        assertEquals(List.of(user), request.messages());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.messages().add(user)
        );
    }
}
