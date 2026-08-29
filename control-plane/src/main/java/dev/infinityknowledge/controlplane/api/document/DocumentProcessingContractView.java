package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.util.Map;
import java.util.Objects;

/** 不含凭据和端点的实际文档处理实现合同视图。 */
public record DocumentProcessingContractView(
        String pipelineContract,
        String normalizerSchemaContract,
        Map<String, String> parserContracts,
        String cleanerContract,
        String chunkerContract,
        String fingerprint
) {

    /** 将固化合同转换为可审计的只读 HTTP 结构。 */
    public static DocumentProcessingContractView from(
            DocumentProcessingContract contract
    ) {
        Objects.requireNonNull(contract, "contract must not be null");
        return new DocumentProcessingContractView(
                contract.pipelineContract(),
                contract.normalizerSchemaContract(),
                contract.parserContracts(),
                contract.cleanerContract(),
                contract.chunkerContract(),
                contract.fingerprint()
        );
    }
}
