package dev.infinityknowledge.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动 Infinity Knowledge HTTP 控制面和运行时依赖。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InfinityKnowledgeApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(InfinityKnowledgeApplication.class, args);
    }
}
