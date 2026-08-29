---
title: 企业知识库抽取
owner: platform-team
---
# 摄取说明

中文段落用于验证多语言文本不会在清洗阶段静默丢失，并保留原始引用范围。

The English paragraph verifies that multilingual extraction preserves stable provenance.

## 操作步骤

- 上传多个文件
- 观察解析状态
- 验证来源引用

| 字段 | 含义 |
| --- | --- |
| parser | 文档解析器 |
| chunker | 分块策略 |

```java
public record JobStatus(String id, String state) {
}
```

## 长段落边界

企业知识库的长段落需要先遵守模型 Token 硬上限，再在结构允许的范围内决定软边界。语义增强可以建议减少已有软边界，也可以建议增加新的边界，但是绝不能越过表格、代码块、章节和来源范围等硬约束。每一次候选配置都应在同一份数据集上记录元素类型、清洗去向、分块大小、来源核算率、耗时与成本，从而让管理员比较基线方案和候选方案。配置只有在硬门禁全部通过并由用户主动发布后才能成为 Space 的生效版本，已发布索引在新版本投影完成前继续提供服务。
