# knowledge-ingestion

知识摄取的纯 Java 处理层。当前提供 Markdown 结构解析与标题感知切分，不依赖 Spring
或具体存储。解析结果保留章节路径和稳定标识，供后续关键字、向量与图投影共同使用。

首版支持：

- YAML Front Matter 隔离；
- 标题、段落、列表、表格、代码块结构保留；
- 标题路径感知的语义切分；
- 超大元素按自然边界拆分；
- 确定性 Element/Chunk ID 与内容指纹。

PDF、DOCX、HTML 解析器将遵循相同输入输出约束接入，避免修改检索与索引层。
