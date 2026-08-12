# 交付与验收

> 更新日期：2026-08-11
> 交付方式：源码保留在当前工作目录，不生成新的 `dist` 源码包。

根目录现存 `dist/` 是历史忽略产物，不代表当前源码，不得用于本次验收。当前
交付状态以 [P1/P2 状态](docs/P1-P2-STATUS.md)、[测试报告](TEST-REPORT.md) 和
[API](docs/API.md) 为准。

## 当前源码交付

- Java 21 / Spring Boot 4.1 Maven 多模块；
- Keycloak/OIDC、API Audience、多租户、Principal 和空间 ACL；
- Markdown、TXT、HTML、PDF、DOCX、Obsidian 摄取；
- PostgreSQL V1-V15 权威事实、不可变修订、生命周期和可恢复任务；
- Elasticsearch BM25、Milvus/GLM Vector、Neo4j Graph、Published Wiki 检索；
- MinIO 原文件、授权下载/预览；
- Projection fencing/dirty-requeue、Connector/Evaluation 恢复和 manifest 对账；
- Evidence/Citation/Trace、低基数指标、变更审计；
- Retrieval Evaluation 与 baseline/candidate 质量门禁；
- React 管理控制台、OpenAPI、Playwright 用例；
- `knowledge-agent-client` Java Client 和 `KnowledgeSearchTool`。

上述范围已完成第一阶段本地受控验收；这不表示生产 HA、容量、灾备或长期稳定性
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
2026-08-11 本轮实测路径。

## 外部契约门禁

需单独记录：

1. PostgreSQL V1 -> V15 升级和全新安装；
2. Elasticsearch 真实投影/检索；
3. Milvus 真实投影/过滤/检索；
4. MinIO put/get/delete 与原文件 API；
5. Neo4j 投影、活动修订清理和 traversal；
6. GLM Embedding，以及显式启用时的 Graph/Wiki 生成；
7. Admin/Reader/Service Principal、Connector/Evaluation 重启恢复和浏览器 E2E。

2026-08-11 已执行最终 Maven 门禁、核心真实 Adapter、API/UI 冒烟、Admin/Reader
浏览器 E2E（2/2）和 Obsidian 四步对账。详细计数与 PostgreSQL 首轮测试夹具修正
轨迹见 [测试报告](TEST-REPORT.md)。双实例、容量、灾备和生产 HA 仍未验收。

## Docker 数据

```powershell
docker compose --profile acceptance ps
```

命名卷默认保留。除非已经确认无需恢复，不要执行 `docker compose down -v`。
