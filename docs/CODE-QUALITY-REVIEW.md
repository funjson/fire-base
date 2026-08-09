# 代码质量与完整性复核

> 历史说明：本文记录进入本轮 Phase 1 缺陷整改前的阶段快照，不代表
> 2026-08-09 合并后源码已重新通过同一组测试。当前结论以
> [Phase 1 状态](PHASE-1-STATUS.md) 和根目录 [测试报告](../TEST-REPORT.md)
> 为准。

## 结论

当前代码已经从“可运行 RAG 内核”提升为带企业管理面的知识基础设施：

- 模块边界保持 `domain → spi → runtime → adapter → control-plane`；
- PostgreSQL、Elasticsearch、Milvus 和 GLM Provider 通过端口接入；
- 多租户、用户/角色/部门 ACL 在召回前执行；
- 管理控制台、持久化评测、Obsidian 手动同步和 Trace 查看已形成闭环；
- Maven Wrapper、直接依赖、Failsafe 绑定和文档偏差已经修正。

## 本轮修正

1. 修复 Windows Maven Wrapper 对空 PowerShell Target 的处理。
2. 补齐各模块直接依赖，避免依靠传递依赖偶然编译。
3. 将 Milvus IT 绑定到 Failsafe，避免测试文件存在但永不执行。
4. 为 Retriever 和投影失败增加不记录正文的结构化上下文日志。
5. 将检索线程池改为有界队列和调用方背压；评测、连接器使用独立有界线程池。
6. Query Analyzer 只规划当前安装的通道，避免未安装 Graph 时产生虚假降级。
7. 将 Chunk 参数配置化，移除摄取服务中的生产硬编码。
8. 增加请求关联 ID、稳定 500 错误和 Prometheus 指标。
9. 增加管理 API、评测持久化、逐 Case 失败隔离和 Trace 关联。
10. 完成 React 管理控制台，并按路由拆包。
11. 将 Obsidian 从解析库接入配置、同步 Run、Checkpoint 和统一写入链路；
    本地路径必须通过服务器白名单。

## 仍需继续的能力

以下能力没有用临时代码伪装成已完成：

| 优先级 | 能力 | 当前缺口 |
|---|---|---|
| P0 | 删除与 ACL 变更传播 | Obsidian 删除/移动尚未跨 PostgreSQL、ES、Milvus 原子对账 |
| P0 | 检索超时与熔断 | Provider 有自身超时，但 Gateway 尚无统一请求 Deadline |
| P1 | Neo4j Graph | 领域枚举有预留，尚无 Graph Schema、投影和 Retriever |
| P1 | 原始文件体系 | MinIO、PDF/DOCX/HTML 结构解析尚未接入 |
| P1 | 评测扩展 | 当前是检索指标，尚无答案忠实度、引用正确性和 LLM Judge 校准 |
| P1 | Connector 调度 | 已支持手动异步同步，尚无分布式定时调度与抢占租约 |
| P2 | 可观测导出 | 已有业务 Trace 与 Prometheus，尚无 OpenTelemetry 跨服务导出 |
| P2 | 前端自动化 | 已通过类型检查和生产构建，尚无组件测试与浏览器 E2E |
| P2 | Wiki 编译 | Knowledge Page、审核、Diff 和来源验证尚未实现 |

## 验证基线

- `.\mvnw.cmd clean verify`：12 个 Maven 模块成功；
- 单元测试：23 项成功；
- PostgreSQL Testcontainers：5 项成功，Flyway V1–V4 真实执行；
- `npm.cmd run build`：TypeScript 和 Vite 生产构建成功；
- GLM Provider：1 项真实 API 合约测试成功；
- Elasticsearch：1 项真实租户/文档范围合约测试成功；
- Milvus：2 项真实向量隔离与 GLM 语义检索测试成功；
- API 冒烟：健康检查、OIDC 登录、管理 Overview、Prometheus 均返回 HTTP 200，
  自定义 `X-Request-Id` 正确回传。

外部测试仍由环境变量显式启用；默认构建会清晰显示为 skipped，避免在缺少外部
环境时伪装成已经执行。
