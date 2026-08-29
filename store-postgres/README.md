# store-postgres

`store-postgres` 使用 PostgreSQL、Spring JDBC 和 Flyway 实现知识系统的事务事实存储。

PostgreSQL 负责保存租户与权限、知识事实、生命周期、异步任务、评测、Trace 和审计。Elasticsearch、Milvus、Neo4j 是可以从 PostgreSQL 重建的异构投影，不是事实源。

## Schema 基线

本文描述执行 **Flyway V1～V18 全部迁移后的最终结构**，不是任何单个迁移文件创建时的历史结构。

- 唯一事实源：[src/main/resources/db/migration](src/main/resources/db/migration)
- 当前表数量：31
- 当前基线：V17
- 新增或修改迁移时，必须同步更新本文。

数据库名称、Schema 名称和连接参数由运行环境决定；下文表名均位于应用配置的默认 PostgreSQL Schema 中。

## PostgreSQL 类型约定

| 类型 | 项目中的含义 |
|---|---|
| `uuid` | Document Revision、运行、任务、Trace 等内部不可变标识 |
| `varchar(n)` | 有明确长度上限的标识、枚举、名称或错误码 |
| `text` | 正文、Markdown、URI、描述等不适合设置短长度上限的文本 |
| `smallint` | 小范围整数；当前用于 `authority` |
| `integer` | 顺序、数量、Top K、HTTP 状态等 32 位整数 |
| `bigint` | 乐观锁版本、计数、耗时和 fencing token |
| `boolean` | 布尔状态 |
| `double precision` | Recall、MRR、nDCG 等评测指标 |
| `timestamptz` | 带时区的绝对时间；Java 侧按 `Instant` 使用 |
| `jsonb` | 元数据、配置、游标、调用者快照、来源快照等结构化扩展字段 |
| `tsvector` | 由 Chunk 正文自动生成的 PostgreSQL 全文检索向量 |

表格中的“可空/默认”按 V17 后的最终结构描述：

- `否`：`NOT NULL`，调用方必须提供或由数据库默认值产生。
- `是`：允许 `NULL`。
- `否 / <值>`：`NOT NULL` 且具有该默认值。
- `生成列`：由 PostgreSQL 根据其他字段计算，应用不得直接写入。

## 表总览

| 业务域 | 表 | 作用 |
|---|---|---|
| 租户与权限 | `knowledge_tenant` | 租户根记录 |
| 租户与权限 | `knowledge_principal` | 租户内用户或服务主体 |
| 租户与权限 | `knowledge_space` | 知识隔离、授权和检索边界 |
| 租户与权限 | `knowledge_space_acl` | 空间 ACL 授权项 |
| 文档处理配置 | `space_document_processing_config` | 空间级 Parser、内容清洗与 Chunker Provider 配置 |
| Connector | `connector_instance` | 外部来源连接器实例 |
| Connector | `connector_checkpoint` | 连接器持久化游标 |
| Connector | `connector_sync_run` | 一次可恢复的异步同步运行 |
| Connector | `connector_manifest` | 上次成功完整快照的权威清单 |
| Connector | `connector_snapshot_manifest` | 本轮同步的暂存清单 |
| 知识事实 | `knowledge_document` | 跨修订稳定的文档聚合 |
| 知识事实 | `document_revision` | 不可变文档处理修订 |
| 知识事实 | `document_source_object` | Revision 对应的原文件目录记录 |
| 知识事实 | `knowledge_element` | Parser 产生的结构元素 |
| 知识事实 | `knowledge_chunk` | 参与检索的 Chunk |
| 索引投影 | `index_generation` | 空间的一套索引配置代际 |
| 索引投影 | `document_index_projection` | 某代际下文档投影结果摘要 |
| 索引投影 | `projection_job` | 异步投影可靠任务队列 |
| Wiki | `knowledge_page` | 稳定的编译知识页聚合 |
| Wiki | `knowledge_page_revision` | 不可变知识页修订 |
| Wiki | `knowledge_page_review_event` | 知识页审核状态事件 |
| 检索观测 | `retrieval_trace` | 一次检索的 Trace 摘要 |
| 检索观测 | `retrieval_trace_step` | Trace 内有序步骤 |
| 变更审计 | `mutation_audit_event` | 控制面写请求审计事件 |
| 评测 | `evaluation_dataset` | 可版本化 Retrieval 评测集 |
| 评测 | `evaluation_case` | 单条黄金检索用例 |
| 评测 | `evaluation_run` | 一次可恢复的异步评测运行 |
| 评测 | `evaluation_case_result` | Run 中每个 Case 的结果 |

## 核心关系

```text
knowledge_tenant
  ├─ knowledge_principal
  └─ knowledge_space
       ├─ knowledge_space_acl
       ├─ space_document_processing_config
       ├─ connector_instance
       │    ├─ connector_checkpoint
       │    ├─ connector_sync_run
       │    ├─ connector_manifest
       │    └─ connector_snapshot_manifest
       ├─ knowledge_document
       │    └─ document_revision
       │         ├─ knowledge_element
       │         ├─ knowledge_chunk
       │         ├─ document_source_object
       │         └─ projection_job
       ├─ index_generation
       │    └─ document_index_projection
       └─ knowledge_page
            ├─ knowledge_page_revision
            └─ knowledge_page_review_event

evaluation_dataset
  ├─ evaluation_case
  └─ evaluation_run
       └─ evaluation_case_result

retrieval_trace
  └─ retrieval_trace_step
```

多租户表通常把 `tenant_id` 放入主键、唯一约束和外键，避免只依赖应用层过滤。V15 进一步使用复合外键保证 Revision 必须属于引用它的 Document 或 Page。

---

## 一、租户与权限

### `knowledge_tenant` — 租户

知识系统的租户根记录。所有租户级数据最终都归属于这里的 `id`。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `varchar(64)` | 否 | 租户稳定标识；主键 |
| `display_name` | `varchar(256)` | 否 | 租户展示名称 |
| `status` | `varchar(32)` | 否 | `ACTIVE`、`SUSPENDED`、`DELETED` |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与关系：

- 主键：`id`
- `status` 由 CHECK 约束限制为 `ACTIVE/SUSPENDED/DELETED`。
- `knowledge_principal`、`knowledge_space`、`retrieval_trace` 等表引用该表。

### `knowledge_principal` — 租户主体

OIDC 用户或服务身份进入知识系统后的本地治理投影。它不是 Keycloak 用户库的复制品，只保存知识权限和审计所需的稳定主体信息。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `principal_id` | `varchar(128)` | 否 | 租户内主体稳定标识 |
| `principal_type` | `varchar(32)` | 否 | `USER` 或 `SERVICE` |
| `display_name` | `varchar(256)` | 否 | 主体展示名称 |
| `status` | `varchar(32)` | 否 | `ACTIVE`、`SUSPENDED`、`DELETED` |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与关系：

- 主键：`(tenant_id, principal_id)`
- 外键：`tenant_id → knowledge_tenant.id`
- `mutation_audit_event` 使用租户与主体复合外键引用该表。

### `knowledge_space` — 知识空间

