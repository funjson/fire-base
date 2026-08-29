# 数据抽取验收规范

## 1. 目标

数据抽取验收用于比较同一 Space 的固化配置与某次测试请求临时覆盖的 Parser、Cleaner、
Chunker、Tokenizer 配置。验收标签必须绑定固定合同产生的规范化 Artifact UTF-16
`SourceRange`，禁止绑定某次运行生成的 ElementId 或 ChunkId。Cleaner、Chunker 和
Tokenizer 变化时同一份数据集可以复用；Parser 或 Artifact 合同变化时必须升级
Dataset，不能把旧坐标解释到新制品。

验收规则实现位于 `knowledge-evaluation` 的 `evaluation.extraction` 子包，本身保持纯 Java
且可离线复现；Space 测试广场在运行时会先通过真实 OSS 与 `ExtractionEngine` 产生观测，
再调用同一套规则。离线 Runner 不访问 PostgreSQL、对象存储、Docling 或模型服务，不代表
运行态测试广场绕过这些真实链路。

## 2. 当前可执行数据集

首批数据集位于：

```text
knowledge-evaluation/src/main/resources/extraction-acceptance/
├── dataset.json
└── sources/
    ├── markdown-enterprise.md
    └── plain-text-enterprise.txt
```

它覆盖以下能力：

- Markdown、纯文本及中英文普通段落；
- Markdown Front Matter、Heading、List、Table、Code；
- 长段落内部的 `MUST_JOIN`；
- 段落、表格与代码之间的 `MUST_BREAK`；
- Front Matter 元数据化，正文必须保留；
- Parse、Token 上限、SourceSpan、来源核算、静默截断五项硬门禁。

运行命令：

```powershell
.\mvnw.cmd -T1 -pl knowledge-evaluation -am `
  "-Dtest=ExtractionGoldenDatasetTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

测试使用 `ExtractionDatasetLoader` 加载清单，真实调用 `ExtractionEngine` 执行
Markdown 与 TXT 的 Parse → Clean → Chunk，再由 `ExtractionObservationFactory`
生成观测并交给 `ExtractionAcceptanceRunner`。Factory 不接收 Fixture 自报 Token 数，
而是用本次配置的实际 `TokenCounter` 对最终 `contextualText` 重算，并把 Tokenizer
合同写入报告。Space 测试广场必须复用该链路，不能在页面另写判定逻辑。

Dataset 已放入 main resources，可通过
`loadDataset("extraction-acceptance/dataset.json", classLoader)` 在运行态加载。报告同时
携带 Source SHA-256、Artifact SHA-256/合同/长度，以及 Processor、Normalizer、Parser、
Cleaner、Chunker、Tokenizer 完整合同，避免指标脱离输入或处理语义。

## 3. 标签规则

`dataset.json` 中每个 Case 至少应包含：

- `sourceSha256`：Golden Source 原始字节的 SHA-256；
- `artifactResource`、`artifactSha256`、`artifactContract`：经人工复核的规范化文本
  制品、哈希和坐标合同；
- `license`：Corpus 的许可或内部使用分类；
- `review`：`reviewedBy` 与 ISO-8601 `reviewedAt` 组成的人工复核记录；
- `expectedElements`：源范围、`ElementType` 与可选角色；
- `boundaries`：左右语义锚点及 `MUST_BREAK`/`MUST_JOIN`；
- `mustPreserve`：必须同时出现在可索引 Element 和最终 Chunk 中的范围；
- `mustRemove`：必须明确删除或标记为 `METADATA_ONLY`，且不得进入 Chunk 的范围；
- `hardGates`：单样例硬阈值。

范围采用 Java/Web 一致的 UTF-16 半开区间 `[startOffset, endOffset)`。标签只覆盖
有语义的正文；Markdown 围栏、标题符号等纯语法字符不计入来源核算分母。新增范围
时应先用脚本或编辑器核对实际子串，禁止凭行号估算偏移。

