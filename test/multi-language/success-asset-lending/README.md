# 资产借还管理

原生 JavaScript 多页面界面经 Node 网关调用 C#。C# 独占 SQLite，借用单、资产占用、部分归还、维修、历史及幂等结果在事务内提交。业务规范见 [BUSINESS.md](BUSINESS.md)。固定身份选择用于演示，不属于身份认证。

## 构建与运行

项目可独立复制。需要 .NET SDK 10.0.300、Node 24、npm；Microsoft.Data.Sqlite 10.0.8 及传递依赖由 packages.lock.json 固定。

在 `backend` 执行 `dotnet restore --locked-mode`、`dotnet publish --no-restore -c Release -o publish`、`dotnet publish/AssetLending.dll`。另一个终端在 `web` 执行 `npm ci`、`npm run build`、`node dist/server.js`。浏览器打开 `http://127.0.0.1:18120`，结束后各终端 Ctrl+C。

C# 的 HOST/PORT 默认 127.0.0.1/18121；DATA_DIR 默认当前目录 data。Node HOST/PORT 默认 127.0.0.1/18120，API_URL 默认 `http://127.0.0.1:18121`；API_TIMEOUT_MS 默认 30000，MAX_API_BYTES 默认 8388608。SQLite 写事务等待上限 10 秒。

使用新的 DATA_DIR 初始化本版结构，不迁移旧数据库；重启保留数据。初始化两个分类和三件演示资产。PowerShell 示例：`$env:DATA_DIR="D:\资产 数据"`；Linux：`export DATA_DIR="/tmp/资产 数据"`。

## 人工完整流程

1. 查看初始化资产，登记两件不同编号的相机。按名称、分类和状态筛选，在列表勾选两件资产。
2. 进入“新建借用单”，选择申请人、期限并填写用途，保存草稿；详情中依次提交审批、切换演示经办人为陈老师、批准整单、确认领用。
3. 只勾选第一件归还，选择完好；借用单变为部分归还，第一件重新可借。第二件标记损坏并归还，借用单结清，该资产进入维修。
4. 在维修工单填写处理说明并完成维修，资产重新可借。在资产履历及借用详情查看经办人、状态操作和时间。
5. 两张单包含同一资产，先批准一张；另一张批准失败，整单其他资产也不能被部分占用。未审批不能领用，已归还项不能再次记账，维修资产不能被审批占用。
6. 借用单筛选“仅逾期”检查已领用且未完全归还的过期单。统计必须与资产状态、维修工单和借用单明细一致。

## HTTP 契约

UTF-8 JSON，C# 响应带 `X-Sample-Protocol: 2`。Node 校验协议和响应必要字段，故障返回 502；业务错误 400/404/409，返回 `{error}`。

- `GET /api/actors`：固定身份；`GET/POST /api/categories`：分类，创建体 `{name}`。
- `POST /api/assets`：`{name,categoryId,serial}`，编号唯一。`GET /api/assets?q=&status=&categoryId=0&offset=0&limit=25`：数据库分页和筛选，limit 1..100。
- `POST /api/loans`：`{assetIds,borrower,dueDate,purpose,actor,requestId}`。1..20 件不同资产，期限 YYYY-MM-DD。创建草稿不占用资产。
- `GET /api/loans?status=&borrower=&overdue=false&offset=0&limit=25`：列表；`GET /api/loans/{id}`：详情，含 items/events。
- `POST /api/loans/{id}/submit|approve|reject|checkout`：`{actor,requestId,note?}`。审批使用 BEGIN IMMEDIATE，全部占用或全部回滚。
- `POST /api/loans/{id}/return`：`{actor,requestId,items:[{assetId,condition:"good"|"damaged",note}]}`。部分归还与损坏维修在同一事务提交。
- `GET /api/maintenance`：最近 200 工单；`POST /api/maintenance/{id}/complete`：`{actor,requestId,note}`。
- `GET /api/assets/{id}/history`：资产及履历；`GET /api/stats`：资产各状态、借用单各状态、逾期及待维修数。

借用单创建和所有流转都使用 requestId。相同键和内容返回首次结果，不新增历史；同键不同内容 409。新键重复归还已归还项也返回 409。状态表和示例见 BUSINESS.md、samples/request.json。

## 验收与规模生成

`python samples/generate.py --url http://127.0.0.1:18120 --count 10000 --seed 20260919` 通过真实 API 登记确定性资产。使用独立 DATA_DIR；同一种子再次运行会因编号重复失败，不覆写现有数据。

仓库运行器：`python src/app/main/src/test/python/polyglot_runner.py verify --project asset-lending --profile standard --work <隔离目录>`。快速档为 `--profile quick`。标准档通过 API 创建 10,000 件资产，执行 10 单竞争、整单回滚、并发幂等、归还维修、审批写入途中杀进程、浏览器流程、协议错误及下游重启；保存种子、规模、耗时和资源指标。

隔离故障配置 `SAMPLE_FAULT_POINT=approval-reserved` 与 `SAMPLE_FAULT_DIR` 在事务首次占用后写入 ready 文件并等待 release 文件，60 秒超时，默认关闭。验收在此处终止 C# 并核对 SQLite 回滚，之后正常审批必须成功。

数据库、依赖目录、构建产物和验收输出不提交。Windows 实际结果见上级验证记录；Linux 实机及产品部署仍待验证。