租户内的知识隔离、ACL 和检索范围边界。Document、Connector、Wiki Page 和 Index Generation 都属于一个 Space。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `varchar(64)` | 否 | 租户内空间稳定标识 |
| `name` | `varchar(256)` | 否 | 空间名称 |
| `description` | `text` | 否 / `''` | 空间说明 |
| `status` | `varchar(32)` | 否 | `ACTIVE`、`ARCHIVED`、`DELETED` |
| `version` | `bigint` | 否 / `0` | 乐观锁版本，必须大于等于 0 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与关系：

- 主键：`(tenant_id, id)`
- 外键：`tenant_id → knowledge_tenant.id`
- Space 删除时，ACL 会级联删除；Document、Connector 等是否删除由各自外键策略控制。

### `knowledge_space_acl` — 空间授权项

为用户、角色、部门或整个租户授予 Space 权限。一个主体可同时拥有多个权限项。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `space_id` | `varchar(64)` | 否 | 被授权空间 |
| `subject_type` | `varchar(32)` | 否 | `USER`、`ROLE`、`DEPARTMENT`、`TENANT` |
| `subject_id` | `varchar(128)` | 否 | 用户 ID、角色名、部门 ID 或租户 ID |
| `permission` | `varchar(32)` | 否 | `READ`、`WRITE`、`ADMIN` |
| `granted_by` | `varchar(128)` | 否 | 授权操作者主体 ID |
| `created_at` | `timestamptz` | 否 | 授权时间 |

约束与索引：

- 主键：`(tenant_id, space_id, subject_type, subject_id, permission)`
- 外键：`(tenant_id, space_id) → knowledge_space`，Space 删除时级联删除。
- 索引：`ix_knowledge_space_acl_subject(tenant_id, subject_type, subject_id, permission)`，用于按当前主体查询权限。

### `space_document_processing_config` — 空间文档处理配置

保存每个 Space 明确选择的 Parser、内容清洗和 Chunker 处理语义。数据库、API 和代码
统一使用 `document-processing-config` / `SpaceDocumentProcessingConfig`。创建 Space 的请求
必须携带完整配置；真正的新 Space 会在同一事务中完成能力校验并原子创建 Space、配置、
创建者 ACL 和上传 Connector。精确重复请求只核对已有固化定义，不覆盖原记录。配置从创建
起不可修改，不存在执行期补写动态默认值或管理员乐观更新。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `space_id` | `varchar(64)` | 否 | 配置所属知识空间 |
| `parser_selections_json` | `jsonb` | 否 | 规范媒体类型到稳定 Parser 标识的完整对象 |
| `cleaning_header_action` | `varchar(32)` | 否 | Parser 明确识别为页眉的元素处理方式：`KEEP`、`REMOVE` 或 `METADATA_ONLY` |
| `cleaning_footer_action` | `varchar(32)` | 否 | Parser 明确识别为页脚的元素处理方式：`KEEP`、`REMOVE` 或 `METADATA_ONLY` |
| `cleaning_page_number_action` | `varchar(32)` | 否 | Parser 明确识别为页码的元素处理方式：`KEEP`、`REMOVE` 或 `METADATA_ONLY` |
| `cleaning_watermark_action` | `varchar(32)` | 否 | Parser 明确识别为水印的元素处理方式：`KEEP`、`REMOVE` 或 `METADATA_ONLY` |
| `cleaning_front_matter_action` | `varchar(32)` | 否 | Parser 明确识别为 Front Matter 的元素处理方式：`KEEP`、`REMOVE` 或 `METADATA_ONLY` |
| `chunker_provider_id` | `varchar(64)` | 否 | 稳定且可扩展的 Chunker Provider ID；当前内置 `STRUCTURAL`、`SEMANTIC_REFINEMENT` |
| `tokenizer_id` | `varchar(128)` | 否 | 计算尺寸并进入处理契约的稳定 TokenCounter ID |
| `minimum_tokens` | `integer` | 否 | 普通 Chunk 的软最小 Token 数；硬结构边界可以产生更小块 |
| `target_tokens` | `integer` | 否 | 普通 Chunk 的目标 Token 数 |
| `maximum_tokens` | `integer` | 否 | 单个 Chunk 不得突破的 Token 硬上限 |
| `overlap_tokens` | `integer` | 否 | 相邻 Chunk 的重叠 Token 数；不得跨越硬结构边界 |
| `chunker_provider_config_json` | `jsonb` | 否 | Provider 专属 canonical JSON Object；业务字段由对应 Provider 强类型校验 |
| `pipeline_contract` | `varchar(128)` | 否 | Space 创建时实际抽取主线合同 |
| `normalizer_schema_contract` | `varchar(2048)` | 否 | Markdown 与二进制入口允许使用的来源规范化规则集合 |
| `parser_contracts_json` | `jsonb` | 否 | 规范媒体类型到创建时实际 Parser 实现合同的非空对象；应用读取后按键排序 |
| `cleaner_contract` | `text` | 否 | 创建时实际 Cleaner 实现和有效动作合同 |
| `chunker_contract` | `text` | 否 | 创建时实际 Provider、Tokenizer、模型预算与切分参数合同 |
| `processing_contract_fingerprint` | `varchar(64)` | 否 | 上述实际实现材料的总 SHA-256 |
| `version` | `bigint` | 否 / `1` | 创建时固定为 1，供任务快照与发布审计使用 |
| `updated_by` | `varchar(128)` | 否 | 创建配置的主体标识 |
| `created_at` | `timestamptz` | 否 | 首次持久化时间 |
| `updated_at` | `timestamptz` | 否 | 固化时间；不可变配置中与 `created_at` 同次写入 |

约束与关系：

- 主键：`(tenant_id, space_id)`
- 外键：`(tenant_id, space_id) → knowledge_space`，Space 删除时级联删除。
- `parser_selections_json` 必须是非空 JSON 对象；具体 Parser 是否已安装由应用能力目录校验。
- `chunker_provider_id` 受 `^[A-Z][A-Z0-9_]{0,63}$` CHECK 约束；数据库只校验稳定标识格式，Provider 是否安装、
  是否可用以及所选 Parser 是否满足其 `requiredParserCapabilities` 由应用层拒绝不兼容组合。
- `tokenizer_id` 受稳定标识格式约束。当前内置 `UTF8_BYTE_BUDGET` 使用 UTF-8 字节数作为
  稳定预算估算（`exactModelTokens=false`），并不是模型精确 Token 数，也不保证构成任意
  Tokenizer 的数学硬上界；应用必须把所选 TokenCounter 的 ID 和版本写入处理契约。
- Token 尺寸满足 `0 <= overlap < minimum <= target <= maximum <= 65536`；最小值是软目标，
  最大值是所有 Provider 都不能突破的硬边界，Overlap 不得跨越结构硬边界。
- `chunker_provider_config_json` 必须是 JSON Object。`STRUCTURAL` 使用空对象；
  `SEMANTIC_REFINEMENT` 当前 canonical Schema 包含 `embeddingProfileId`、
  `splitSimilarityThreshold`、`mergeSimilarityThreshold` 和 `contextSlices`，具体取值和
  字段完备性由应用/Provider 的强类型配置校验，存储层不复制业务规则。
