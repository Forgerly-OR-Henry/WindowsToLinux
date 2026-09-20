# 文件收发站：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

folders / uploads / chunks / files / versions / upload_events

## 状态流

receiving → verifying → completed；receiving/verifying → cancelled/failed；重启把 verifying 恢复为 receiving，保留已确认分块

## 接口

GET/POST /api/folders；GET /api/files?folderId；GET /api/files/{id}/versions；POST /api/uploads；PUT /api/uploads/{id}/chunks/{index}；GET /api/uploads/{id}；POST /api/uploads/{id}/complete|cancel；GET /api/versions/{id}/download

## 不变量

1 MiB 分块、默认最大 256 MiB 文件。会话保存 folderId/name/size/sha256/chunkSize；同编号同摘要重传返回原确认，冲突 409。发布版本由 SQLite 唯一约束保证，历史版本不可覆盖。

## 标准验收

64 MiB，两个版本；暂停重启续传；重复/冲突分块；10 个完成请求只生成一个版本；取消与下游故障

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
