package dev.infinityknowledge.controlplane.api.document.ingestion;

import java.util.List;
import java.util.Objects;

/**
 * 描述一次正式多文件摄取中每个 Multipart 文件的业务发布属性。
 *
 * <p>列表顺序必须与重复出现的 {@code files} Part 一致。Parser、Cleaner 与 Chunker
 * 不允许在上传时覆盖，统一使用该 Space 创建任务时物化的处理配置。</p>
 */
public record IngestionManifest(List<Item> items) {

    /** 保存逐文件发布属性。 */
    public IngestionManifest {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    /**
     * 一个来源文件的逻辑身份、检索标题与治理权威等级。
     *
     * @param externalId 调用方维护的稳定来源键；重复键不表示覆盖
     * @param title 展示和检索标题；空白时服务端使用安全文件名
     * @param authority 来源权威等级；空值使用 80，不表示访问权限
     */
    public record Item(String externalId, String title, Integer authority) {
    }
}
