-- V10 had to reconstruct leases from the pre-snapshot evaluation model. Restore
-- terminal history from the immutable result rows; only recoverable work follows
-- the dataset's current case list.
UPDATE evaluation_run run
   SET case_ids_json = history.case_ids
  FROM (
        SELECT terminal.tenant_id,
               terminal.id AS run_id,
               coalesce(
                   jsonb_agg(result.case_id ORDER BY result.case_id)
                       FILTER (WHERE result.case_id IS NOT NULL),
                   '[]'::jsonb
               ) AS case_ids
          FROM evaluation_run terminal
          LEFT JOIN evaluation_case_result result
            ON result.tenant_id = terminal.tenant_id
           AND result.run_id = terminal.id
         WHERE terminal.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
         GROUP BY terminal.tenant_id, terminal.id
  ) history
 WHERE run.tenant_id = history.tenant_id
   AND run.id = history.run_id;

UPDATE evaluation_run run
   SET case_ids_json = recoverable.case_ids
  FROM (
        SELECT active.tenant_id,
               active.id AS run_id,
               coalesce(
                   jsonb_agg(evaluation_case.id ORDER BY evaluation_case.created_at,
                                                       evaluation_case.id)
                       FILTER (WHERE evaluation_case.id IS NOT NULL),
                   '[]'::jsonb
               ) AS case_ids
          FROM evaluation_run active
          LEFT JOIN evaluation_case
            ON evaluation_case.tenant_id = active.tenant_id
           AND evaluation_case.dataset_id = active.dataset_id
         WHERE active.status IN ('PENDING', 'RUNNING')
         GROUP BY active.tenant_id, active.id
  ) recoverable
 WHERE run.tenant_id = recoverable.tenant_id
   AND run.id = recoverable.run_id;

-- A revision id is tenant-unique, but it must also belong to the aggregate that
-- references it. Fail with the first useful identity instead of an opaque FK error.
DO $$
DECLARE
    dirty record;
BEGIN
    SELECT violation, tenant_id, owner_id, revision_id
      INTO dirty
      FROM (
            SELECT 'knowledge_document.active_revision_id' AS violation,
                   document.tenant_id,
                   document.id::text AS owner_id,
                   document.active_revision_id AS revision_id
              FROM knowledge_document document
              JOIN document_revision revision
                ON revision.tenant_id = document.tenant_id
               AND revision.id = document.active_revision_id
             WHERE revision.document_id <> document.id
            UNION ALL
            SELECT 'knowledge_chunk.revision_id', chunk.tenant_id,
                   chunk.id::text, chunk.revision_id
              FROM knowledge_chunk chunk
              JOIN document_revision revision
                ON revision.tenant_id = chunk.tenant_id
               AND revision.id = chunk.revision_id
             WHERE revision.document_id <> chunk.document_id
            UNION ALL
            SELECT 'projection_job.revision_id', job.tenant_id,
                   job.id::text, job.revision_id
              FROM projection_job job
              JOIN document_revision revision
                ON revision.tenant_id = job.tenant_id
               AND revision.id = job.revision_id
             WHERE revision.document_id <> job.document_id
            UNION ALL
            SELECT 'document_index_projection.revision_id', projection.tenant_id,
                   projection.document_id::text, projection.revision_id
              FROM document_index_projection projection
              JOIN document_revision revision
                ON revision.tenant_id = projection.tenant_id
               AND revision.id = projection.revision_id
             WHERE revision.document_id <> projection.document_id
            UNION ALL
            SELECT 'knowledge_element.parent_id', child.tenant_id,
                   child.id::text, child.revision_id
              FROM knowledge_element child
              JOIN knowledge_element parent
                ON parent.tenant_id = child.tenant_id
               AND parent.id = child.parent_id
             WHERE parent.revision_id <> child.revision_id
      ) violations
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = format(
                'cross-document revision ownership: %s tenant=%s owner=%s revision=%s',
                dirty.violation, dirty.tenant_id, dirty.owner_id, dirty.revision_id
            );
    END IF;
END $$;

DO $$
DECLARE
    dirty record;
