ALTER TABLE retrieval_observation_event
    DROP CONSTRAINT ck_retrieval_observation_stage;

ALTER TABLE retrieval_observation_event
    ADD CONSTRAINT ck_retrieval_observation_stage
        CHECK (stage IN (
            'EXECUTION_STARTED', 'SPACE_ROUTING', 'CONFIGURATION_RESOLVED',
            'QUERY_ANALYSIS', 'QUERY_PLANNING', 'RETRIEVAL_PLAN',
            'RETRIEVAL_BRANCH', 'FUSION', 'RERANK', 'COVERAGE_CHECK',
            'CHAIN_NODE_EVALUATED', 'CHAIN_NODE_COMPLETED', 'SPACE_CHANGED',
            'EVIDENCE_BUILD', 'EXECUTION_TERMINAL', 'STAGE_FAILURE'
        ));
