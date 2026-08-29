# 交付与验收

> 更新日期：2026-08-23
> 交付方式：源码保留在当前工作目录，不生成新的 `dist` 源码包。

根目录现存 `dist/` 是历史忽略产物，不代表当前源码，不得用于本次验收。当前
交付状态以 [P1/P2 状态](docs/P1-P2-STATUS.md)、[测试报告](TEST-REPORT.md) 和
[API](docs/API.md) 为准。

## 当前源码交付

- Java 21 / Spring Boot 4.1 Maven 多模块；
- Keycloak/OIDC、API Audience、多租户、Principal 和空间 ACL；
- Markdown、TXT、HTML、PDF、DOCX、Obsidian 摄取，以及多文件 TEST_ONLY/INGEST；
- Parser/Cleaner、结构与双向语义 Chunk、Docling PDF Adapter、本地 HuggingFace Tokenizer；
- Space 创建时固化用户配置与服务端实际处理合同，运行时漂移拦截及只读页面展示；
- PostgreSQL V1-V18 权威事实、不可变修订、SourceAsset、配置快照与抽取任务；
- Elasticsearch BM25、Milvus/GLM Vector、Neo4j Graph、Published Wiki 检索；
- MinIO 原文件、授权下载/预览；
- Projection fencing/dirty-requeue、Connector/Evaluation 恢复和 manifest 对账；
- Evidence/Citation/Trace、低基数指标、变更审计；
- Retrieval Evaluation 与 baseline/candidate 质量门禁；
- React 管理控制台、Space 抽取测试广场、OpenAPI、Playwright 用例；
- `knowledge-agent-client` Java Client 和 `KnowledgeSearchTool`。

2026-08-11 的 P1/P2 历史范围与 2026-08-22 的数据抽取范围已分别完成本地受控验收；
2026-08-23 又完成 Space 不可变配置和实际处理合同漂移拦截的代码、数据库与页面聚焦门禁。
上述结果不外推到本轮检索、Wiki、Graph 增量，也不表示生产 HA、容量、灾备或长期稳定性
验收完成。明确延期和未支持项见 [P1/P2 状态](docs/P1-P2-STATUS.md)。

## 环境

- JDK 21
- Docker Desktop
- PowerShell
- Node/npm
- 完整 Hybrid/Graph 验收需要非空 `ZHIPU_API_KEY`
- 本机代理可使用 `127.0.0.1:7890`

## 低资源验收顺序

先执行单线程静态门禁，避免重复 `clean`：

```powershell
.\mvnw.cmd -T1 verify

Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
npm.cmd run e2e:list
Set-Location ..
```

随后只启动一次完整环境：

```powershell
docker compose --profile acceptance up -d --wait --wait-timeout 300 `
  postgres keycloak etcd minio milvus elasticsearch neo4j
docker compose --profile acceptance run --rm keycloak-config

if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw 'ZHIPU_API_KEY is required'
}
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'
$env:KNOWLEDGE_GRAPH_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_GRAPH_PROXY_PORT = '7890'

.\mvnw.cmd -T1 -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

服务已经人工启动时，按 [API](docs/API.md) 做 API 冒烟，再在
`knowledge-console` 执行 `npm.cmd run e2e`。Playwright 不自动启动服务，固定
`workers=1`，避免验收期间额外并发负载。若不下载 bundled Chromium，可在
PowerShell 中先设置 `$env:E2E_BROWSER_CHANNEL='chrome'` 复用本机 Chrome；这是
2026-08-11 历史基线的实测路径；抽取工作台最终用例见
`knowledge-console/e2e/knowledge-console.spec.ts`。

## 外部契约门禁

需单独记录：

1. PostgreSQL V1 -> V18 预发布空库重建；
2. Elasticsearch 真实投影/检索；
3. Milvus 真实投影/过滤/检索；
4. MinIO put/get/delete、保留原件与逐 Item 下载 API；
5. Neo4j 投影、活动修订清理和 traversal；
6. GLM Embedding，以及显式启用时的 Graph/Wiki 生成；
7. 固定 Docling 容器 PDF 成功与 DOCX 覆盖不足 fail-closed；
8. 本地精确 Tokenizer 的禁用/启用两态，以及目标 Embedding Serving 的独立配对门禁；
9. Space 实际处理合同的完整持久化、Run 快照往返、运行时漂移拒绝和发布最终指纹栅栏；
10. Admin/Reader/Service Principal、Connector/Evaluation 重启恢复和浏览器 E2E。

2026-08-11 已执行最终 Maven 门禁、核心真实 Adapter、API/UI 冒烟、Admin/Reader
浏览器 E2E（2/2）和 Obsidian 四步对账。详细计数与 PostgreSQL 首轮测试夹具修正
轨迹见 [测试报告](TEST-REPORT.md)。2026-08-22 又完成抽取工作台 E2E 4/4、V1～V18、
MinIO、Docling、正式 INGEST/重复/冲突和 Elasticsearch KEYWORD 对账。双实例、容量、
灾备和生产 HA 仍未验收。2026-08-23 已复验 Space 不可变配置与实际处理合同的代码、
真实 PostgreSQL 往返、知识写入完整回归 21/21、发布最终指纹栅栏和页面构建；当前套件
包含 8 个浏览器场景，新增配置与合同场景尚待专用环境整套 live Playwright 重跑。

## Docker 数据

```powershell
docker compose --profile acceptance ps
```

命名卷默认保留。除非已经确认无需恢复，不要执行 `docker compose down -v`。
本轮按授权重建了目标 PostgreSQL 的 `public` schema；MinIO 未清空，验收原件按产品保留
策略继续存在。