BEGIN
    SELECT violation, tenant_id, page_id, revision_id
      INTO dirty
      FROM (
            SELECT 'knowledge_page.latest_revision_id' AS violation,
                   page.tenant_id, page.id AS page_id,
                   page.latest_revision_id AS revision_id
              FROM knowledge_page page
              JOIN knowledge_page_revision revision
                ON revision.tenant_id = page.tenant_id
               AND revision.id = page.latest_revision_id
             WHERE revision.page_id <> page.id
            UNION ALL
            SELECT 'knowledge_page.active_revision_id', page.tenant_id,
                   page.id, page.active_revision_id
              FROM knowledge_page page
              JOIN knowledge_page_revision revision
                ON revision.tenant_id = page.tenant_id
               AND revision.id = page.active_revision_id
             WHERE revision.page_id <> page.id
            UNION ALL
            SELECT 'knowledge_page_review_event.revision_id', event.tenant_id,
                   event.page_id, event.revision_id
              FROM knowledge_page_review_event event
              JOIN knowledge_page_revision revision
                ON revision.tenant_id = event.tenant_id
               AND revision.id = event.revision_id
             WHERE revision.page_id <> event.page_id
      ) violations
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = format(
                'cross-page revision ownership: %s tenant=%s page=%s revision=%s',
                dirty.violation, dirty.tenant_id, dirty.page_id, dirty.revision_id
            );
    END IF;
END $$;

ALTER TABLE document_revision
    ADD CONSTRAINT uq_document_revision_owner
        UNIQUE (tenant_id, id, document_id);

ALTER TABLE knowledge_element
    ADD CONSTRAINT uq_knowledge_element_revision_owner
        UNIQUE (tenant_id, id, revision_id),
    ADD CONSTRAINT fk_knowledge_element_parent_revision_owner
        FOREIGN KEY (tenant_id, parent_id, revision_id)
        REFERENCES knowledge_element(tenant_id, id, revision_id) ON DELETE CASCADE;

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_active_revision_owner
        FOREIGN KEY (tenant_id, active_revision_id, id)
        REFERENCES document_revision(tenant_id, id, document_id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE knowledge_chunk
    ADD CONSTRAINT fk_knowledge_chunk_revision_owner
        FOREIGN KEY (tenant_id, revision_id, document_id)
        REFERENCES document_revision(tenant_id, id, document_id) ON DELETE CASCADE;

ALTER TABLE projection_job
    ADD CONSTRAINT fk_projection_job_revision_owner
        FOREIGN KEY (tenant_id, revision_id, document_id)
        REFERENCES document_revision(tenant_id, id, document_id) ON DELETE CASCADE;

ALTER TABLE document_index_projection
    ADD CONSTRAINT fk_document_index_projection_revision_owner
        FOREIGN KEY (tenant_id, revision_id, document_id)
        REFERENCES document_revision(tenant_id, id, document_id);

ALTER TABLE knowledge_page_revision
    ADD CONSTRAINT uq_knowledge_page_revision_owner
        UNIQUE (tenant_id, id, page_id);

ALTER TABLE knowledge_page
    ADD CONSTRAINT fk_knowledge_page_latest_revision_owner
        FOREIGN KEY (tenant_id, latest_revision_id, id)
        REFERENCES knowledge_page_revision(tenant_id, id, page_id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_knowledge_page_active_revision_owner
        FOREIGN KEY (tenant_id, active_revision_id, id)
        REFERENCES knowledge_page_revision(tenant_id, id, page_id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE knowledge_page_review_event
    ADD CONSTRAINT fk_knowledge_page_review_revision_owner
        FOREIGN KEY (tenant_id, revision_id, page_id)
        REFERENCES knowledge_page_revision(tenant_id, id, page_id);

-- New application versions always persist the caller and immutable case snapshot.
-- Keeping recovery defaults would silently turn incomplete writes into admin work.
ALTER TABLE connector_sync_run
    ALTER COLUMN principal_json DROP DEFAULT;

ALTER TABLE evaluation_run
    ALTER COLUMN principal_json DROP DEFAULT,
    ALTER COLUMN case_ids_json DROP DEFAULT;
