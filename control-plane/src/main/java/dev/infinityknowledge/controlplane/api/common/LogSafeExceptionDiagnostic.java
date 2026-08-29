package dev.infinityknowledge.controlplane.api.common;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * 生成不包含异常消息和业务数据的运维诊断堆栈。
 *
 * <p>未知异常的消息可能携带 SQL 参数、用户查询、文档正文或第三方原始响应，
 * 因此不能直接把 {@link Throwable} 交给日志框架。本类型只保留异常类型、代码位置
 * 和 JDBC 的稳定错误码，使日志仍可定位失败路径，同时守住敏感信息边界。</p>
 */
final class LogSafeExceptionDiagnostic {
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_FRAMES_PER_CAUSE = 32;

    private LogSafeExceptionDiagnostic() {
    }

    /**
     * 把异常因果链格式化为有界且不含异常消息的诊断文本。
     *
     * @param failure 未分类异常
     * @return 仅由类型、稳定技术码和代码位置组成的诊断文本
     */
    static String format(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        StringBuilder diagnostic = new StringBuilder(1024);
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = failure;
        int depth = 0;
        while (current != null
                && depth < MAX_CAUSE_DEPTH
                && visited.add(current)) {
            if (depth > 0) {
                diagnostic.append(System.lineSeparator()).append("Caused by: ");
            }
            appendThrowable(diagnostic, current);
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            diagnostic.append(System.lineSeparator())
                    .append("Cause chain omitted after ")
                    .append(MAX_CAUSE_DEPTH)
                    .append(" levels");
        }
        return diagnostic.toString();
    }

    private static void appendThrowable(StringBuilder target, Throwable failure) {
        target.append(failure.getClass().getName());
        if (failure instanceof SQLException sqlFailure) {
            target.append(" [sqlState=")
                    .append(safeSqlState(sqlFailure.getSQLState()))
                    .append(", vendorCode=")
                    .append(sqlFailure.getErrorCode())
                    .append(']');
        }
        StackTraceElement[] frames = failure.getStackTrace();
        int includedFrames = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
        for (int index = 0; index < includedFrames; index++) {
            target.append(System.lineSeparator())
                    .append("\tat ")
                    .append(frames[index]);
        }
        if (frames.length > includedFrames) {
            target.append(System.lineSeparator())
                    .append("\t... ")
                    .append(frames.length - includedFrames)
                    .append(" frames omitted");
        }
    }

    /** SQLState 只能是五位字母数字码；驱动返回其他内容时也不传播到日志。 */
    private static String safeSqlState(String sqlState) {
        if (sqlState == null || !sqlState.matches("[A-Za-z0-9]{5}")) {
            return "unknown";
        }
        return sqlState;
    }
}
