# 交付与验收

> 更新日期：2026-08-09  
> 当前源码直接保存在工作目录，本轮不生成新的 `dist` 分发包。根目录现存
> `dist/` 仅包含 2026-07-26 的历史忽略产物，不代表当前源码，也不得用于本轮
> 验收。当前合并状态以
> [README](README.md)、[Phase 1 状态](docs/PHASE-1-STATUS.md) 和
> [测试报告](TEST-REPORT.md) 为准；Phase 2～4 文档是历史增量快照。

## 当前交付

当前源码包含可进入最终验收的 Phase 1 企业 RAG 基线：

- Java 21 / Spring Boot 4.1 Maven 多模块；
- JWT、Keycloak、多租户、用户/角色/部门 ACL；
- Markdown 与 Obsidian 摄取；
- PostgreSQL 元数据、修订、ACL、关键词检索和 Trace；
- 智谱 `embedding-3`；
- Milvus 向量写入、语义检索和租户隔离；
- 不可混写的索引 generation、活动修订守卫和可重建投影；
- 事务 Projection Job、租约 Worker、指数退避、死信、人工重投和空间重建；
- 离线 Hit Rate、Recall@K、MRR、nDCG@K 评测内核；
- Agent API `EvidenceBundle`；
- Elasticsearch CJK/BM25、事务 KEYWORD 投影和索引内租户/ACL 过滤；
- React/TypeScript 企业管理控制台与 OIDC PKCE 登录；
- 持久化评测数据集、逐案例结果、失败隔离和 Trace 关联；
- Obsidian Vault 白名单配置、异步手动同步、Run 与 Checkpoint；
- 管理查询 API、请求关联 ID、Micrometer/Prometheus 指标；
- Keycloak API Audience、Admin/Reader、Principal 入驻和空间 ACL；
- 文档分页、ACL、投影重建、Connector Run 和 Evaluation 可视化管理。

详细范围见：

- [Phase 1 状态](docs/PHASE-1-STATUS.md)
- [总体架构](docs/ARCHITECTURE.md)
- [API 验收](docs/API.md)
- [测试用例](TEST-CASES.md)
- [架构审计](ARCHITECTURE-AUDIT.md)

## 环境

- JDK 21
- Docker Desktop
- PowerShell
- 非空环境变量 `ZHIPU_API_KEY`

## 快速验收

```powershell
docker compose --profile acceptance up -d --wait --wait-timeout 300 `
  postgres keycloak etcd minio milvus elasticsearch
docker compose --profile acceptance run --rm keycloak-config

# 使用已有密钥；未配置时应先设置，而不是把占位值提交到源码。
if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw 'ZHIPU_API_KEY is required for the acceptance profile'
}
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'

.\mvnw.cmd clean verify
.\mvnw.cmd -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

随后按 `docs/API.md` 获取本地 Token、创建知识空间、写入 Markdown 并调用 Agent 查询 API。

## 最终验收说明

```powershell
.\mvnw.cmd clean verify

Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
```

外部 PostgreSQL、Elasticsearch、Milvus 和 GLM 合约需按
[Phase 1 状态](docs/PHASE-1-STATUS.md) 单独记录。本轮未启动用户服务；当前
合并后的 Maven/Vite/浏览器验收仍待本机执行，不能用历史 Phase 报告代替。

## Docker

```powershell
docker compose --profile acceptance ps
```

命名卷默认保留数据。除非确认无需恢复，不要执行 `docker compose down -v`。
