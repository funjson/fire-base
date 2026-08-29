package dev.infinityknowledge.controlplane.api.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证未知异常诊断既可定位代码，又不会泄露异常消息中的业务数据。 */
class LogSafeExceptionDiagnosticTest {

    @Test
    void keepsTypesFramesAndSafeSqlCodesButDropsMessages() {
        var failure = new IllegalStateException(
                "full user query must stay private",
                new SQLException(
                        "SQL and document content must stay private",
                        "42703",
                        7
                )
        );

        String diagnostic = LogSafeExceptionDiagnostic.format(failure);

        assertTrue(diagnostic.contains(IllegalStateException.class.getName()));
        assertTrue(diagnostic.contains(SQLException.class.getName()));
        assertTrue(diagnostic.contains("sqlState=42703"));
        assertTrue(diagnostic.contains("vendorCode=7"));
        assertTrue(diagnostic.contains("LogSafeExceptionDiagnosticTest.java"));
        assertFalse(diagnostic.contains("full user query"));
        assertFalse(diagnostic.contains("document content"));
    }

    @Test
    void rejectsDriverSuppliedNonStandardSqlStateText() {
        var failure = new SQLException(
                "provider response must stay private",
                "raw-provider-response"
        );

        String diagnostic = LogSafeExceptionDiagnostic.format(failure);

        assertTrue(diagnostic.contains("sqlState=unknown"));
        assertFalse(diagnostic.contains("raw-provider-response"));
        assertFalse(diagnostic.contains("provider response"));
    }
}
