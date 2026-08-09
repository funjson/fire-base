package dev.infinityknowledge.spi.connector;

import java.util.Map;
import java.util.Objects;

/**
 * 表示由连接器解释的增量同步游标。
 *
 * @param values 不透明游标属性
 */
public record ConnectorCursor(Map<String, String> values) {

    /**
     * 复制游标属性，避免同步期间被外部修改。
     */
    public ConnectorCursor {
        values = Map.copyOf(Objects.requireNonNull(values, "cursor values must not be null"));
    }

    /**
     * 创建首次全量同步使用的空游标。
     *
     * @return 空游标
     */
    public static ConnectorCursor initial() {
        return new ConnectorCursor(Map.of());
    }
}

