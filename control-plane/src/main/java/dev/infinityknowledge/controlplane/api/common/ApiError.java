package dev.infinityknowledge.controlplane.api.common;

import java.time.Instant;

/**
 * 定义不泄露内部异常和知识正文的稳定 API 错误。
 *
 * @param code 稳定错误码
 * @param message 安全错误摘要
 * @param requestId 可用于日志检索的请求标识
 * @param timestamp UTC 时间
 */
public record ApiError(
        String code,
        String message,
        String requestId,
        Instant timestamp
) {
}
