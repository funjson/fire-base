package dev.infinityknowledge.agent.client;

/** 为每次知识查询提供当前调用主体的短期访问令牌。 */
@FunctionalInterface
public interface AccessTokenProvider {

    /**
     * 返回不带 {@code Bearer } 前缀的 OAuth2 访问令牌。
     *
     * @return 当前有效访问令牌
     */
    String accessToken();
}