- `parser_contracts_json` 必须是非空 JSON Object；所有合同字段均非空，
  `processing_contract_fingerprint` 必须是小写 64 位 SHA-256。应用读取时会根据完整组件材料
  重新计算总指纹，防止损坏或伪造的合同进入执行。
- 五个 cleaning 字段都受 CHECK 约束，只允许 `KEEP/REMOVE/METADATA_ONLY`；默认策略由
  创建页面从能力目录取得并在创建 Space 时完整写入，数据库不猜测业务默认值。清洗动作只有在 Parser 明确输出
  对应文档角色时才生效。
- 隐藏内容属于服务端安全边界，不作为可配置 cleaning 字段，也不能通过 `KEEP` 恢复。
- 配置行没有更新入口；正式处理语义变化时创建新 Space 并重新摄取，旧 Space 不生成影子配置或索引代际。
- 测试广场可以按单次请求覆盖配置，但只把完整有效配置固化到 Run 快照，不能更新本表。
- 发布事务会锁定活动 Space，并要求固定版本 1 与任务记录的
  `processing_contract_fingerprint` 同时匹配；不匹配时整个知识写事务回滚。该检查只防御
  缺失、损坏或实现漂移，不构成配置更新机制。

---

## 二、Connector 与来源同步

### `connector_instance` — 连接器实例

表示某个 Space 中的一个外部知识来源，例如 API Upload 或 Obsidian Vault。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `varchar(128)` | 否 | 连接器稳定标识，例如 `api-upload:{spaceId}` |
| `space_id` | `varchar(64)` | 否 | 目标知识空间 |
| `connector_type` | `varchar(64)` | 否 | Provider 类型，例如 `API`、`OBSIDIAN` |
| `display_name` | `varchar(256)` | 否 | 展示名称 |
| `config_json` | `jsonb` | 否 / `{}` | Provider 配置；只能存非敏感或已妥善保护的数据 |
| `status` | `varchar(32)` | 否 | `ACTIVE`、`PAUSED`、`ERROR`、`DELETED` |
| `version` | `bigint` | 否 / `0` | 配置乐观锁版本 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与关系：

- 主键：`(tenant_id, id)`
- 唯一约束：`(tenant_id, id, space_id)`，供空间绑定的复合外键引用。
- 外键：`(tenant_id, space_id) → knowledge_space`
- `knowledge_document` 的来源身份必须绑定同一个 Connector 和 Space。

### `connector_checkpoint` — 同步游标

每个 Connector 一条持久化检查点，用于分页、增量同步和进程恢复。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `connector_id` | `varchar(128)` | 否 | 连接器标识 |
| `cursor_json` | `jsonb` | 否 / `{}` | Provider 定义的下一页或增量游标 |
| `snapshot_id` | `uuid` | 是 | 当前完整快照标识；没有快照时为空 |
| `version` | `bigint` | 否 / `0` | 检查点版本 |
| `updated_at` | `timestamptz` | 否 | 最后保存时间 |

约束与关系：

- 主键：`(tenant_id, connector_id)`
- 外键：`(tenant_id, connector_id) → connector_instance`，Connector 删除时级联删除。

### `connector_sync_run` — 同步运行

