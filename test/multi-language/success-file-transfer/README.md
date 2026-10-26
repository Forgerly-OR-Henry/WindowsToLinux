# 文件收发站

TypeScript 网页和 Node 网关通过 HTTP 调用 Go。Go 独占 SQLite 元数据、上传分块和不可变版本文件；Node 流式转发文件内容，核对 JSON 响应的协议和必要字段。业务规范见 [BUSINESS.md](BUSINESS.md)。

## 构建与运行

复制整个项目即可，不引用其他样例资源。需要 Go 1.24、Node 24、npm。依赖由 go.mod/go.sum 和 package-lock.json 固定。

在 `backend` 执行 `go build -mod=readonly -o file-transfer.exe .`，然后 `./file-transfer.exe`。Linux 构建去掉 `.exe`。在另一终端的 `web` 执行 `npm ci`、`npm run build`、`node dist/server.js`，浏览器打开 `http://127.0.0.1:18110`。结束后两终端 Ctrl+C。

| 组件 | 配置                                  | 默认值                                     |
| ---- | ------------------------------------- | ------------------------------------------ |
| Go   | HOST / PORT                           | 127.0.0.1 / 18111                          |
| Go   | DATA_DIR                              | 当前工作目录 data                          |
| Go   | MAX_FILE_BYTES / HTTP_TIMEOUT_SECONDS | 268435456 / 30                             |
| Node | HOST / PORT / API_URL                 | 127.0.0.1 / 18110 / http://127.0.0.1:18111 |
| Node | API_TIMEOUT_MS / MAX_API_BYTES        | 30000 / 8388608（JSON 响应限制）           |

PowerShell 示例：`$env:DATA_DIR="D:\样例 数据"`；Linux：`export DATA_DIR="/tmp/样例 数据"`。本版使用全新数据库结构，指定新的 DATA_DIR；不迁移旧版数据库。重启同一新版目录保留数据。一个数据目录由一个 Go 服务实例管理。

初始化包含“演示文档”和“数据归档”两个文件夹。可直接上传 `samples/中文 文件.txt`。所有服务有 `/healthz`；Node `/readyz` 实际访问 Go，业务仍需调用上传和下载接口验收。

## 人工完整流程

1. 在文件列表选择“演示文档”，进入上传页面，多选样本文件，等待“已校验并发布”。文件列表应显示名称、字节数、SHA-256 和版本号。
2. 再次上传同名文件，进入版本详情，两次发布显示版本 1、2。下载各版本核对原字节；切换“数据归档”不会混入前一文件夹。
3. 用 `python samples/generate.py --output "运行 数据/64 MiB.bin"` 生成大文件。上传过程中暂停，记下会话标识；刷新页面或重启 Go，重新选相同文件和文件夹、填写会话标识，继续上传。只补传未确认分块。
4. 续传时选择其他文件应显示冲突提示。取消会话后不能继续上传；历史显示 cancelled。已发布版本不能取消。
5. 停止 Go 后操作页面，应显示下游不可用；重启后正常业务继续，旧版本仍可下载。

## HTTP 契约

UTF-8 JSON，Go 响应带 `X-Sample-Protocol: 2`。Node 对协议错误、必要字段缺失、超时和异常连接返回 502。业务拒绝保持 400/404/409/422，JSON `{error: string}`。

- `GET/POST /api/folders`：创建体 `{name}`，名称唯一。
- `GET /api/files?folderId=1&q=&offset=0&limit=25`：数据库分页，limit 1..100；返回 `{items,total,offset,limit}`，只含已发布版本。
- `GET /api/files/{id}/versions`：所有历史版本，按版本号倒序。
- `POST /api/uploads`：`{folderId,name,size,sha256,chunkSize}`；chunkSize 64 KiB..4 MiB，网页使用 1 MiB。返回稳定 `id`、state、已确认 chunks、events。
- `PUT /api/uploads/{id}/chunks/{number}`：原始字节流，编号从 0 开始。长度必须准确；相同编号同内容重试返回 `duplicate:true`，不同内容返回 409。
- `GET /api/uploads/{id}`：恢复会话，包含原始文件身份与已确认分块；`GET /api/uploads?folderId=1` 返回最近 200 次历史。
- `POST /api/uploads/{id}/complete`：验证每块存在、总长度和整文件 SHA-256，返回 `{id,fileId,number,size,sha256}`。10 次并发完成仍返回同一版本；同名文件的新会话发布新版本。
- `POST /api/uploads/{id}/cancel`：取消并移除分块；重复取消幂等。
- `GET /api/versions/{id}/download`：原始字节，含 `X-Content-SHA256`。未发布会话不可下载。

下载的30秒由最终客户端按接收进展计时：网页从请求开始等待首个非空响应块，此后每收到非空响应块重新计时；连续30秒无进展则取消请求。响应头到达不算内容进展。网页收齐声明长度并核对版本SHA-256后才保存文件，截断或摘要不符会显示错误。下载可以持续超过30秒；Go的普通响应写入期限与Node的上游socket期限只在有效文件下载流中解除，连接、响应头、JSON及上传原有限制保留，客户端关闭会沿Node取消Go请求。

直接调用下载API的客户端也须实现30秒或更严格的接收停滞取消并校验长度和摘要。服务端无法观察最终客户端实际接收进展，不能用网关写入阻塞代替该判断。网页校验期间会在内存中保留文件；浏览器暂停或主线程阻塞时，计时检查需等事件循环恢复才能执行。

上传相位日志按uploadId和chunkIndex关联：Node记录实际接收字节和请求/响应事件，Go分别记录读取调用、暂存写入、同步和元数据阶段。读取调用耗时包含调用本身的等待，不能直接等同纯网络耗时；本地写入或响应返回标记也不证明最终客户端已接收。诊断字段限定为安全身份、数值、阶段和错误类型，不含请求内容、凭据或任意异常正文。

元数据提交是版本可见性的边界。启动时清理未提交的临时发布文件，把 verifying 会话恢复为 receiving；已确认分块保留。文件名不作为磁盘路径，拒绝路径分隔符、换行、NUL。

## 验收

仓库运行器：`python src/app/main/src/test/python/polyglot_runner.py verify --project file-transfer --profile standard --work <隔离目录>`；`--profile quick` 使用较小规模。标准档生成 64 MiB 文件、两版本、10 路完成竞争，执行浏览器操作、真实流中断、分块落盘与发布前杀进程、协议/字段/超时/下游异常和重启恢复。种子、规模、耗时和可采集资源记录在 report.json。

故障闸门仅用于隔离验收：`SAMPLE_FAULT_POINT=chunk-written|before-publish`、`SAMPLE_FAULT_DIR`。到达边界写入 `<point>.ready` 后等待同目录 `<point>.release`，60 秒超时报错，默认关闭。不能把闸门配置用于日常体验。

数据库、分块、版本、生成样本和构建产物不提交。运行器与产品部署验证分别记录；本轮正式64MiB ASSISTED业务、各次失败、原任务恢复和产品收尾状态见[四项复验记录](../../../docs/test/multi-language/ASSISTED-FOUR-FIXES-20261023.md)，实际完成范围以对应轮次证据为准。
