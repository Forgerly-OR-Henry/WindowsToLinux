# CSV v2 接口

PHP 所有 JSON 响应带 `X-Sample-Protocol: 2`。请求为 UTF-8；业务拒绝返回 400/404/409 及 `error`。异步检查错误保存在任务 `status/error`，不伪装为同步成功报告。

| 接口                                   | 数据                                                         |
| -------------------------------------- | ------------------------------------------------------------ |
| `POST /api/datasets?name=...`          | CSV 文件流，Content-Length；返回 id/name/size/sha256         |
| `GET /api/datasets`                    | offset/limit（1..100）分页                                   |
| `GET/POST /api/templates`              | 读取模板 / `{name,rules}` 创建不可变模板                     |
| `POST /api/jobs`                       | `{datasetId,templateId,requestId}`；同键同参数只创建一次     |
| `GET /api/jobs`                        | 数据库分页：items/total/offset/limit                         |
| `GET /api/jobs/{id}`                   | 状态、尝试次数、进度、规则、汇总、历史                       |
| `GET /api/jobs/{id}/issues`            | 当前尝试问题明细分页；未完成明细仅供诊断                     |
| `POST /api/jobs/{id}/cancel` / `retry` | `{requestId}`；取消运行/排队任务；显式重试失败/取消/中断任务 |
| `GET /api/jobs/{id}/export`            | 只允许 completed；下载完整 JSON 报告                         |
| `GET /api/compare?left=...&right=...`  | 只比较同数据集成功报告；分类差额、added/resolved、原规则     |

请求标识是 8..80 个 ASCII 字母、数字、下划线或连字符。相同键用于不同参数/动作返回 409。SQLite 外键、请求唯一键、任务状态约束与事务保证身份一致。

规则结构：`{required:[列],types:{列:"number"或"date"},ranges:{列:{min,max}},enums:{列:[值]},unique:[[组合列]]}`。空对象必须编码为 `{}`，不使用 `[]`。日期严格为有效 `YYYY-MM-DD`；数字必须有限；范围含端点；枚举比较原字符串；组合去重按完整原值元组，报告首次记录号。空值跳过类型、范围和枚举，只由 required 判断。缺失的列、重复表头和列数异常使检查失败。

PHP worker → Python `POST /analyze`：文件流、Content-Length、`X-Sample-Protocol: 2`、`X-CSV-Rules: base64(UTF-8 JSON)`。Python 先将输入写入临时文件再按 CSV 记录处理，内存不保存全文件/全问题。

响应为逐行 NDJSON，每条包含 `protocolVersion:2`、从 0 连续递增的 `sequence`、`type`。严格顺序为：

- `start {columns}` 一次。
- 零到多条 `issue {row,column,code,value,detail}`，及 `progress {rows,issues}`。row 是逻辑 CSV 记录号，表头为 1，不等同于物理换行号。问题类型为 required/number/date/range/enum/duplicate。
- `end {rows,validRows,issueCount,statistics,columns}` 一次且必须为最后一条。运行中错误使用 `error {error}`，不再发送 end。

PHP 限制单条记录 64 KiB、总记录数、网络超时；每 500 条或进度边界提交 SQLite。结束时用 SQL 重新统计分类、全部问题和问题行数，再发布报告文件与 completed 状态。缺少结束、字段、数量或协议不一致均失败。取消主动关闭调用；Python 在写出时检测断开并回收临时文件。

worker 启动持有独占文件锁，将遗留 running 标记 interrupted，并删除未发布报告。不会自动重试。重试沿用 job id、attempt+1；创建与重试幂等标识在同一事务内保存。发布前中断不会对外提供成功报告，旧成功任务保持可用。
