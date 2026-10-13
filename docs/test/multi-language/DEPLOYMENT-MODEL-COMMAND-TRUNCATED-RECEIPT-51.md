# 模型命令截断输出与精确持久回执 51

[50 定位](DEPLOYMENT-AGENT-PUBLICATION-UNKNOWN-AUDIT-50.md)的阻塞已完成本地修复：原执行器在已观察外层退出码 0 时，仅因 stdout/stderr 截断就转 UNKNOWN，没有查询持久回执。现在保留执行结果与输出完整性的区别，继续按原 task/operation/body/layout 查询严格匹配的完整回执，才将命令持久状态推进 ENDED。实际脚本退出码仍取回执；不重发脚本、不绕过审批、不把命令结束当作 App 成功。

生产仅改 `DeploymentModelCommandRunner.java`，5 行增、2 行删。截断反馈在真实回执之后、原输出之前加入 `OUTPUT_TRUNCATED=1`，明确 partial 是不可信 DATA，不能证明发布、健康、封存或成功评估；未截断返回字节保持原样。未知 transport、缺 value/exit、外层非零、回执缺失/截断/异常/身份不匹配仍保留不确定性和原资源。原 checkpoint、数据库 schema、预算和恢复最终评估门均未改。

窄共享行为实际由 AGENT RemoteTools/PublishRunner 和 ASSISTED 发布调用，直接验证两条路径；STATIC 不经过此执行器，未改其编排或共享 helper。三个既有测试文件为共用 runner 测试、AgentPublishRunnerTest、AssistedWorkflowTest，没有新增框架或公共 peer。

| 定点运行 | 结果 |
| --- | --- |
| focused-01 | 共用 runner 11、AGENT 1、ASSISTED 1，共 13 PASS，0 failure/error/skip |
| layout-01 | 仅新增非默认目录身份方法 1 PASS，0 failure/error/skip；未重复前轮通过组 |

总计 14 次测试、8 个方法。显式 `-Dtest` 方法筛选，配合 `-pl src/mod/deployment -am -Dsurefire.failIfNoSpecifiedTests=false`；包括必要编译，没有全模块/全项目测试。每轮原 XML/TXT、修改时间、SHA、明确 selector 和源码冻结均保留；各轮 freeze 差异 0。四个 Java owner 格式检查通过。

实际覆盖：截断后执行与原 operation 查询各一次；缺回执、错 TASK/OP/BODY/FORMAT、查询截断或异常保持 DISPATCHED；未观察 exit 不查询或释放；原丢回包恢复不重发；未截断字节不变；FORMAT2 正确捕获目录可用、错 layout 摘要拒绝。AGENT 即使部分正文伪造 HEALTHY/VERIFIED，仍 verified=false、finish 被真实独立验证门拒绝；ASSISTED 的实际 publisher loop 收到 partial 回执后也不能靠文本或未提供的 finish 完成，正式 App 行为 0。使用 fake AiService/transport 与隔离本地 Runtime/H2，不能称真实模型或真机改善已通过。

完整 3282 个 before/after-final/current 原字节核对通过，只有四 owner 变化，其他迁移、Skill、配置和历史记录保留。父在受控独立目录用 Git 正反应用精确补丁逐字节通过，目录清除；正常用户 CIM 正控制 1、四个精确 launcher/相关 JVM 活动数 0，本地 Runtime/Frame 正常关闭，无新临时依赖。补丁 SHA256 `5c84c7a8451c4f6c9a0c3c0f7dfd4562b42b79fff238b1ccac80e2fe484a3e9f`，见[原字节增量](DEPLOYMENT-MODEL-COMMAND-TRUNCATED-RECEIPT-51.patch)。

四个源码 owner 都与原迁移混合或不在 HEAD，本地只提交本次精确补丁及配套记录，不整体暂存原源码基线、不推送。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-command-truncated-receipt-51/`。未打包更新 Mod JAR，未读原 48 live DB、运行真实恢复、provider/SSH/browser 或新部署。原 48 仍 UNKNOWN；[52 失败继续设计](DEPLOYMENT-AGENT-FAILURE-CONTINUATION-DESIGN-52.md)与后续实现另计，不把本地修复当作纯部署通过。