一次异步 Connector 同步的持久化状态，同时承担 single-flight、租约、fencing 和进程恢复。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `uuid` | 否 | Run 主键 |
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `connector_id` | `varchar(128)` | 否 | 被执行的连接器 |
| `snapshot_id` | `uuid` | 否 | 本次完整快照标识 |
| `status` | `varchar(32)` | 否 | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` |
| `records_seen` | `bigint` | 否 / `0` | 已观察记录数 |
| `records_changed` | `bigint` | 否 / `0` | 发生知识变化的记录数 |
| `records_deleted` | `bigint` | 否 / `0` | 完整快照对账后归档的记录数 |
| `error_code` | `varchar(128)` | 是 | 稳定错误码，不保存敏感异常正文 |
| `principal_json` | `jsonb` | 否 | 启动同步的调用者权限快照；V15 后无数据库默认值 |
| `lease_owner` | `varchar(160)` | 是 | 当前 Worker 标识；仅 RUNNING 时存在 |
| `lease_token` | `bigint` | 否 / `0` | 每次 claim 单调递增的 fencing token |
| `lease_until` | `timestamptz` | 是 | 当前租约到期时间；仅 RUNNING 时存在 |
| `snapshot_restart_token` | `bigint` | 否 / `0` | 最近一次清空 staging/checkpoint 的 lease token |
| `started_at` | `timestamptz` | 否 | Run 创建/开始时间 |
| `completed_at` | `timestamptz` | 是 | 终态完成时间 |

约束与索引：

- 主键：`id`
- 外键：`(tenant_id, connector_id) → connector_instance`
- 计数必须大于等于 0。
- RUNNING 时 `lease_owner`、`lease_until` 必须非空；非 RUNNING 时必须为空。
- `0 <= snapshot_restart_token <= lease_token`。
- 部分唯一索引：同一 `(tenant_id, connector_id)` 最多一个 `PENDING/RUNNING` Run。
- Claim 索引：`(status, lease_until, started_at)`，只覆盖 `PENDING/RUNNING`。
- 历史索引：`(tenant_id, connector_id, started_at DESC)`。

### `connector_manifest` — 权威来源清单

保存上一次成功完整扫描后确认存在的来源对象。它用于识别“上一轮存在、本轮完整扫描中消失”的文档，不保存文档正文。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `connector_id` | `varchar(128)` | 否 | 来源连接器 |
| `space_id` | `varchar(64)` | 否 | 目标空间 |
| `external_id` | `varchar(512)` | 否 | 来源系统内稳定记录标识 |
| `document_id` | `uuid` | 否 | 对应稳定 Document |
| `content_hash` | `varchar(128)` | 否 | 上次成功快照中的规范化内容哈希 |
| `source_uri` | `text` | 否 | 来源 URI |
| `updated_at` | `timestamptz` | 否 | 清单最后更新时间 |

约束与索引：

- 主键：`(tenant_id, connector_id, external_id)`
- Connector 和 Document 都使用包含 `space_id` 的复合外键，避免跨空间清单。
- Connector 或 Document 删除时级联删除清单行。
- 索引：`ix_connector_manifest_document(tenant_id, document_id)`。

### `connector_snapshot_manifest` — 本轮暂存清单

保存某次 Run 在扫描过程中已经看到的记录。只有完整扫描成功后，它才会与 `connector_manifest` 原子对账并提升为新的权威清单。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `connector_id` | `varchar(128)` | 否 | 来源连接器 |
| `space_id` | `varchar(64)` | 否 | 目标空间 |
| `run_id` | `uuid` | 否 | 所属同步 Run |
| `snapshot_id` | `uuid` | 否 | 所属完整快照 |
| `external_id` | `varchar(512)` | 否 | 本轮观察到的来源记录标识 |
| `document_id` | `uuid` | 否 | 已摄入的 Document |
| `content_hash` | `varchar(128)` | 否 | 本轮内容哈希 |
| `source_uri` | `text` | 否 | 来源 URI |
| `observed_at` | `timestamptz` | 否 | 本轮观察时间 |

约束与索引：

- 主键：`(tenant_id, connector_id, snapshot_id, external_id)`
- 外键：`run_id → connector_sync_run.id`，Run 删除时级联删除。
- Connector 和 Document 复合外键都绑定同一个 Space。
- 索引：`ix_connector_snapshot_manifest_run(run_id)`。

---

## 三、知识事实与原始文件

### `knowledge_document` — 稳定文档聚合

跨内容更新和 Parser 升级保持稳定的文档身份。它保存治理信息及当前活动 Revision，而不是直接保存完整原文。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Document 内部标识 |
| `space_id` | `varchar(64)` | 否 | 所属知识空间 |
| `connector_id` | `varchar(128)` | 否 | 来源 Connector |
| `external_id` | `varchar(512)` | 否 | 来源系统内稳定业务键 |
| `source_type` | `varchar(32)` | 否 | 来源类型，例如 API、OBSIDIAN |
| `source_uri` | `text` | 否 | 可审计或跳转的来源 URI |
| `title` | `varchar(512)` | 否 | 当前文档标题 |
| `status` | `varchar(32)` | 否 | `DRAFT`、`ACTIVE`、`DEPRECATED`、`ARCHIVED`、`DELETED` |
| `authority` | `smallint` | 否 | 权威等级，范围 0～100 |
| `metadata_json` | `jsonb` | 否 / `{}` | 受治理的非敏感扩展元数据 |
| `active_revision_id` | `uuid` | 是 | 当前生产 Revision；必须属于本 Document |
| `version` | `bigint` | 否 / `0` | 生命周期和元数据更新的乐观锁版本 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与索引：

- 主键：`(tenant_id, id)`
- 来源唯一键：`(tenant_id, space_id, connector_id, external_id)`
- 空间身份唯一约束：`(tenant_id, id, space_id)`，供 Chunk、Job、Manifest 复合外键引用。
- `active_revision_id` 使用可延迟复合外键保证 Revision 属于本 Document。
- Connector 复合外键保证 Connector 与 Document 位于同一 Space。
- 索引：`(tenant_id, space_id, status)`。
- 活动 Revision 查询索引：`(tenant_id, space_id, status, active_revision_id)`。

状态语义：

- `ACTIVE`：可参与当前检索。
- `ARCHIVED`：可恢复的逻辑下线，历史仍保留。
- `DELETED`：治理 tombstone；普通摄入不得自动复活。
- `DRAFT/DEPRECATED`：领域预留状态，是否可见由应用策略控制。

### `document_revision` — 不可变文档修订

表示某个 Document 在特定内容和处理契约下产生的一次不可变结果。Parser、Chunker 或预算变化，即使原始字节相同，也应产生不同 Revision。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Revision 标识 |
| `document_id` | `uuid` | 否 | 所属 Document |
| `revision_number` | `bigint` | 否 | Document 内从 1 开始的展示序号 |
| `content_hash` | `varchar(128)` | 否 | 规范化内容哈希 |
| `media_type` | `varchar(128)` | 否 | 规范化媒体类型 |
| `language` | `varchar(32)` | 否 | 规范化 BCP 47 语言标签 |
| `parser_version` | `varchar(64)` | 否 | Parser、Chunker 与预算组成的处理契约版本 |
| `object_uri` | `text` | 是 | 历史兼容对象 URI；新原件目录使用 `document_source_object` |
| `created_at` | `timestamptz` | 否 | 修订创建时间 |

约束与关系：

- 主键：`(tenant_id, id)`
- Revision 序号唯一：`(tenant_id, document_id, revision_number)`
- 完整处理指纹唯一：`(tenant_id, document_id, content_hash, media_type, language, parser_version)`
- V15 增加 `(tenant_id, id, document_id)` 唯一约束，供所有者复合外键使用。
- 外键：所属 Document 删除时级联删除 Revision。
- `revision_number > 0`。

### `knowledge_element` — 文档结构元素

Parser 的结构化输出，例如标题、段落、列表、表格或代码块。Element 属于某个 Revision；它是 Chunk 的来源结构，不是单独的发布状态。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Element 标识 |
| `revision_id` | `uuid` | 否 | 所属 Revision |
| `parent_id` | `uuid` | 是 | 父 Element；根元素为空 |
| `element_type` | `varchar(32)` | 否 | Parser 识别的元素类型 |
| `ordinal` | `integer` | 否 | Revision 内源文顺序，必须大于等于 0 |
| `section_path_json` | `jsonb` | 否 / `[]` | 从根标题到当前元素的章节路径数组 |
| `content` | `text` | 否 | 结构元素正文 |
| `attributes_json` | `jsonb` | 否 / `{}` | Parser 产生的页码等非敏感属性 |

约束与索引：

- 主键：`(tenant_id, id)`
- V15 唯一约束：`(tenant_id, id, revision_id)`
- `parent_id` 复合外键保证父子 Element 属于同一个 Revision。
- Revision 或父 Element 删除时级联删除。
- 索引：`(tenant_id, revision_id, ordinal)`。

### `knowledge_chunk` — 检索切片

由 Revision 的 Elements 切分或合并而成，是关键词、向量、精排和 Evidence 的基本检索单元。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Chunk 标识 |
| `space_id` | `varchar(64)` | 否 | 所属空间，便于检索前过滤 |
| `document_id` | `uuid` | 否 | 所属 Document |
| `revision_id` | `uuid` | 否 | 所属 Revision |
| `ordinal` | `integer` | 否 | Revision 内 Chunk 的全局 0-based 顺序 |
| `section_path_json` | `jsonb` | 否 / `[]` | Chunk 所在章节路径 |
| `element_ids_json` | `jsonb` | 否 / `[]` | 构成该 Chunk 的 Element ID 数组 |
| `content` | `text` | 否 | 原始 Chunk 正文；用于关键词检索、Evidence 展示和引用 |
| `contextual_text` | `text` | 否 | 标题路径等上下文与原始正文拼接后的语义文本；当前用于向量化，不替换原文。Reranker 会从候选标题、章节和正文单独构造有界输入 |
| `content_hash` | `varchar(128)` | 否 | Chunk 正文哈希 |
| `source_spans_json` | `jsonb` | 否 / `[]` | `ChunkSourceSpan[]`：来源 `elementId`、元素内 `[startOffset,endOffset)` 与可选页码；用于高亮。新 Chunk 最多 128 条，历史行可为空数组 |
| `metadata_json` | `jsonb` | 否 / `{}` | 用于过滤或展示的 Chunk 元数据 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `search_vector` | `tsvector` | 生成列 | `to_tsvector('simple', content)` 的持久化生成结果 |

约束与索引：

- 主键：`(tenant_id, id)`
- 唯一顺序：`(tenant_id, revision_id, ordinal)`
- V15 复合外键保证 Revision 属于同一个 Document。
- Document 或 Revision 删除时级联删除 Chunk。
- `contextual_text` 必须非空；`source_spans_json` 必须是 JSON 数组，元素归属与
  偏移上限由写入批次结合对应 Element 正文校验。
- 文档顺序索引：`(tenant_id, document_id, revision_id, ordinal)`。
- GIN 全文索引：`ix_knowledge_chunk_search_vector(search_vector)`。

### `document_source_object` — 原文件目录

记录某个 Revision 对应的原始二进制对象元数据。文件字节保存在 MinIO；PostgreSQL 只保存逻辑 `storage_id` 和完整性信息，不保存物理 Bucket/Key。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `revision_id` | `uuid` | 否 | 对应 Revision；一 Revision 最多一个原文件 |
| `storage_id` | `varchar(256)` | 否 | ObjectStorage 中的逻辑对象标识 |
| `original_file_name` | `varchar(512)` | 否 | 上传时原文件名，仅用于展示与下载 |
| `media_type` | `varchar(128)` | 否 | 原文件媒体类型 |
| `content_length` | `bigint` | 否 | 原文件字节数，必须大于等于 0 |
| `checksum_sha256` | `char(64)` | 否 | 64 位小写十六进制 SHA-256 |
| `stored_at` | `timestamptz` | 否 | 对象保存时间 |

约束与关系：

- 主键：`(tenant_id, revision_id)`
- 唯一约束：`(tenant_id, storage_id)`
- Revision 删除时级联删除目录记录；对象存储的物理删除由应用补偿或清理流程负责。

---

## 四、索引 Generation 与异构投影

### `index_generation` — 索引代际

描述一个 Space 的一套不可变索引配置。Embedding 模型或处理契约升级时，可以新建 Generation、重建并切换，而不是原地混用不同向量空间。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Generation 标识 |
| `space_id` | `varchar(64)` | 否 | 所属空间 |
| `status` | `varchar(32)` | 否 | `BUILDING`、`READY`、`ACTIVE`、`FAILED`、`RETIRED` |
| `embedding_provider` | `varchar(64)` | 否 | Embedding Provider 标识 |
| `embedding_model` | `varchar(128)` | 否 | 模型名称 |
| `embedding_dimensions` | `integer` | 否 | 向量维度，必须大于 0 |
| `normalizer_version` | `varchar(64)` | 否 | 部署级来源规范化/清洗与全量 Parser Registry 契约的短指纹；不是单篇文档的 Parser 版本 |
| `chunker_version` | `varchar(64)` | 否 | 当前部署 Chunker 完整契约（算法与参数）的短指纹 |
| `configuration_hash` | `varchar(128)` | 否 | 完整索引配置哈希 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `published_at` | `timestamptz` | 是 | 激活/发布时间 |
| `retired_at` | `timestamptz` | 是 | 退役时间 |

约束与索引：

- 主键：`(tenant_id, id)`
- 外键：所属 Space。
- 部分唯一索引保证一个 Space 最多一个 `ACTIVE` Generation。

### `document_index_projection` — 文档投影结果摘要

记录某个 Generation 下，一个 Document 的当前 Revision 在 KEYWORD、VECTOR、GRAPH 三个通道中的结果状态。

它不是任务队列：本表回答“结果是否就绪”，`projection_job` 回答“任务如何执行和重试”。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `generation_id` | `uuid` | 否 | 所属索引 Generation |
| `document_id` | `uuid` | 否 | 被投影 Document |
| `revision_id` | `uuid` | 否 | 被投影 Revision；必须属于该 Document |
| `keyword_status` | `varchar(32)` | 否 | `PENDING`、`SUCCEEDED`、`FAILED`、`SKIPPED` |
| `vector_status` | `varchar(32)` | 否 | `PENDING`、`SUCCEEDED`、`FAILED`、`SKIPPED` |
| `graph_status` | `varchar(32)` | 否 | `PENDING`、`SUCCEEDED`、`FAILED`、`SKIPPED` |
| `updated_at` | `timestamptz` | 否 | 状态更新时间 |

约束与关系：

- 主键：`(tenant_id, generation_id, document_id)`
- Generation 或 Document 删除时级联删除。
- V15 复合外键保证 Revision 属于该 Document。

### `projection_job` — 异步投影任务

与知识事实在同一个 PostgreSQL 事务中产生的可靠任务表。Worker 以至少一次语义将 Revision 投影到 Elasticsearch、Milvus 或 Neo4j。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `uuid` | 否 | Job 主键 |
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `space_id` | `varchar(64)` | 否 | 所属空间 |
| `document_id` | `uuid` | 否 | 所属 Document |
| `revision_id` | `uuid` | 否 | 待投影 Revision |
| `projection_type` | `varchar(32)` | 否 | `VECTOR`、`KEYWORD`、`GRAPH` |
| `status` | `varchar(32)` | 否 | `PENDING`、`RUNNING`、`RETRY`、`SUCCEEDED`、`DEAD` |
| `attempt_count` | `integer` | 否 / `0` | 已执行次数 |
| `available_at` | `timestamptz` | 否 | 下次可领取时间，用于退避重试 |
| `lease_owner` | `varchar(128)` | 是 | 当前 Worker；仅 RUNNING 时存在 |
| `lease_until` | `timestamptz` | 是 | 租约到期时间；仅 RUNNING 时存在 |
| `lease_token` | `bigint` | 否 / `0` | 每次 claim 单调递增的 fencing token |
| `requeue_requested` | `boolean` | 否 / `false` | RUNNING 期间来源又变化，结束后需重新排队 |
| `last_error_code` | `varchar(128)` | 是 | 最近稳定错误码 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 状态更新时间 |
| `completed_at` | `timestamptz` | 是 | 成功或 DEAD 时间 |

约束与索引：

- 主键：`id`
- 幂等唯一键：`(tenant_id, revision_id, projection_type)`
- Document、Space、Revision 使用复合外键保证归属一致；Document/Revision 删除时级联删除。
- RUNNING 时租约字段必须非空，其他状态必须为空。
- Claim 部分索引：`(status, available_at, created_at)`，覆盖 `PENDING/RETRY/RUNNING`。
- 文档任务索引：`(tenant_id, document_id, created_at DESC)`。

---

## 五、Wiki / 编译知识页

### `knowledge_page` — 知识页聚合

空间内以 `slug` 稳定标识的编译知识页。`latest_revision_id` 指向最新草稿，`active_revision_id` 指向当前对 Agent 发布的版本，两者可以不同。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Page 内部标识 |
| `space_id` | `varchar(64)` | 否 | 所属知识空间 |
| `slug` | `varchar(256)` | 否 | 空间内稳定、可读的页面标识 |
| `title` | `varchar(512)` | 否 | 当前页面标题 |
| `status` | `varchar(32)` | 否 | `DRAFT`、`IN_REVIEW`、`PUBLISHED`、`ARCHIVED` |
| `latest_revision_id` | `uuid` | 是 | 最新生成或编辑的 Page Revision |
| `active_revision_id` | `uuid` | 是 | 当前已发布 Page Revision |
| `version` | `bigint` | 否 / `0` | 页面聚合乐观锁版本 |
| `created_at` | `timestamptz` | 否 | 创建时间 |
| `updated_at` | `timestamptz` | 否 | 最后更新时间 |

约束与索引：

- 主键：`(tenant_id, id)`
- 空间内 Slug 唯一：`(tenant_id, space_id, slug)`
- `PUBLISHED` 状态必须具有 `active_revision_id`。
- `latest_revision_id`、`active_revision_id` 使用可延迟复合外键保证 Revision 属于本 Page。
- 索引：`(tenant_id, space_id, status, updated_at DESC)`。

生命周期说明：

- `DRAFT`：草稿状态。
- `IN_REVIEW`：等待审核。
- `PUBLISHED`：`active_revision_id` 对检索和 Agent 可见。
- `ARCHIVED`：逻辑下线，不删除历史 Revision 和审核记录。

### `knowledge_page_revision` — 不可变知识页修订

知识编译器根据一个或多个 Document/Revision/Chunk 生成的不可变 Markdown 页面版本。来源快照保存在 `sources_json`，用于证明页面内容来自哪些企业知识。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Page Revision 标识 |
| `page_id` | `uuid` | 否 | 所属 Page |
| `revision_number` | `bigint` | 否 | Page 内从 1 开始的展示序号 |
| `summary` | `text` | 否 | 页面摘要 |
| `markdown` | `text` | 否 | 编译后的页面正文 |
| `sources_json` | `jsonb` | 否 | 非空来源引用数组 |
| `content_hash` | `varchar(128)` | 否 | 页面正文哈希 |
| `compiler_version` | `varchar(128)` | 否 | 编译器和编译策略版本 |
| `generated_by` | `varchar(128)` | 否 | 生成该页面的模型或 Provider 标识 |
| `created_by` | `varchar(128)` | 否 | 发起编译的主体 ID |
| `created_at` | `timestamptz` | 否 | 修订创建时间 |

约束与索引：

- 主键：`(tenant_id, id)`
- Revision 序号唯一：`(tenant_id, page_id, revision_number)`
- 编译幂等键：`(tenant_id, page_id, content_hash, compiler_version)`
- V15 增加 `(tenant_id, id, page_id)` 唯一约束，供 Page 和 Review Event 的所有者外键引用。
- Page 删除时级联删除 Revision。
- `revision_number > 0`。
- `sources_json` 必须是至少包含一个元素的 JSON 数组。
- GIN 索引：`sources_json jsonb_path_ops`，用于按来源 Revision 查询页面。

`sources_json` 中的每项由 Wiki 编译 SPI 定义，当前语义包含来源 Document、Revision、Chunk、章节、哈希和权威等级。它是编译时的不可变来源快照，不应只保存一段无法验证的自由文本。

### `knowledge_page_review_event` — 页面审核事件

记录 Page 每次审核状态变更以及状态变更所针对的 Revision。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `uuid` | 否 | 事件主键 |
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `page_id` | `uuid` | 否 | 被审核 Page |
| `revision_id` | `uuid` | 否 | 本次状态变化针对的 Page Revision |
| `from_status` | `varchar(32)` | 否 | 变化前状态 |
| `to_status` | `varchar(32)` | 否 | 变化后状态 |
| `actor_id` | `varchar(128)` | 否 | 执行审核动作的主体 ID |
| `created_at` | `timestamptz` | 否 | 事件时间 |

约束与关系：

- 主键：`id`
- `from_status`、`to_status` 均限制为 `DRAFT/IN_REVIEW/PUBLISHED/ARCHIVED`。
- Page 删除时事件级联删除。
- V15 复合外键保证 `revision_id` 属于同一个 `page_id`。

---

## 六、检索 Trace 与变更审计

### `retrieval_trace` — 检索 Trace 摘要

保存一次 Knowledge Query 的总体观测信息。为避免泄露企业问题正文，只保存 `query_hash`，不保存原始 Query、Chunk 正文或模型响应。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `uuid` | 否 | Trace 主键 |
| `request_id` | `uuid` | 否 | API 请求关联 ID；全表唯一 |
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `principal_id` | `varchar(128)` | 否 | 发起检索的主体 ID |
| `query_hash` | `varchar(128)` | 否 | 原始 Query 的不可逆摘要 |
| `total_duration_ms` | `bigint` | 否 | 检索总耗时，单位毫秒 |
| `result_count` | `integer` | 否 | 最终 Evidence 数量 |
| `created_at` | `timestamptz` | 否 | Trace 创建时间 |

约束与索引：

- 主键：`id`
- 唯一约束：`request_id`
- 外键：`tenant_id → knowledge_tenant.id`
- 耗时和结果数量必须大于等于 0。
- 索引：`(tenant_id, created_at DESC)`，用于租户内按时间浏览。

`principal_id` 没有直接外键到 `knowledge_principal`，这样可以按独立保留策略保存历史 Trace，即使主体治理记录发生变化也不破坏 Trace。

### `retrieval_trace_step` — 检索步骤

保存一个 Trace 内按顺序发生的 Query Analyze、Retriever、Fusion、Rerank、Evidence Build 等步骤。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `trace_id` | `uuid` | 否 | 所属 Trace |
| `ordinal` | `integer` | 否 | Trace 内 0-based 步骤顺序 |
| `step_name` | `varchar(64)` | 否 | 稳定步骤名称 |
| `duration_ms` | `bigint` | 否 | 步骤耗时，单位毫秒 |
| `input_count` | `integer` | 否 | 进入步骤的候选数量 |
| `output_count` | `integer` | 否 | 离开步骤的候选数量 |
| `status` | `varchar(32)` | 否 | Runtime 写入的步骤状态；数据库不限定枚举 |

约束与关系：

- 主键：`(trace_id, ordinal)`
- 外键：`trace_id → retrieval_trace.id`，Trace 删除时级联删除。
- ordinal、耗时、输入数和输出数必须大于等于 0。

### `mutation_audit_event` — 控制面变更审计

记录经过认证的 `POST/PUT/PATCH/DELETE` 请求结果。审计只保存路由模板和动作，不保存请求正文、Token、原始 Query 或具体敏感 URL。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `id` | `uuid` | 否 | 审计事件主键 |
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `principal_id` | `varchar(128)` | 否 | 操作主体 |
| `request_id` | `uuid` | 否 | API 请求关联 ID |
| `http_method` | `varchar(16)` | 否 | `POST`、`PUT`、`PATCH`、`DELETE` |
| `route_pattern` | `varchar(256)` | 否 | 脱敏路由模板，如 `/documents/{documentId}` |
| `action` | `varchar(320)` | 否 | 稳定业务动作名称 |
| `response_status` | `integer` | 否 | HTTP 状态码，范围 100～599 |
| `outcome` | `varchar(16)` | 否 | `SUCCEEDED` 或 `FAILED` |
| `duration_ms` | `bigint` | 否 | 请求耗时，单位毫秒 |
| `created_at` | `timestamptz` | 否 | 事件时间 |

约束与索引：

- 主键：`id`
- 外键：`(tenant_id, principal_id) → knowledge_principal`
- `duration_ms >= 0`。
- 时间分页索引：`(tenant_id, created_at DESC, id DESC)`。
- 请求关联索引：`(tenant_id, request_id)`。

---

## 七、Retrieval 评测

### `evaluation_dataset` — 评测数据集

一组可版本化的黄金 Retrieval Cases。相同名称可以存在多个版本，但同租户同名称同版本只能有一个。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Dataset 标识 |
| `name` | `varchar(256)` | 否 | 数据集名称 |
| `description` | `text` | 否 / `''` | 数据集说明 |
| `version` | `bigint` | 否 | 数据集版本，必须大于 0 |
| `status` | `varchar(32)` | 否 | `DRAFT`、`ACTIVE`、`ARCHIVED` |
| `created_at` | `timestamptz` | 否 | 创建时间 |

约束与关系：

- 主键：`(tenant_id, id)`
- 唯一约束：`(tenant_id, name, version)`
- Dataset 删除时 Case 级联删除；Run 按历史保留要求不配置级联删除。

### `evaluation_case` — 评测用例

一条黄金查询及其期望 Document/Chunk。当前主要用于计算 Hit、Recall@K、MRR 和 nDCG@K。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Case 标识 |
| `dataset_id` | `uuid` | 否 | 所属 Dataset |
| `query_text` | `text` | 否 | 测试 Query 正文 |
| `space_ids_json` | `jsonb` | 否 / `[]` | Query 限定的 Space ID 数组 |
| `expected_documents_json` | `jsonb` | 否 / `[]` | 期望召回的 Document ID 数组 |
| `expected_chunks_json` | `jsonb` | 否 / `[]` | 期望召回的 Chunk ID 数组 |
| `top_k` | `integer` | 否 / `8` | 本 Case 的 K，范围 1～100 |
| `labels_json` | `jsonb` | 否 / `{}` | 业务标签、难度或分组等非敏感元数据 |
| `created_at` | `timestamptz` | 否 | 创建时间 |

约束与关系：

- 主键：`(tenant_id, id)`
- 外键：`(tenant_id, dataset_id) → evaluation_dataset`，Dataset 删除时级联删除。
- 数据库检查 `top_k BETWEEN 1 AND 100`。
- 数据库没有强制两个 expected 数组至少一个非空；该业务约束由 API/Application 层执行。

### `evaluation_run` — 评测运行

一次异步执行的数据集评测。Run 固化启动者权限与 Case ID 快照，并使用租约和 fencing token 支持进程重启恢复。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `id` | `uuid` | 否 | Run 标识 |
| `dataset_id` | `uuid` | 否 | 被执行 Dataset |
| `generation_id` | `uuid` | 是 | 可选索引 Generation；空表示使用当前运行配置 |
| `status` | `varchar(32)` | 否 | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` |
| `configuration_json` | `jsonb` | 否 | 本次 Run 的检索配置快照 |
| `metrics_json` | `jsonb` | 是 | 终态汇总指标 |
| `requested_by` | `varchar(128)` | 否 / `'system'` | 发起 Run 的主体 ID，供展示和历史兼容 |
| `principal_json` | `jsonb` | 否 | 发起者完整授权上下文快照；V15 后无默认值 |
| `case_ids_json` | `jsonb` | 否 | Run 启动时不可变 Case ID 数组；V15 后无默认值 |
| `case_count` | `integer` | 否 / `0` | Case 总数 |
| `failed_case_count` | `integer` | 否 / `0` | 失败 Case 数 |
| `error_code` | `varchar(128)` | 是 | Run 级稳定错误码 |
| `lease_owner` | `varchar(160)` | 是 | 当前 Worker；仅 RUNNING 时存在 |
| `lease_token` | `bigint` | 否 / `0` | 单调递增 fencing token |
| `lease_until` | `timestamptz` | 是 | 当前租约到期时间；仅 RUNNING 时存在 |
| `started_at` | `timestamptz` | 否 | Run 创建/开始时间 |
| `completed_at` | `timestamptz` | 是 | 终态完成时间 |

