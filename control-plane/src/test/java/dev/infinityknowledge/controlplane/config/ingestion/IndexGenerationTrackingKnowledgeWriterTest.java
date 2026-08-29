package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证权威修订写入与真实索引基线代际在同一发布编排中完成。 */
class IndexGenerationTrackingKnowledgeWriterTest {

    private static final TenantId TENANT = new TenantId("tenant-baseline");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");

    @Test
    void writesDocumentBeforeResolvingItsBaselineGeneration() {
        KnowledgeWriter delegate = mock(KnowledgeWriter.class);
        IndexProjectionStore projectionStore = mock(IndexProjectionStore.class);
        KnowledgeWriteBatch batch = mock(KnowledgeWriteBatch.class);
        KnowledgeDocument document = mock(KnowledgeDocument.class);
        KnowledgeWriteResult expected = new KnowledgeWriteResult(
                DocumentId.random(),
                UUID.randomUUID(),
                true,
                3
        );
        when(batch.document()).thenReturn(document);
        when(document.tenantId()).thenReturn(TENANT);
        when(document.spaceId()).thenReturn(SPACE);
        when(delegate.write(batch)).thenReturn(expected);
        IndexingContract contract = new IndexingContract(
                "normalizer-v1",
                "chunker-v1"
        );
        EmbeddingSpec embeddingSpec = new EmbeddingSpec(
                "zhipu",
                "embedding-3",
                2_048
        );
        IndexPhysicalContract physicalContract = IndexPhysicalContract.baseline("v2");
        var writer = new IndexGenerationTrackingKnowledgeWriter(
                delegate,
                projectionStore,
                embeddingSpec,
                physicalContract,
                (tenantId, spaceId) -> contract,
                invokingTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        KnowledgeWriteResult actual = writer.write(batch);

        assertSame(expected, actual);
        InOrder order = inOrder(delegate, projectionStore);
        order.verify(delegate).write(batch);
        order.verify(projectionStore).resolveActiveGeneration(
                TENANT,
                SPACE,
                embeddingSpec,
                physicalContract,
                contract.normalizerVersion(),
                contract.chunkerVersion(),
                NOW
        );
        verify(batch).document();
    }

    @SuppressWarnings("unchecked")
    private static TransactionOperations invokingTransaction() {
        TransactionOperations transaction = mock(TransactionOperations.class);
        doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transaction).execute(any(TransactionCallback.class));
        return transaction;
    }
}
