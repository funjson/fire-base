CREATE TABLE source_asset (
    tenant_id          varchar(64) NOT NULL,
    id                 uuid NOT NULL,
    space_id           varchar(64) NOT NULL,
    object_id          varchar(128) NOT NULL,
    storage_id         varchar(256) NOT NULL,
    original_file_name varchar(512) NOT NULL,
    media_type         varchar(128) NOT NULL,
    content_length     bigint NOT NULL,
    checksum_sha256    varchar(64) NOT NULL,
    stored_at          timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, space_id, object_id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    CONSTRAINT ck_source_asset_content_length CHECK (content_length >= 0),
    CONSTRAINT ck_source_asset_checksum
        CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_source_asset_space_created
    ON source_asset (tenant_id, space_id, created_at DESC);

CREATE TABLE extraction_run (
    tenant_id          varchar(64) NOT NULL,
    id                 uuid NOT NULL,
    space_id           varchar(64) NOT NULL,
    mode               varchar(32) NOT NULL DEFAULT 'TEST_ONLY',
    status             varchar(32) NOT NULL,
    language           varchar(32) NOT NULL,
    config_version     bigint NOT NULL,
    config_fingerprint varchar(64) NOT NULL,
    config_snapshot_json jsonb NOT NULL,
    dataset_id         varchar(128),
    baseline_run_id    uuid,
    gate_status        varchar(32) NOT NULL DEFAULT 'NOT_EVALUATED',
    gate_report_json   jsonb,
    created_by         varchar(128) NOT NULL,
    error_code         varchar(128),
    worker_id          varchar(128),
    created_at         timestamptz NOT NULL,
    ready_at           timestamptz,
    started_at         timestamptz,
    finished_at        timestamptz,
    cancel_requested_at timestamptz,
    heartbeat_at       timestamptz,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, space_id, id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    FOREIGN KEY (tenant_id, space_id, baseline_run_id)
        REFERENCES extraction_run(tenant_id, space_id, id),
    CONSTRAINT ck_extraction_run_mode CHECK (mode IN ('TEST_ONLY', 'INGEST')),
    CONSTRAINT ck_extraction_run_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'CANCEL_REQUESTED',
            'SUCCEEDED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_extraction_run_config_version CHECK (config_version = 1),
    CONSTRAINT ck_extraction_run_config_fingerprint CHECK (
        config_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_extraction_run_config_snapshot CHECK (
        jsonb_typeof(config_snapshot_json) = 'object'
        AND jsonb_typeof(config_snapshot_json -> 'processingContract') = 'object'
        AND jsonb_typeof(config_snapshot_json -> 'normalizerContract') = 'string'
        AND length(config_snapshot_json ->> 'normalizerContract') > 0
        AND jsonb_typeof(config_snapshot_json -> 'parserSelections') = 'object'
        AND jsonb_typeof(config_snapshot_json -> 'cleaning') = 'object'
        AND jsonb_typeof(config_snapshot_json -> 'chunker') = 'object'
        AND jsonb_typeof(config_snapshot_json -> 'fingerprint') = 'string'
        AND (config_snapshot_json ->> 'fingerprint') ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_extraction_run_gate_status CHECK (
        gate_status IN ('NOT_EVALUATED', 'PASSED', 'FAILED', 'ERROR')
    ),
    CONSTRAINT ck_extraction_run_gate_report CHECK (
        (gate_status = 'NOT_EVALUATED' AND gate_report_json IS NULL)
        OR (
            gate_status <> 'NOT_EVALUATED'
            AND dataset_id IS NOT NULL
            AND jsonb_typeof(gate_report_json) = 'object'
        )
    ),
    CONSTRAINT ck_extraction_run_ready
        CHECK (ready_at IS NULL OR ready_at >= created_at),
    CONSTRAINT ck_extraction_run_finished
        CHECK (finished_at IS NULL OR finished_at >= created_at)
);

CREATE UNIQUE INDEX ux_extraction_run_active_space
    ON extraction_run (tenant_id, space_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX ix_extraction_run_space_created
    ON extraction_run (tenant_id, space_id, created_at DESC);

CREATE INDEX ix_extraction_run_claim
    ON extraction_run (status, ready_at, heartbeat_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE TABLE extraction_run_item (
    tenant_id          varchar(64) NOT NULL,
    id                 uuid NOT NULL,
    run_id             uuid NOT NULL,
    source_asset_id    uuid NOT NULL,
    external_id        varchar(512) NOT NULL,
    title              varchar(512) NOT NULL,
    authority          smallint NOT NULL,
    status             varchar(32) NOT NULL,
    stage              varchar(32) NOT NULL,
    error_code         varchar(128),
    parser_id          varchar(128),
    processor_version  varchar(64),
    element_count      integer,
    chunk_count        integer,
    parse_duration_ms  bigint,
    clean_duration_ms  bigint,
    chunk_duration_ms  bigint,
    diagnostics_json   jsonb,
    preview_json       jsonb,
    preview_truncated  boolean NOT NULL DEFAULT false,
    document_id        uuid,
    revision_id        uuid,
    created_at         timestamptz NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, run_id, source_asset_id),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES extraction_run(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, source_asset_id)
        REFERENCES source_asset(tenant_id, id),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document(tenant_id, id),
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id),
    CONSTRAINT ck_extraction_run_item_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED_DUPLICATE',
            'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_extraction_run_item_authority CHECK (authority BETWEEN 0 AND 100),
    CONSTRAINT ck_extraction_run_item_publication_output CHECK (
        (document_id IS NULL) = (revision_id IS NULL)
    ),
    CONSTRAINT ck_extraction_run_item_stage CHECK (
        stage IN ('STORED', 'PARSE', 'CLEAN', 'CHUNK', 'PUBLISHING', 'COMPLETED')
    ),
    CONSTRAINT ck_extraction_run_item_counts CHECK (
        (element_count IS NULL OR element_count >= 0)
        AND (chunk_count IS NULL OR chunk_count >= 0)
        AND (parse_duration_ms IS NULL OR parse_duration_ms >= 0)
        AND (clean_duration_ms IS NULL OR clean_duration_ms >= 0)
        AND (chunk_duration_ms IS NULL OR chunk_duration_ms >= 0)
    ),
    CONSTRAINT ck_extraction_run_item_diagnostics CHECK (
        diagnostics_json IS NULL OR jsonb_typeof(diagnostics_json) = 'object'
    ),
    CONSTRAINT ck_extraction_run_item_preview CHECK (
        (preview_json IS NULL AND NOT preview_truncated)
        OR jsonb_typeof(preview_json) = 'object'
    )
);

CREATE INDEX ix_extraction_run_item_order
    ON extraction_run_item (tenant_id, run_id, created_at, id);

COMMENT ON TABLE source_asset IS
    '上传后保留在 OSS 的不可变来源原件；任务失败或取消不会删除。';
COMMENT ON TABLE extraction_run IS
    '统一的多文件抽取任务；mode 区分测试广场与正式摄取，门禁状态独立于执行状态。';
COMMENT ON COLUMN extraction_run.worker_id IS
    '当前后台执行节点标识；与 heartbeat_at 一起提供最小并发所有权校验。';
COMMENT ON TABLE extraction_run_item IS
    '逐文件抽取状态和非敏感聚合诊断，不保存正文或模型原始响应。';
COMMENT ON COLUMN extraction_run_item.diagnostics_json IS
    'Clean 去向与固定 16 项 Chunk 边界指标；禁止保存正文、向量或模型响应。';
COMMENT ON COLUMN extraction_run.config_snapshot_json IS
    '任务创建时的不可变有效处理配置；fingerprint 不包含 Space 固定版本、创建主体或固化时间。';
COMMENT ON COLUMN extraction_run.gate_report_json IS
    '真实 Dataset Runner 返回的硬门禁指标与原因码；未评测时必须为空。';
COMMENT ON COLUMN extraction_run_item.preview_json IS
    '管理员权限内可见的有界 Element/Chunk 结构与正文片段；禁止写入日志。';
COMMENT ON COLUMN extraction_run_item.stage IS
    'PUBLISHING 表示 INGEST 已进入不可安全取消的正式事务发布窗口。';