约束与索引：

- 主键：`(tenant_id, id)`
- 外键：Dataset；可选 Generation。
- `case_count >= 0` 且 `0 <= failed_case_count <= case_count`。
- RUNNING 时 `lease_owner`、`lease_until` 必须非空；非 RUNNING 时必须为空。
- Claim 部分索引：`(status, lease_until, started_at)`，只覆盖 `PENDING/RUNNING`。

为什么要同时保存 `requested_by` 和 `principal_json`：

- `requested_by` 是便于管理页面展示的稳定主体 ID，也用于兼容历史数据。
- `principal_json` 是恢复执行所需的完整权限上下文；恢复 Worker 不能擅自提升为管理员。

### `evaluation_case_result` — 逐 Case 结果

记录一个 Run 对某个 Case 的最终指标。主键确保同一个 Run 和 Case 只产生一份权威结果。

| 字段 | PostgreSQL 类型 | 可空/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | `varchar(64)` | 否 | 所属租户 |
| `run_id` | `uuid` | 否 | 所属 Run |
| `case_id` | `uuid` | 否 | 被执行 Case |
| `trace_id` | `uuid` | 是 | 对应检索 Trace；当前不设数据库外键 |
| `status` | `varchar(32)` | 否 | `SUCCEEDED` 或 `FAILED` |
| `hit` | `boolean` | 否 | 是否命中至少一个期望目标 |
| `recall_at_k` | `double precision` | 否 | Recall@K，范围 0～1 |
| `reciprocal_rank` | `double precision` | 否 | Reciprocal Rank，范围 0～1 |
| `ndcg_at_k` | `double precision` | 否 | nDCG@K，范围 0～1 |
| `result_count` | `integer` | 否 | 实际召回结果数 |
| `duration_ms` | `bigint` | 否 | Case 执行耗时，单位毫秒 |
| `error_code` | `varchar(128)` | 是 | Case 失败时的稳定错误码 |
| `created_at` | `timestamptz` | 否 | 结果创建时间 |

