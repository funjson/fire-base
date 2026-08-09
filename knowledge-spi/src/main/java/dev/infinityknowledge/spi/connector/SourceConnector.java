package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

/**
 * 定义外部知识源的增量读取端口。
 */
public interface SourceConnector {

    /**
     * 返回连接器实例的稳定标识。
     *
     * @return 连接器标识
     */
    String connectorId();

    /**
     * 从给定游标开始拉取有界批次。
     *
     * @param tenantId 目标租户
     * @param spaceId 目标知识空间
     * @param cursor 上次成功提交的游标
     * @param limit 最大记录数
     * @return 记录和后继游标
     */
    ConnectorBatch pull(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ConnectorCursor cursor,
            int limit
    );
}

