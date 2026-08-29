# tokenizer-huggingface

该模块用部署固定的 HuggingFace `tokenizer.json` 为 Chunk 硬上限提供模型 Token
计数。运行期只读取本地文件，不访问 HuggingFace Hub，也不会根据模型名称自动下载或
替换 Tokenizer。

## 部署配置

能力默认关闭。启用时必须同时提供稳定 ID、本地文件、SHA-256 和模型配置绑定：

```text
KNOWLEDGE_HUGGINGFACE_TOKENIZER_ENABLED=true
KNOWLEDGE_HUGGINGFACE_TOKENIZER_ID=BGE_M3_LOCAL
KNOWLEDGE_HUGGINGFACE_MODEL_PROFILE_ID=embedding/bge-m3@v1
KNOWLEDGE_HUGGINGFACE_TOKENIZER_JSON=/opt/knowledge/tokenizers/bge-m3/tokenizer.json
KNOWLEDGE_HUGGINGFACE_TOKENIZER_SHA256=<64 位小写十六进制指纹>
KNOWLEDGE_HUGGINGFACE_ADD_SPECIAL_TOKENS=true
```

加载前会先核对文件 SHA-256；指纹不一致时立即失败。DJL 实现版本、文件指纹和
`addSpecialTokens` 都进入 Tokenizer 契约，任一变化都会形成新的处理版本并要求重新
抽取/索引。`modelProfileId` 是运维固定的部署关系，不表示系统已经向模型服务自动验证
过配对结果。

## Golden 配对边界

上线前必须用目标 Embedding Serving 的真实计数或可审计 Token ID 建立 Golden 数据集，
至少覆盖中文、英文、混合文本、Emoji、组合字符、空白、长词、特殊 Token 和最大长度
边界。Golden 结果必须确认以下配置与 Serving 一致：

- 相同的 `tokenizer.json` 内容与 SHA-256；
- 相同的标准化、Pre-tokenizer 和 Post-processor；
- 相同的特殊 Token 开关与模板；
- 相同的截断、Padding 和最大上下文口径。

本模块保证对已加载的本地 Tokenizer 精确计数，但不能替代上述跨服务 Golden 验收。
密钥、原始文档正文和 Token 序列不得写入日志或 Trace。