约束与索引：

- 主键：`(tenant_id, run_id, case_id)`
- Run 删除时结果级联删除；Case 使用普通外键以保护历史一致性。
- 三个质量指标必须位于 0～1；结果数和耗时必须大于等于 0。
- 索引：`(tenant_id, run_id, status, case_id)`。
- `trace_id` 故意不设 FK，以允许 Trace 与评测结果采用独立的数据保留周期。

---

### `source_asset` — 抽取试验原件目录

记录多文件抽取 Run 已经保留到 OSS 的不可变原件。主键 `id` 是全局 UUID；
`(tenant_id,id)` 唯一约束用于组合外键，`(tenant_id,space_id,object_id)` 防止同一
逻辑对象重复登记。表中保存文件名、媒体类型、长度、SHA-256、逻辑 objectId 与
Adapter storageId，但 HTTP API 不返回物理存储坐标。任务失败或取消不会删除原件。

### `extraction_run` — Space 多文件抽取试验

仅支持 `mode=TEST_ONLY`，状态为
`QUEUED/RUNNING/CANCEL_REQUESTED/SUCCEEDED/FAILED/CANCELLED`。部分唯一索引保证同一
租户 Space 同时只有一个活动 Run。`config_version` 固定为创建期版本 1；
`config_snapshot_json` 保存用户配置、完整实际处理合同、本次 Normalizer 合同及总配置指纹；
后台以数据库原子领取、进程级 `worker_id` 与 `heartbeat_at` 完成简单接管，避免引入
业务 Fence/Lease Token。该任务没有正式 Document/Revision/Projection 发布语义。

