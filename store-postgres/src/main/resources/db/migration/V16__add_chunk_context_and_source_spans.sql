-- 原始正文用于展示、关键词和引用；上下文化正文只用于语义索引。
ALTER TABLE knowledge_chunk
    ADD COLUMN contextual_text text NOT NULL DEFAULT '',
    ADD COLUMN source_spans_json jsonb NOT NULL DEFAULT '[]'::jsonb;

-- 历史修订没有可恢复的元素内偏移，保留空范围并使用原正文作为安全回填值。
UPDATE knowledge_chunk
   SET contextual_text = content
 WHERE contextual_text = '';

ALTER TABLE knowledge_chunk
    ALTER COLUMN contextual_text DROP DEFAULT;

-- 数据库只校验稳定的容器与非空不变量；每个 Span 的元素归属和偏移上限由
-- KnowledgeWriteBatch 在同一写命令内结合 Element 正文完成校验。
ALTER TABLE knowledge_chunk
    ADD CONSTRAINT ck_knowledge_chunk_contextual_text
        CHECK (length(contextual_text) > 0),
    ADD CONSTRAINT ck_knowledge_chunk_source_spans_json_array
        CHECK (jsonb_typeof(source_spans_json) = 'array');
