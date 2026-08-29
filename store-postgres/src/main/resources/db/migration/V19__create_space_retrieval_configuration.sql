CREATE TABLE space_retrieval_configuration_version (
    tenant_id           varchar(64) NOT NULL,
    space_id            varchar(64) NOT NULL,
    revision            bigint NOT NULL,
    configuration_json  jsonb NOT NULL,
    fingerprint         varchar(64) NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, space_id, revision),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_space_retrieval_configuration_revision
        CHECK (revision > 0),
    CONSTRAINT ck_space_retrieval_configuration_json
        CHECK (jsonb_typeof(configuration_json) = 'object'
            AND configuration_json ?& ARRAY[
                'firstRound',
                'branches',
                'reranker',
                'coverage',
                'maximumRetrievalAttempts',
                'chainNodeEnables',
                'crossSpace'
            ]),
    CONSTRAINT ck_space_retrieval_configuration_fingerprint
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_space_retrieval_configuration_created_by
        CHECK (btrim(created_by) <> '')
);

CREATE TABLE space_retrieval_configuration_current (
    tenant_id          varchar(64) NOT NULL,
    space_id           varchar(64) NOT NULL,
    current_revision   bigint NOT NULL,
    activated_by       varchar(128) NOT NULL,
    activated_at       timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, space_id),
    FOREIGN KEY (tenant_id, space_id, current_revision)
        REFERENCES space_retrieval_configuration_version(
            tenant_id,
            space_id,
            revision
        ) ON DELETE CASCADE,
    CONSTRAINT ck_space_retrieval_configuration_current_revision
        CHECK (current_revision > 0),
    CONSTRAINT ck_space_retrieval_configuration_activated_by
        CHECK (btrim(activated_by) <> '')
);

-- 迁移前已经存在的 Space 必须立即物化同一份确定性基线，否则新 Runtime 会把
-- “缺少当前配置”视为数据不完整并拒绝检索。该指纹与领域对象的稳定算法一致。
INSERT INTO space_retrieval_configuration_version
    (tenant_id, space_id, revision, configuration_json,
     fingerprint, created_by, created_at)
SELECT
    space.tenant_id,
    space.id,
    1,
    jsonb_build_object(
        'firstRound', jsonb_build_object(
            'termExpansionEnabled', false,
            'terminologyResourceId', 'none',
            'maximumExpansionTerms', 0
        ),
        'branches', jsonb_build_object(
            'maximumVariantsPerAttempt', 1,
            'maximumRetrievalBranches', 4,
            'rrfConstant', 60,
            'channels', jsonb_build_object(
                'KEYWORD', jsonb_build_object(
                    'enabled', true, 'topK', 40, 'rrfWeight', 1.0
                ),
                'VECTOR', jsonb_build_object(
                    'enabled', true, 'topK', 40, 'rrfWeight', 1.0
                ),
                'GRAPH', jsonb_build_object(
                    'enabled', true, 'topK', 20, 'rrfWeight', 0.8
                ),
                'PAGE', jsonb_build_object(
                    'enabled', true, 'topK', 20, 'rrfWeight', 0.8
                )
            )
        ),
        'reranker', jsonb_build_object(
            'enabled', false,
            'providerId', 'deterministic',
            'modelId', 'rrf-order',
            'candidateLimit', 40,
            'outputTopK', 8
        ),
        'coverage', jsonb_build_object(
            'enabled', false,
            'providerId', 'deterministic',
            'modelId', 'none',
            'promptVersion', 'none',
            'memoryLimit', 20,
            'sufficiencyThreshold', 0.75
        ),
        'maximumRetrievalAttempts', 1,
        'chainNodeEnables', jsonb_build_object(
            'GAP_QUERY', false,
            'PRF', false,
            'RELAX_CONSTRAINTS', false,
            'NARROW_CONSTRAINTS', false,
            'STEP_BACK', false,
            'HYDE', false,
            'NEXT_SPACE', false
        ),
        'crossSpace', jsonb_build_object(
            'enabled', false,
            'maximumSpaces', 1
        )
    ),
    'd1501dc9baefa3671ea42757436fafa973cb5dd4166768659c39e912f8efad98',
    'system',
    space.created_at
FROM knowledge_space space
ON CONFLICT (tenant_id, space_id, revision) DO NOTHING;

INSERT INTO space_retrieval_configuration_current
    (tenant_id, space_id, current_revision, activated_by, activated_at)
SELECT
    version.tenant_id,
    version.space_id,
    version.revision,
    version.created_by,
    version.created_at
FROM space_retrieval_configuration_version version
WHERE version.revision = 1
ON CONFLICT (tenant_id, space_id) DO NOTHING;

CREATE FUNCTION reject_space_retrieval_configuration_version_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'space retrieval configuration versions are immutable';
END;
$$;

CREATE TRIGGER trg_space_retrieval_configuration_version_immutable
BEFORE UPDATE ON space_retrieval_configuration_version
FOR EACH ROW
EXECUTE FUNCTION reject_space_retrieval_configuration_version_update();

COMMENT ON TABLE space_retrieval_configuration_version IS
    'Space 检索配置的不可变修订；不保存草稿或发布状态。';
COMMENT ON COLUMN space_retrieval_configuration_version.configuration_json IS
    '完整物化的 Q0/术语增强、分支、RRF、精排、Coverage、预算和固定 Chain 节点开关；不包含索引配置。';
COMMENT ON COLUMN space_retrieval_configuration_version.fingerprint IS
    '只由有效检索语义计算的稳定 SHA-256，不包含 Space 身份和审计字段。';
COMMENT ON TABLE space_retrieval_configuration_current IS
    '每个 Space 当前使用的检索配置修订指针。';
COMMENT ON COLUMN space_retrieval_configuration_current.current_revision IS
    '原子切换后的当前不可变修订号。';
