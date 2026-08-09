package dev.infinityknowledge.spi.connector;

import java.util.List;
import java.util.Objects;

/**
 * 表示连接器一次有界拉取的记录和后继游标。
 *
 * @param records 外部记录
 * @param nextCursor 下一次拉取游标
 * @param hasMore 是否仍有数据
 */
public record ConnectorBatch(
        List<SourceRecord> records,
        ConnectorCursor nextCursor,
        boolean hasMore
) {

    /**
     * 复制批次并校验游标存在。
     */
    public ConnectorBatch {
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}