### `extraction_run_item` — 逐文件抽取结果

每个 Item 引用一个 `source_asset`，记录 `STORED/PARSE/CLEAN/CHUNK/COMPLETED` 阶段、
状态、稳定错误码、Parser、实际 processorVersion 合同指纹、Element/Chunk 数以及三阶段耗时。这里只保存非敏感聚合诊断，
`diagnostics_json` 以固定契约保存 Clean 的 indexable/metadata-only 去向、原因码计数，
以及 Chunk 的结构/软边界、CUT/JOIN 建议与实际应用、硬上限断点和大小分布 16 项指标。
不保存正文、向量、模型响应或内容/边界预览。

---

## JSONB 字段契约速查

PostgreSQL 只对部分 JSON 字段做形状检查，详细结构由对应 Domain/SPI 定义。修改 JSON 契约时必须考虑历史行和异步恢复兼容性。

| 字段 | 预期形状 | 主要内容 |
|---|---|---|
| `connector_instance.config_json` | Object | Provider 配置；当前 Obsidian 含 Vault、authority 等配置 |
| `space_document_processing_config.parser_selections_json` | 非空 Object[String,String] | 规范媒体类型到稳定 Parser ID 的完整选择 |
| `space_document_processing_config.chunker_provider_config_json` | Object | Provider 专属 canonical 配置；字段由对应 Provider 强类型校验 |
| `connector_checkpoint.cursor_json` | Object | Provider 不透明分页/增量游标；空对象表示初始游标 |
| `connector_sync_run.principal_json` | Object | `principalId`、`roleIds`、`departmentIds`、`systemPrincipal` |
| `knowledge_document.metadata_json` | Object | 受治理的文档扩展元数据 |
| `knowledge_element.section_path_json` | Array[String] | Element 章节路径 |
| `knowledge_element.attributes_json` | Object | Parser 页码、结构属性等 |
| `knowledge_chunk.section_path_json` | Array[String] | Chunk 章节路径 |
| `knowledge_chunk.element_ids_json` | Array[UUID] | 构成 Chunk 的 Element ID |
| `knowledge_chunk.source_spans_json` | Array[Object] | Element 内原始文本范围和可选页码；不保存版面坐标 |
| `knowledge_chunk.metadata_json` | Object | Chunker、过滤和展示元数据 |
| `knowledge_page_revision.sources_json` | 非空 Array[Object] | 编译页面使用的来源证据快照 |
| `evaluation_case.space_ids_json` | Array[String] | Case 的 Space 范围 |
| `evaluation_case.expected_documents_json` | Array[UUID] | 期望 Document |
| `evaluation_case.expected_chunks_json` | Array[UUID] | 期望 Chunk |
| `evaluation_case.labels_json` | Object | 业务标签、分组和难度 |
| `evaluation_run.configuration_json` | Object | Run 检索参数快照 |
| `evaluation_run.metrics_json` | Object / NULL | Run 汇总指标 |
| `evaluation_run.principal_json` | Object | 恢复执行使用的权限快照 |
| `evaluation_run.case_ids_json` | Array[UUID] | 启动时不可变 Case 列表 |
| `extraction_run_item.diagnostics_json` | Object | 强类型 Clean 去向与固定 16 项 Chunk 边界诊断；不含正文、向量或模型响应 |

