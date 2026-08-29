CREATE TABLE retrieval_observation_event (
    tenant_id              varchar(64) NOT NULL REFERENCES knowledge_tenant(id),
    event_id               uuid NOT NULL,
    execution_id           uuid NOT NULL,
    request_id             uuid NOT NULL,
    sequence_number        bigint NOT NULL,
    purpose                varchar(32) NOT NULL,
    space_ids_json         jsonb NOT NULL,
    visit_index            integer NOT NULL,
    attempt_index          integer NOT NULL,
    stage                  varchar(64) NOT NULL,
    status                 varchar(32) NOT NULL,
    reason_code            varchar(64) NOT NULL,
    config_fingerprint     varchar(64) NOT NULL,
    stage_started_at       timestamptz NOT NULL,
    stage_completed_at     timestamptz NOT NULL,
    schema_version         integer NOT NULL,
    payload_type           varchar(96) NOT NULL,
    payload_json           jsonb NOT NULL,
    stored_at              timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, event_id),
    UNIQUE (tenant_id, execution_id, sequence_number),
    CONSTRAINT ck_retrieval_observation_sequence
        CHECK (sequence_number BETWEEN 0 AND 100000),
    CONSTRAINT ck_retrieval_observation_indexes
        CHECK (visit_index >= 0 AND attempt_index >= 0),
    CONSTRAINT ck_retrieval_observation_time
        CHECK (stage_completed_at >= stage_started_at),
    CONSTRAINT ck_retrieval_observation_schema
        CHECK (schema_version >= 1),
    CONSTRAINT ck_retrieval_observation_spaces_json
        CHECK (jsonb_typeof(space_ids_json) = 'array'),
    CONSTRAINT ck_retrieval_observation_payload_json
        CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_retrieval_observation_purpose
        CHECK (purpose IN ('ONLINE', 'EVALUATION', 'TEST_PLAZA', 'SHADOW', 'REPLAY')),
    CONSTRAINT ck_retrieval_observation_stage
        CHECK (stage IN (
            'EXECUTION_STARTED', 'SPACE_ROUTING', 'CONFIGURATION_RESOLVED',
            'QUERY_ANALYSIS', 'QUERY_PLANNING', 'RETRIEVAL_PLAN',
            'RETRIEVAL_BRANCH', 'FUSION', 'RERANK', 'COVERAGE_CHECK',
            'CHAIN_NODE_EVALUATED', 'CHAIN_NODE_COMPLETED', 'SPACE_CHANGED',
            'EVIDENCE_BUILD', 'EXECUTION_TERMINAL'
        )),
    CONSTRAINT ck_retrieval_observation_status
        CHECK (status IN (
            'STARTED', 'SUCCEEDED', 'DEGRADED', 'SKIPPED', 'FAILED',
            'TIMED_OUT', 'CANCELLED', 'REJECTED', 'NOT_CONFIGURED'
        )),
    CONSTRAINT ck_retrieval_observation_config
        CHECK (
            config_fingerprint = 'UNRESOLVED'
            OR config_fingerprint ~ '^[0-9a-f]{64}$'
        )
);

CREATE INDEX ix_retrieval_observation_execution
    ON retrieval_observation_event (tenant_id, execution_id, sequence_number);

CREATE INDEX ix_retrieval_observation_time
    ON retrieval_observation_event (tenant_id, stage_completed_at DESC);

