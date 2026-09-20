# 项目任务看板

原生 TypeScript 页面使用运行时 API 地址调用 Spring Boot；Java 独占 SQLite，处理项目成员、任务依赖、审核流转、评论、乐观锁和操作记录。业务规范见 [BUSINESS.md](BUSINESS.md)。

## 独立构建与运行

需要 JDK 21、Maven 3.9、Node 24、npm。Spring Boot 3.4.0、SQLite JDBC 3.46.0.0 固定于 POM/BOM；前端使用 package-lock.json。项目可独立复制，不引用其他样例。

1. 在 backend 执行 `mvn -B -ntp package`、`java -jar target/task-board-1.0.0.jar`。
2. 在另一终端 frontend 执行 `npm ci`、`npm run build`、`npm run start -- --port 18100`。
3. 浏览器打开 `http://127.0.0.1:18100`，结束后各终端 Ctrl+C。

Java 配置 HOST（默认 127.0.0.1）、PORT（18101）、DATA_DIR（当前目录 data）、WEB_ORIGIN（http://127.0.0.1:18100）。使用新目录初始化本版数据库，不迁移旧数据；重启新版目录保留记录。

构建前可编辑 `frontend/public/runtime-config.json`，构建后编辑 `frontend/dist/runtime-config.json` 即可，无需重新编译：`{"apiBase":"http://127.0.0.1:18101","requestTimeoutMs":15000}`。超时可设 100..60000 毫秒；改变页面端口须同步 WEB_ORIGIN。

## 人工操作

初始项目“校园活动”有两名成员和两件任务，“发布活动公告”依赖“确认活动场地”；“实验室建设”的成员和任务独立。演示成员选择不代表身份认证。

1. 项目概览查看状态及负责人统计；任务列表组合筛选标题、标签、负责人、优先级、状态及逾期，按数据库分页。
2. 打开“发布活动公告”，依次开始任务、提交审核、审核完成；前置任务未完成时应明确拒绝。通过依赖链接完成“确认活动场地”，再完成公告。
3. 新建任务，填写描述、负责人、截止日期、标签和依赖编号。跨项目负责人／依赖被拒绝，直接或间接依赖环也被拒绝。
4. 对任务发表评论并查看操作历史；历史包含操作成员、内容和时间。已完成任务不可编辑。
5. 两个浏览器窗口同时编辑同一未完成任务。窗口一保存后，窗口二保存应提示冲突且保留输入；点击“丢弃本次编辑并重新加载”，依据最新版本再修改。
6. 切换项目，列表、详情和统计只显示该项目的数据；可从概览创建新项目，并获得固定演示成员。

## HTTP 契约

响应为 UTF-8 JSON，带 `X-Sample-Protocol: 2`；前端校验协议和必要字段。业务错误 400/404/409，响应 `{error}`；版本冲突用 409。

- `GET/POST /api/projects`，创建体 `{name}`；`GET /api/members?projectId=1`。
- `GET /api/tasks?projectId=1&q=&label=&status=&priority=0&ownerId=0&overdue=false&page=1&size=25`，size 1..100，返回 items/total/page/size。
- `GET /api/tasks/{id}?projectId=1` 返回任务、dependencies/comments/events；错误项目范围返回 404。
- `POST /api/tasks` 和 `PATCH /api/tasks/{id}` 使用 samples/request.json 的字段。创建为 todo/version=1；编辑须带读到的 version，成功递增版本。标签最多 8 个，依赖最多 20 个。
- `POST /api/tasks/{id}/transition?projectId=1`：`{status,version,actorId}`。todo→doing→review→done；review 可退回 doing，其他跳转拒绝。完成时所有依赖必须 done。
- `POST /api/tasks/{id}/comments?projectId=1`：`{text,actorId,requestId}`。同键同内容幂等，同键不同内容 409。
- `GET /api/stats?projectId=1` 返回 total/statuses/owners/overdue。

写事务使用 BEGIN IMMEDIATE；任务、依赖、版本和事件共同提交。项目与成员关系由服务端校验，数据库包含外键、唯一约束和查询索引。

## 验收与规模数据

`python samples/generate.py --url http://127.0.0.1:18101 --count 10000 --seed 20260919` 通过真实 API 扩展两个演示项目的数据。推荐使用独立 DATA_DIR。

仓库运行器：`python src/app/main/src/test/python/polyglot_runner.py verify --project task-board --profile standard --work <隔离目录>`，快速档使用 `--profile quick`。标准档包含 10,000 条任务、独立统计预期、跨项目拒绝、依赖环、审核闭环、10 路竞争、评论幂等、写入中断恢复和真实浏览器多页面流程。

隔离故障闸门：`SAMPLE_FAULT_POINT=task-written`、`SAMPLE_FAULT_DIR`。写入事务期间产生 task-written.ready，等待 task-written.release，60 秒超时，默认关闭。验收在边界终止 Java 后检查事务整体回滚，再执行正常修改。

数据库、构建目录及生成输出不提交。Windows 本轮结果见上级验证记录；Linux 实机与 WindowsToLinux 产品部署仍待验证。
