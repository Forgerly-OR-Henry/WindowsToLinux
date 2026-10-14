# 130：选择性备份盘点的原生批量调用修复

129 首轮只读盘点实际失败：主模型返回 7 个工具调用，盘点入口误用严格单结果 `DeploymentAiJson.ask`，因此在下载前终止。原 UNKNOWN、日志和用量保留，不重放旧任务，也不从日志补造远端清单。用户仍要求必要备份完成后才能暂停并自行重装。

仅修改 `DeploymentStaticLiveAcceptanceTest.java` 的 BACKUP_ONLY 盘点适配器，复用既有 `DeploymentAiJson.call/arguments` 和 `DeploymentNativeHistory`。每批允许 1 至 32 个 `backup_action`；整批先核工具名、唯一调用身份、四字段 schema、允许动作和已观测对象身份，再执行只读 metadata 查询。未知工具、额外路径、任意命令或复用身份不 dispatch。真实逐项反馈和完整原生历史保留，FINISH 或前项失败后的剩余调用明确为 NOT_STARTED。共享单结果适配器、独立审批、生产 API、部署三模式和数据库版本均未改变。

新增 task UUID 独占的有界盘点进度文件，每个根观测和动作回执及时保存；校验 run、task、布局 SHA、实际 wire 指纹、目录边界及原文件 SHA。拒绝 reparse、错误绑定、外部修改和超过 2 MiB。失败仍保留已观测元数据，覆盖状态始终 UNKNOWN、`backupComplete=false`；不存载荷、凭据或可执行脚本，不把盘点结束视为完整备份。

唯一新增本地方法 `DeploymentStaticLiveAcceptanceTest#backupInventoryAcceptsNativeReadOnlyBatchesAndRetainsPartialMetadata` 在 test-02 实际 1 PASS、0 FAIL、0 ERROR、0 SKIP，0.363 秒。覆盖实际 7-call 形状、单调用、整批非法零回调、FINISH 后 NOT_STARTED、原生历史、部分记录故障保留与绑定负例。test-01 因本地 runner 遗漏新专用属性而实际 SKIPPED，原证保留，未计为通过；修正 runner 后仅重跑该方法，未放宽入口。format-03 仅 owner 的 write/check 和 package-01 显式 skipTests 打包通过；未跑整类、模块全套或重跑此前通过方法。

源码清单 3299 项，仅该 owner 改动，最终字节与实际测试及打包前后相同。deployment.jar SHA `790540d08d0eed34a8a59a3433e3a9f7c07c347c958d7f0367580fa98582c761`；1048 个 class 与当前编译字节、113 个资源与源码相同。正常非管理员 CIM 核实 58 组自有 PID/创建时刻均退出，自有测试临时目录已清。本阶段没有 Runtime、DB、provider、SSH 或真实导出。

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-native-inventory-130/`。父核原日志/报告 SHA、最终实测字节和 [精确增量补丁](patches/BACKUP-NATIVE-INVENTORY-130.patch) 的正反向原字节；仅提交本文和补丁，保留原迁移与其它改动，不推送。下一轮必须创建新只读备份任务并重新认证，不修复旧 UNKNOWN。必要数据实际导回、保护、SQLite 一致性和范围完整性验证仍待，当前不能暂停或提示可重装。
