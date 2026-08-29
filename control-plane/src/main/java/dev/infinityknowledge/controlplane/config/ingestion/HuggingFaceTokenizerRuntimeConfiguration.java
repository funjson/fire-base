package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.tokenizer.huggingface.HuggingFaceTokenCounter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按部署配置安装本地 HuggingFace TokenCounter。
 *
 * <p>Bean 创建阶段即校验文件 SHA-256；不匹配时拒绝启动，禁止静默退回字节预算。</p>
 */
@Configuration(proxyBeanMethods = false)
public class HuggingFaceTokenizerRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.ingestion.tokenizers.huggingface",
            name = "enabled",
            havingValue = "true"
    )
    TokenCounter huggingFaceTokenCounter(HuggingFaceTokenizerProperties properties) {
        return HuggingFaceTokenCounter.open(
                properties.id(),
                properties.modelProfileId(),
                properties.tokenizerJson(),
                properties.sha256(),
                properties.addSpecialTokens()
        );
    }
}
