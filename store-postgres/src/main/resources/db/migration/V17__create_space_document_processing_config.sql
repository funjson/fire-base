CREATE TABLE space_document_processing_config (
    tenant_id                         varchar(64) NOT NULL,
    space_id                          varchar(64) NOT NULL,
    parser_selections_json            jsonb NOT NULL,
    cleaning_header_action            varchar(32) NOT NULL,
    cleaning_footer_action            varchar(32) NOT NULL,
    cleaning_page_number_action       varchar(32) NOT NULL,
    cleaning_watermark_action         varchar(32) NOT NULL,
    cleaning_front_matter_action      varchar(32) NOT NULL,
    chunker_provider_id               varchar(64) NOT NULL,
    tokenizer_id                      varchar(128) NOT NULL,
    minimum_tokens                    integer NOT NULL,
    target_tokens                     integer NOT NULL,
    maximum_tokens                    integer NOT NULL,
    overlap_tokens                    integer NOT NULL,
    chunker_provider_config_json      jsonb NOT NULL,
    pipeline_contract                 varchar(128) NOT NULL,
    normalizer_schema_contract        varchar(2048) NOT NULL,
    parser_contracts_json             jsonb NOT NULL,
    cleaner_contract                  text NOT NULL,
    chunker_contract                  text NOT NULL,
    processing_contract_fingerprint   varchar(64) NOT NULL,
    version                           bigint NOT NULL DEFAULT 1,
    updated_by                        varchar(128) NOT NULL,
    created_at                        timestamptz NOT NULL,
    updated_at                        timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, space_id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_space_document_processing_config_parsers
        CHECK (jsonb_typeof(parser_selections_json) = 'object'
            AND parser_selections_json <> '{}'::jsonb),
    CONSTRAINT ck_space_document_processing_config_cleaning_header
        CHECK (cleaning_header_action IN ('KEEP', 'REMOVE', 'METADATA_ONLY')),
    CONSTRAINT ck_space_document_processing_config_cleaning_footer
        CHECK (cleaning_footer_action IN ('KEEP', 'REMOVE', 'METADATA_ONLY')),
    CONSTRAINT ck_space_document_processing_config_cleaning_page_number
        CHECK (cleaning_page_number_action IN ('KEEP', 'REMOVE', 'METADATA_ONLY')),
    CONSTRAINT ck_space_document_processing_config_cleaning_watermark
        CHECK (cleaning_watermark_action IN ('KEEP', 'REMOVE', 'METADATA_ONLY')),
    CONSTRAINT ck_space_document_processing_config_cleaning_front_matter
        CHECK (cleaning_front_matter_action IN ('KEEP', 'REMOVE', 'METADATA_ONLY')),
    CONSTRAINT ck_space_document_processing_config_provider_id
        CHECK (chunker_provider_id ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_space_document_processing_config_tokenizer_id
        CHECK (tokenizer_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_space_document_processing_config_token_sizes
        CHECK (minimum_tokens BETWEEN 1 AND 65536
            AND target_tokens BETWEEN minimum_tokens AND 65536
            AND maximum_tokens BETWEEN target_tokens AND 65536
            AND overlap_tokens >= 0
            AND overlap_tokens < minimum_tokens),
    CONSTRAINT ck_space_document_processing_config_provider_config
        CHECK (jsonb_typeof(chunker_provider_config_json) = 'object'),
    CONSTRAINT ck_space_document_processing_contract_parsers
        CHECK (jsonb_typeof(parser_contracts_json) = 'object'
            AND parser_contracts_json <> '{}'::jsonb),
    CONSTRAINT ck_space_document_processing_contract_components
        CHECK (btrim(pipeline_contract) <> ''
            AND btrim(normalizer_schema_contract) <> ''
            AND btrim(cleaner_contract) <> ''
            AND btrim(chunker_contract) <> ''),
    CONSTRAINT ck_space_document_processing_contract_fingerprint
        CHECK (processing_contract_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_space_document_processing_config_version CHECK (version = 1)
);

COMMENT ON TABLE space_document_processing_config IS
    '知识空间创建时固化的 Parser、Cleaner 与 Chunker Provider 配置；处理语义改变时必须创建新 Space。';
COMMENT ON COLUMN space_document_processing_config.parser_selections_json IS
    '规范媒体类型到稳定 Parser 标识的完整 JSON 对象。';
COMMENT ON COLUMN space_document_processing_config.cleaning_header_action IS
    'Parser 识别为页眉的内容处理方式：KEEP、REMOVE 或 METADATA_ONLY。';
COMMENT ON COLUMN space_document_processing_config.cleaning_footer_action IS
    'Parser 识别为页脚的内容处理方式：KEEP、REMOVE 或 METADATA_ONLY。';
COMMENT ON COLUMN space_document_processing_config.cleaning_page_number_action IS
    'Parser 识别为页码的内容处理方式：KEEP、REMOVE 或 METADATA_ONLY。';
COMMENT ON COLUMN space_document_processing_config.cleaning_watermark_action IS
    'Parser 识别为水印的内容处理方式：KEEP、REMOVE 或 METADATA_ONLY。';
COMMENT ON COLUMN space_document_processing_config.cleaning_front_matter_action IS
    'Parser 识别为 Front Matter 的内容处理方式：KEEP、REMOVE 或 METADATA_ONLY。';
COMMENT ON COLUMN space_document_processing_config.chunker_provider_id IS
    '可扩展的稳定 Chunker Provider 标识；当前内置 STRUCTURAL 与 SEMANTIC_REFINEMENT。';
COMMENT ON COLUMN space_document_processing_config.tokenizer_id IS
    '计算 Token 尺寸并参与处理契约的稳定 TokenCounter 标识；当前内置实现以 UTF-8 字节数作为预算估算，exactModelTokens=false，不保证任意 Tokenizer 的数学硬上界。';
COMMENT ON COLUMN space_document_processing_config.minimum_tokens IS
    '普通 Chunk 的软最小 Token 数；硬结构边界可以产生更小块。';
COMMENT ON COLUMN space_document_processing_config.target_tokens IS
    '普通 Chunk 的目标 Token 数。';
COMMENT ON COLUMN space_document_processing_config.maximum_tokens IS
    '单个 Chunk 不得突破的 Token 硬上限。';
COMMENT ON COLUMN space_document_processing_config.overlap_tokens IS
    '相邻 Chunk 的重叠 Token 数；不得跨越硬结构边界。';
COMMENT ON COLUMN space_document_processing_config.chunker_provider_config_json IS
    'Provider 专属 canonical JSON Object；业务字段由对应 Provider 的强类型配置校验。';
COMMENT ON COLUMN space_document_processing_config.processing_contract_fingerprint IS
    '创建时实际 Pipeline、Normalizer、Parser、Cleaner、Chunker 与 Tokenizer 合同的总 SHA-256；升级漂移只允许拒绝，不提供历史实现路由。';
COMMENT ON COLUMN space_document_processing_config.version IS
    '不可变空间文档处理配置的存储版本，创建时固定为 1。';
COMMENT ON COLUMN space_document_processing_config.updated_by IS
    '创建并固化配置的租户主体标识。';
