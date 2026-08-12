package dev.infinityknowledge.spi.wiki;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.KnowledgePage;
import dev.infinityknowledge.domain.wiki.KnowledgePageId;
import dev.infinityknowledge.domain.wiki.KnowledgePageRevision;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Transactional persistence and publication boundary for compiled pages. */
public interface KnowledgePageStore {

    PageSnapshot saveDraft(DraftCommand command);

    PageSnapshot transition(TransitionCommand command);

    Optional<PageSnapshot> findById(TenantId tenantId, KnowledgePageId pageId);

    Optional<PageSnapshot> findPublished(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug
    );

    List<PageSnapshot> findAll(
            TenantId tenantId,
            Set<KnowledgeSpaceId> spaceIds,
            KnowledgePageStatus status
    );

    Optional<KnowledgePageRevision> findRevision(
            TenantId tenantId,
            KnowledgePageId pageId,
            UUID revisionId
    );

    List<PageSnapshot> findAffectedBySourceRevision(
            TenantId tenantId,
            UUID sourceRevisionId
    );

    record DraftCommand(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug,
            String title,
            KnowledgePageCompiler.CompiledPage content,
            PrincipalId actor,
            Instant now
    ) {
    }

    record TransitionCommand(
            TenantId tenantId,
            KnowledgePageId pageId,
            long expectedVersion,
            KnowledgePageStatus targetStatus,
            PrincipalId actor,
            Instant now
    ) {
    }

    record PageSnapshot(KnowledgePage page, KnowledgePageRevision revision) {
    }
}
