# Infinity Knowledge 编码规范

- 标识符使用英文，Javadoc、设计文档、模块 README 和关键设计注释使用中文。
- 所有公开类型、显式构造器和方法必须说明职责、输入输出及关键约束。
- Domain 与 SPI 禁止依赖 Spring 和基础设施 SDK。
- 使用 Java 21，编译启用 `-Xlint:all -Werror`。
- 不得记录文档正文、Chunk 正文、模型原始请求响应、JWT、密码或 API Key。
- 时间统一使用 UTC `Instant`。
- 金额之外的评分使用有限区间值对象或构造校验，禁止传播 NaN。
- 每个 Maven 模块必须有 README，说明职责、依赖方向和扩展点。
- 真实集成测试、离线单元测试和源码策略门禁必须分别报告，不能互相冒充。

