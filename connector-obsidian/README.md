# connector-obsidian

该模块将 Obsidian Vault 作为外部知识源，保留相对路径、YAML Frontmatter、标签、WikiLink 与附件引用。

连接器以内容哈希和游标支持有界分页采集，不负责文档索引和权限判断。

Control Plane 将每轮采集视为一个完整快照：分页记录先按 `snapshotId` 暂存，
只有全部分页成功且 Worker 仍持有数据库租约时，才会原子提升 manifest，并将上轮
存在但本轮缺失的文档归档。失败、崩溃或租约被接管的半轮扫描不会改变当前 manifest。
文件移动默认按“旧 externalId 归档 + 新 externalId 创建”处理。
