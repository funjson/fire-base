package dev.infinityknowledge.provider.zhipu;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 GLM 上下文窗口与网络重试预算在配置阶段即被约束。 */
class ZhipuGenerationConfigTest {

    @Test
    void rejectsGlm51PromptAndOutputBudgetBeyondItsContextWindow() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config("glm-5.1", 190_000, 16_000, 1, Duration.ZERO)
        );

        assertEquals(
                "prompt and output token budgets exceed the configured model context window",
                failure.getMessage()
        );
    }

    @Test
    void includesAllAttemptsAndBackoffInMaximumLatency() {
        ZhipuGenerationConfig config = config(
                "glm-5.2",
                16_384,
                512,
                3,
                Duration.ofMillis(100)
        );

        assertEquals(Duration.ofMillis(9_300), config.maximumLatency());
    }

    @Test
    void tokenizerLatencyIncludesEveryAttemptAndExponentialBackoff() {
        ZhipuTokenizerConfig tokenizer = new ZhipuTokenizerConfig(
                URI.create("https://example.invalid/tokenizer"),
                "secret-key",
                Duration.ofSeconds(1),
                3,
                Duration.ofMillis(250),
                20_000
        );

        assertEquals(Duration.ofMillis(3_750), tokenizer.maximumLatency());
    }

    private static ZhipuGenerationConfig config(
            String model,
            int maximumPromptTokens,
            int maxOutputTokens,
            int maxAttempts,
            Duration backoff
    ) {
        return new ZhipuGenerationConfig(
                URI.create("https://example.invalid/chat"),
                "secret-key",
                model,
                Duration.ofSeconds(3),
                maxAttempts,
                backoff,
                20_000,
                maximumPromptTokens,
                maxOutputTokens
        );
    }
}
