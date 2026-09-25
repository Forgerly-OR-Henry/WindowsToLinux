# CSV 检查工作台：业务与验收规范

版本：2。直接升级，无旧数据迁移；固定身份只用于演示。

## 数据与职责

datasets / templates / jobs / issues / job_events

## 状态流

queued → running → completed/failed/cancelled；启动恢复 running 为 interrupted；failed/interrupted 经显式 retry 回 queued，同 jobId 递增 attempt

## 接口

GET/POST /api/datasets、/api/templates、/api/jobs；GET /api/jobs/{id}、/issues、/export；POST /api/jobs/{id}/cancel|retry；GET /api/compare?left&right；Python POST /analyze 返回 NDJSON

## 不变量

PHP 独占 SQLite，PHP worker 独立进程。Python 接收 CSV 与模板 JSON，逐行返回 protocolVersion=2 的 issue/progress/summary，PHP 每 500 条入库。completed 前数量核对且取消不发布报告。

## 标准验收

100,000 行，required/number/date/range/enum/composite-duplicate 独立预期；并发网页可读；取消、重试、worker 中断及畸形协议

每项包含正常、业务拒绝、异常恢复三类流程；先按此规范实现，再更新 README 与实际验证记录。旧版本通过记录不代表本版通过。
