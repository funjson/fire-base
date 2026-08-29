package dev.infinityknowledge.controlplane.application.ingestion.extraction;

import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset;
import dev.infinityknowledge.evaluation.extraction.ExtractionDatasetLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供测试广场可执行的受版本控制 Extraction Golden Dataset。
 *
 * <p>当前首个数据集随 knowledge-evaluation Artifact 发布，Source、Artifact、标签、
 * 哈希和复核记录属于同一个不可变版本；后续外部数据集 Adapter 只需向本目录注册
 * 新 manifest，不能覆盖已经存在的 datasetVersion。</p>
 */
@Service
public final class ExtractionDatasetCatalog {

    private static final List<String> BUILT_IN_MANIFESTS = List.of(
            "extraction-acceptance/dataset.json"
    );

    private final Map<String, ExtractionAcceptanceDataset> datasets;

    /** 启动时加载并校验内置 Corpus，损坏资源不能静默隐藏。 */
    public ExtractionDatasetCatalog() {
        ExtractionDatasetLoader loader = new ExtractionDatasetLoader();
        Map<String, ExtractionAcceptanceDataset> loaded = new LinkedHashMap<>();
        for (String manifest : BUILT_IN_MANIFESTS) {
            ExtractionAcceptanceDataset dataset;
            try {
                dataset = loader.loadDataset(manifest, ExtractionDatasetCatalog.class
                        .getClassLoader());
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "built-in extraction dataset cannot be loaded",
                        failure
                );
            }
            if (loaded.putIfAbsent(dataset.datasetVersion(), dataset) != null) {
                throw new IllegalStateException("duplicate extraction dataset version");
            }
        }
        datasets = Map.copyOf(loaded);
    }

    /** 返回不含 Source 或 Artifact 正文的稳定目录。 */
    public List<DatasetDescriptor> descriptors() {
        return datasets.values().stream()
                .map(dataset -> new DatasetDescriptor(
                        dataset.datasetVersion(),
                        dataset.cases().size(),
                        dataset.cases().stream().map(value -> new CaseDescriptor(
                                value.id(),
                                value.description(),
                                value.sourceName(),
                                value.sourceSha256(),
                                value.mediaType(),
                                value.license(),
                                value.review().reviewedBy(),
                                value.review().reviewedAt()
                        )).toList()
                ))
                .toList();
    }

    /** 按不可变版本取得完整 Dataset，仅供受控运行时执行。 */
    public ExtractionAcceptanceDataset require(String datasetId) {
        ExtractionAcceptanceDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("extraction dataset does not exist");
        }
        return dataset;
    }

    /** 页面可选择的数据集摘要。 */
    public record DatasetDescriptor(
            String id,
            int caseCount,
            List<CaseDescriptor> cases
    ) {
    }

    /** 不暴露 Corpus 正文的 Case 摘要。 */
    public record CaseDescriptor(
            String id,
            String description,
            String sourceName,
            String sourceSha256,
            String mediaType,
            String license,
            String reviewedBy,
            String reviewedAt
    ) {
    }
}
