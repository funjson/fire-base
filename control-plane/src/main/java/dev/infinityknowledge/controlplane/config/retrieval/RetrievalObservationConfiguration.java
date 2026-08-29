package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.retrieval.observation.HmacSha256TextFingerprinter;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 使用进程内 Spring Event 发布逐层检索事实。
 *
 * <p>Gateway 只依赖技术中立端口；后续切换 MQ 时可提供另一 Publisher Bean，
 * 无需修改检索业务链。</p>
 */
@Configuration
public class RetrievalObservationConfiguration {

    /** 注册使用外部密钥和显式版本的 HMAC 查询指纹器。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalTextFingerprinter.class)
    RetrievalTextFingerprinter retrievalTextFingerprinter(
            RetrievalObservationFingerprintProperties properties
    ) {
        return new HmacSha256TextFingerprinter(
                properties.secret(),
                properties.keyVersion()
        );
    }

    /** 注册不改变检索结果的进程内观测发布适配器。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalObservationPublisher.class)
    RetrievalObservationPublisher retrievalObservationPublisher(
            ApplicationEventPublisher events
    ) {
        return events::publishEvent;
    }
}