## 数据保留与级联语义

- PostgreSQL 是治理与发布事实源。ES、Milvus、Neo4j 中的数据可以从活动 Revision 重建。
- 删除 Tenant 没有设置全链路级联；租户销毁应走受控治理流程，不能直接执行一条 Tenant DELETE。
- Space ACL、Connector Checkpoint、Page Revision、Trace Step、Evaluation Case Result 等明确的从属数据使用 `ON DELETE CASCADE`。
- Document 删除会级联其 Revision、Element、Chunk、Projection Job 和来源目录记录；但 MinIO 对象和外部索引需要应用层补偿或清理流程。
- `ARCHIVED`、`DELETED` 通常是生命周期状态，不等于数据库物理 DELETE。
- `connector_manifest` 与 `connector_snapshot_manifest` 是删除对账清单，不保存知识正文。
- `projection_job` 是执行队列，`document_index_projection` 是 Generation 结果摘要，不要混为一张表。
- V15 的 Revision Ownership 复合外键与早期简单外键同时存在。这是有意的：简单外键保证目标存在，复合外键进一步保证目标属于正确 Document/Page。

## Flyway 迁移摘要

| 迁移 | 主要内容 |
|---|---|
| V1 | 创建租户、ACL、Connector、知识事实、索引代际、Trace 和基础评测模型 |
| V2 | 为 `knowledge_chunk` 增加 `search_vector` 生成列和 GIN 全文索引 |
| V3 | 创建可靠异步 `projection_job` |
| V4 | 完善评测 Dataset/Case/Run，增加 `evaluation_case_result` |
| V5 | 将文档来源身份收紧为 Space 级；增加 Connector/Document/Chunk/Job 空间归属外键 |
| V6 | 为 Connector Run 增加 single-flight 约束 |
| V7 | 将 Revision 唯一指纹扩展为内容、媒体类型、语言和处理版本 |
| V8 | 为投影 Job 增加 `lease_token` 和 `requeue_requested` |
| V9 | 创建 Wiki Page、Page Revision 和 Review Event |
| V10 | 为 Connector/Evaluation Run 增加 PENDING、权限快照、租约和进程恢复能力 |
| V11 | 创建 Revision 原文件目录 `document_source_object` |
| V12 | 创建 Connector 权威 Manifest 和 Snapshot Staging Manifest |
| V13 | 创建控制面变更审计表 |
| V14 | 为完整快照恢复增加幂等 `snapshot_restart_token` |
| V15 | 修复异步历史快照；增加 Document/Page Revision Ownership 复合外键；移除不安全的恢复默认身份 |
| V16 | 为 `knowledge_chunk` 增加 `contextual_text` 与 `source_spans_json`；历史 Chunk 以原始正文回填语义文本、范围保留为空数组；数据库约束语义文本非空且范围字段必须为 JSON 数组 |
| V17 | 创建最终空间文档处理配置表 `space_document_processing_config`，在 Space 创建事务中一次定义 Parser 选择、Cleaner 动作、可扩展 Chunker Provider、公共 Token 尺寸、Provider canonical JSON、固定版本和租户/空间边界；创建后不可更新 |
| V18 | 创建 `source_asset`、`extraction_run` 和 `extraction_run_item`，支持保留 OSS 原件的多文件 TEST_ONLY 抽取试验、Space 单活、简单心跳接管、取消和逐文件诊断 |

## 维护规则

1. Migration SQL 是最终事实源；README 是面向开发和验收的数据字典。
2. 新增、删除或修改表、字段、约束、枚举和索引时，必须在同一个变更中更新本文。
3. 禁止修改已经发布的历史 Migration；使用新的递增版本 Migration 演进 Schema。
4. 表中的租户边界必须通过主键、唯一约束或外键体现，不能只依赖 Java 查询条件。
5. 新的后台任务必须按副作用风险选择并发保护：正式发布任务需要 fencing/幂等；无发布能力的试验任务至少需要数据库原子领取、所有者匹配、心跳和失联恢复，不能只保存一个 `RUNNING` 状态。
6. 新的 JSONB 字段必须在对应 Domain/SPI 中有明确契约，并在本文记录预期形状。
7. 新增外部投影时，PostgreSQL 仍应只保存事实、任务和发布状态，不把厂商专属索引结构泄漏到核心知识表。
