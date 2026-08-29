# parser-docling

该模块把 Docling Serve 的 lossless JSON 转换为 Infinity Knowledge 的标准
`KnowledgeElement`，负责 PDF 和受独立门禁保护的 DOCX。第三方 `DoclingDocument` 不会进入 Domain、
空间配置或存储契约。

## 依赖版本说明

Docling Java 的 `0.5.4` 文档页面展示了 Maven 坐标，但 Maven Central 从未发布
`ai.docling:docling-serve-client:0.5.4`。仓库因此固定使用实际可解析的 `0.5.3`，并将
该版本写入 `parserVersion`。不能为了追随文档页面而声明一个无法构建的依赖版本。

服务镜像/模型包与 JSON Schema 分别由必填的 `deployment-contract`、
`server-contract` 固定。仓库真实 Golden 固定 CPU 镜像 v1.20.0 的完整摘要，并验证其
Docling Core 2.77.0 返回 `DoclingDocument@1.10.0`。两者都会进入 `parserVersion`；响应的 `schema_name` 或
`version` 变化时解析会明确失败，升级后必须修改配置并重新处理文档。

## 当前能力边界

- 严格按 `body.children` reading order 输出 TITLE、HEADING、PARAGRAPH、LIST、TABLE、CODE。
- 保留标题 `sectionPath`、可表达的 `parentId`、Docling 原始 parent/self_ref 和页码。
- 表格当前只输出 TSV 形式的扁平检索文本。
- DOCX 会用源文件中的非空表格做覆盖门禁；缺少 TABLE 或单元格覆盖时稳定返回
  `DOCLING_SOURCE_COVERAGE_MISMATCH`，异常不包含正文。
- 暂不声明 BOUNDING_BOX、TABLE_STRUCTURE、NATIVE_ARTIFACT；后续领域模型具备对应类型后再扩展。
- transport、partial、errors、Schema 漂移、无 JSON、坏引用、引用环和资源超限都明确失败，
  不回退到 PDFBox/POI。

## 部署

默认关闭。总开关只安装已通过固定部署 Golden 的 PDF Parser；DOCX 有独立开关且默认关闭：

```text
KNOWLEDGE_DOCLING_PARSER_ENABLED=true
KNOWLEDGE_DOCLING_DOCX_PARSER_ENABLED=false
KNOWLEDGE_DOCLING_ENDPOINT=http://docling-serve:5001
KNOWLEDGE_DOCLING_API_KEY=
KNOWLEDGE_DOCLING_DEPLOYMENT_CONTRACT=ghcr.io/docling-project/docling-serve-cpu:v1.20.0@sha256:419967009a6b507bf25380132335cf8b354e6ce9df4d40f46a612de2e9ddcb88
KNOWLEDGE_DOCLING_SERVER_CONTRACT=DoclingDocument@1.10.0
KNOWLEDGE_DOCLING_CONNECT_TIMEOUT=10s
KNOWLEDGE_DOCLING_DOCUMENT_TIMEOUT=2m
KNOWLEDGE_DOCLING_READ_TIMEOUT=130s
KNOWLEDGE_DOCLING_MAXIMUM_CONCURRENT_REQUESTS=2
```

固定的 v1.20.0 CPU 镜像能通过 PDF Golden，但其 DOCX lossless JSON 会丢失合成表格与
单元格文本。真实容器回归已把该行为固定为 fail-closed，因此当前部署不得打开 DOCX
开关；能力目录仍展示 `docling-serve-docx`，状态为不可用，并继续使用内置 POI Parser。
只有新的固定部署同时通过 DOCX 表格 Golden 后，运维才可显式开启该开关。

读取超时必须比服务端转换预算至少多 10 秒。本进程用共享并发门禁限制同时外呼数量，
满载时稳定返回 `DOCLING_BUSY`；SDK 的请求/响应日志始终关闭，避免原文和 lossless JSON
进入日志。
