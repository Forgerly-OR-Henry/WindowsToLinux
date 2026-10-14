# 139：备份专用产品命令入口

为重装前的必要保全增加独立验收入口 `executesSelectiveBackupCommandThroughProduct`。它只接收一轮一项、严格绑定的 BACKUP_ONLY 计划，通过真实新 Task 和产品 `RemoteSession.execute` 执行；本轮尚未运行该真实入口，没有连接服务器或导回数据。

计划在 Runtime 和能力探测前校验：严格 UTF-8、重复及尾随 JSON 拒绝、固定16字段、run/server/指纹/显式布局、原文件 SHA 和无重解析点边界、真实来源事实与证据引用、超时和输出上限。类别只允许备份只读调查、数据库快照及本次所属备份清理；类别本身不证明脚本安全。独立 APP 必须查看完整脚本、非秘密 stdin、具体请求、exact intent、来源和必要性，不能只审批摘要。完整事实超过48000序列化字符即拒绝，不截断。

APP 批准后重新检查计划、证据和配置，取得一次新的 exact grant，发送前再次检查，原产品 SDK 消费 grant。完整 `CommandResult` 的 stdout、stderr、退出码、截断状态及原 outcome 落证；UNKNOWN 原样交回任务，不改成成功，不自动重放或恢复旧任务。备份入口只配置 APP 及原两项必要能力探测，没有主部署模型调用；STATIC、ASSISTED、AGENT 和共享 API 未改。

唯一新增纯本地方法 `backupCommandPlansBindFullRequestsAndPreserveUnknownReceipts` 实际 **1 PASS、0 FAIL、0 ERROR、0 SKIP，0.290秒**。反例覆盖错误 run/来源/摘要/布局/指纹/字段/编码、超界或漂移证据、DENY不发送、新 grant 一次性、取消及 UNKNOWN 原始回执保留。没有运行整类或全套测试，也没有重复136已通过的方法。sole-owner格式写入和检查、显式 `-DskipTests package` 均通过。

源码3299项仅此验收 fixture 变化，最终字节与实际测试一致。父核原 log/XML/TXT SHA，以及[精确源码增量补丁](patches/BACKUP-COMMAND-139.patch)正反应用后的原始字节；只提交该增量及文档，保留原迁移和其它改动。deployment.jar SHA `891ef1b0cc2d0c7dca23353c299676e9fb24270e28ac878b2214e844eacc42bb`。正常CIM进程收尾、临时文件清理及JAR逐字节检查保存在本轮原证中。

原证：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-backup-command-139/`。140仅准备了固定五根的有界元数据、容量和挂载查询；真实执行须封定当前代码、工具、输入和原连接身份并完成独立 APP。本工具不默认完整 collect，不读取配置秘密正文，不允许借此启动部署、清理旧 UNKNOWN 或遗漏数据库一致性。必要备份仍未完成，不能暂停或提示可重装。
