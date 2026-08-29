package dev.infinityknowledge.spi.model;

import java.util.Objects;

/**
 * 厂商无关的模型消息；只表达角色和正文，不携带凭据、端点或厂商请求对象。
 *
 * <p>正文会原样参与 Token 计数，因此这里只校验非空，不做 trim 或规范化。</p>
 *
 * @param role 消息角色
 * @param content 实际发送给模型的消息正文
 */
public record ModelMessage(Role role, String content) {

    /** 保留当前结构化生成链实际使用的三种标准消息角色。 */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        /** 返回外部聊天协议使用的稳定小写角色名。 */
        public String wireName() {
            return wireName;
        }
    }

    /** 保证消息可被 Provider 安全序列化，同时保留原始空白语义。 */
    public ModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
