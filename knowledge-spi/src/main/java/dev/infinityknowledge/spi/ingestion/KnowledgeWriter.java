package dev.infinityknowledge.spi.ingestion;

/**
 * 定义文档修订及检索投影的事务写入端口。
 */
public interface KnowledgeWriter {

    /**
     * 幂等写入并发布一个文档修订。
     *
     * @param batch 写入批次
     * @return 写入结果
     */
    KnowledgeWriteResult write(KnowledgeWriteBatch batch);
}