Loader 必须分别校验 Source 与 Artifact 原始字节哈希，并严格 UTF-8 解码 Artifact；
损坏字节不得替换为占位字符继续运行。仓库根目录 `.gitattributes` 将文本 Corpus 固定为
LF，避免 Windows checkout 改写换行并使 UTF-16 偏移失效；未来的 PDF/DOCX 等二进制
Corpus 不得套用文本换行规则。

当前样例使用 `INTERNAL-ONLY-SYNTHETIC`，表示它是只供仓库内部验收的合成语料分类，
不是 SPDX 标识或对第三方授予的许可证。`codex-agent:final-integration-validation` 只记录
本次 Source、哈希与标签一致性复核，不等同于人工业务签字。技术链路的受控验收可以使用
合成语料，但在把 Dataset 作为业务配置发布门禁前，必须补充明确的人类复核责任人。

来源核算率计算为：

```text
已由 Element、METADATA_ONLY 或明确删除覆盖的标签字符数
---------------------------------------------------------
mustPreserve 与 mustRemove 的去重字符总数
```

硬门禁默认建议值为：解析成功率 `1.0`、Token 超限 `0`、非法 SourceSpan `0`、
来源核算率 `1.0`、静默截断 `0`。本次测试配置即使平均指标更好，只要任一 Case 的
硬门禁失败，也不能用于创建新的正式 Space。

## 4. 扩展一个本地文本样例

1. 在 `sources/` 添加固定 UTF-8/LF 的 Golden Source 和经复核 Artifact；文本 Parser
   可以让二者指向同一文件，二进制 Parser 必须提供独立 Artifact。
2. 分别计算 Source 与 Artifact SHA-256，在 `dataset.json` 添加唯一 Case、许可分类、
   复核记录，并以 Artifact 范围标注元素、边界、保留和删除期望。
3. 只允许由真实 ExtractionEngine 结果经 `ExtractionObservationFactory` 产生门禁观测。
4. 先运行聚焦测试，再评审失败原因码；不要为了让本次测试通过而覆盖基线标签。
5. 当业务规则确实变化时升级 `datasetVersion`，保留旧版本报告用于回归对比。

## 5. Docling 已验收边界与待扩充 Corpus

本轮已经使用固定 digest 的 Docling Serve v1.20.0 和 `DoclingDocument@1.10.0` 完成
最小真实 Golden：PDF 必须成功；固定部署对带表格 DOCX 会丢失表格，系统必须以
`DOCLING_SOURCE_COVERAGE_MISMATCH` fail-closed，且 DOCX Adapter 保持不可选择。该门禁
证明协议与覆盖保护有效，但不能替代企业 Corpus。后续仍需建设经授权、固定 SHA-256 的
PDF/DOCX Golden Corpus，至少覆盖：

- 原生文本 PDF、扫描 OCR PDF、多栏排版、跨页段落、页眉页脚和脚注；
- 跨页表格、合并单元格、图片标题、公式、代码与列表层级；
- DOCX 标题样式、嵌套列表、表格、页眉页脚、批注和嵌入图片；
- 中英混排、emoji、组合字符、异常字体、损坏文件与超大文件资源门禁；
- Docling 超时、协议不兼容和部分输出，且不得静默降级到同版本 Baseline。

PDF/DOCX 是二进制 Source，文本范围应绑定固定 Parser 合同导出的规范化 Artifact，并额外
保存页码与 bounding box Golden 标签。当前领域链已保存 typed pageNumber 和 Artifact
UTF-16 `SourceRange`；bounding box、原件版面叠加和跨页坐标仍未实现，不能用估算位置
冒充精确来源。

企业 Corpus 不应直接提交含客户内容的文件。应使用自有、公开许可或脱敏合成文档，
清单记录许可来源、SHA-256、预期 Docling/Parser 版本和人工复核人。