CREATE TABLE retrieval_execution_observation (
    tenant_id                    varchar(64) NOT NULL REFERENCES knowledge_tenant(id),
    execution_id                 uuid NOT NULL,
    request_id                   uuid NOT NULL,
    purpose                      varchar(32) NOT NULL,
    completeness                 varchar(16) NOT NULL,
    incomplete_reasons_json      jsonb NOT NULL,
    missing_sequences_json       jsonb NOT NULL,
    visited_configurations_json  jsonb NOT NULL,
    event_count                  integer NOT NULL,
    last_sequence                bigint NOT NULL,
    terminal_status              varchar(32),
    terminal_reason_code         varchar(64),
    first_event_at               timestamptz NOT NULL,
    last_event_at                timestamptz NOT NULL,
    schema_version               integer NOT NULL,
    updated_at                   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, execution_id),
    CONSTRAINT ck_retrieval_execution_completeness
        CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    CONSTRAINT ck_retrieval_execution_json
        CHECK (
            jsonb_typeof(incomplete_reasons_json) = 'array'
            AND jsonb_typeof(missing_sequences_json) = 'array'
            AND jsonb_typeof(visited_configurations_json) = 'array'
        ),
    CONSTRAINT ck_retrieval_execution_counts
        CHECK (event_count >= 1 AND last_sequence >= 0),
    CONSTRAINT ck_retrieval_execution_time
        CHECK (last_event_at >= first_event_at),
    CONSTRAINT ck_retrieval_execution_schema
        CHECK (schema_version >= 1),
    CONSTRAINT ck_retrieval_execution_terminal_pair
        CHECK (
            (terminal_status IS NULL AND terminal_reason_code IS NULL)
            OR (terminal_status IS NOT NULL AND terminal_reason_code IS NOT NULL)
        )
);

CREATE INDEX ix_retrieval_execution_observation_time
    ON retrieval_execution_observation (tenant_id, updated_at DESC);

CREATE TABLE retrieval_metric_fact (
    tenant_id               varchar(64) NOT NULL,
    fact_id                 char(64) NOT NULL,
    fact_type               varchar(32) NOT NULL,
    source_event_id         uuid NOT NULL,
    execution_id            uuid NOT NULL,
    metric_key              varchar(128) NOT NULL,
    metric_definition_version integer NOT NULL,
    aggregation             varchar(32) NOT NULL,
    metric_value            double precision NOT NULL,
    space_id                varchar(64),
    query_case_id           uuid,
    config_fingerprint      varchar(64) NOT NULL,
    strategy                varchar(64),
    attempt_index           integer,
    component_model         varchar(512),
    data_index_version      varchar(128),
    status                  varchar(32) NOT NULL,
    purpose                 varchar(32) NOT NULL,
    time_slice              varchar(64) NOT NULL,
    dataset_id              uuid,
    dataset_version         bigint,
    case_id                 uuid,
    dimensions_json         jsonb NOT NULL,
    fact_json               jsonb NOT NULL,
    observed_at             timestamptz NOT NULL,
    stored_at               timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, fact_id),
    FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES retrieval_observation_event(tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT ck_retrieval_metric_fact_type
        CHECK (fact_type IN ('RUNTIME', 'OFFLINE_GOLD')),
    CONSTRAINT ck_retrieval_metric_fact_version
        CHECK (metric_definition_version >= 1),
    CONSTRAINT ck_retrieval_metric_fact_value
        CHECK (
            metric_value BETWEEN
                '-1.7976931348623157e308'::double precision
                AND '1.7976931348623157e308'::double precision
        ),
    CONSTRAINT ck_retrieval_metric_fact_attempt
        CHECK (attempt_index IS NULL OR attempt_index >= 0),
    CONSTRAINT ck_retrieval_metric_fact_config
        CHECK (
            config_fingerprint = 'UNRESOLVED'
            OR config_fingerprint ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_retrieval_metric_fact_json
        CHECK (
            jsonb_typeof(dimensions_json) = 'object'
            AND jsonb_typeof(fact_json) = 'object'
        ),
    CONSTRAINT ck_retrieval_metric_fact_gold_fields
        CHECK (
            (fact_type = 'RUNTIME'
                AND dataset_id IS NULL AND dataset_version IS NULL AND case_id IS NULL)
            OR
            (fact_type = 'OFFLINE_GOLD'
                AND dataset_id IS NOT NULL AND dataset_version >= 1 AND case_id IS NOT NULL)
        )
);

CREATE INDEX ix_retrieval_metric_fact_query
    ON retrieval_metric_fact (
        tenant_id, metric_key, metric_definition_version, purpose, observed_at DESC
    );

CREATE INDEX ix_retrieval_metric_fact_execution
    ON retrieval_metric_fact (tenant_id, execution_id, observed_at);

CREATE INDEX ix_retrieval_metric_fact_space_config
    ON retrieval_metric_fact (
        tenant_id, space_id, config_fingerprint, metric_key, observed_at DESC
    );
