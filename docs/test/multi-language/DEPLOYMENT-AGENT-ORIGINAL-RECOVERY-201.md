# 201：原197容量清理恢复未结

**一次真实产品恢复失败：1 FAIL、0 ERROR/SKIP，原任务仍UNKNOWN。** 没有取得候选清理成功、当前容量或连接通过的证明，未启动新部署。它使用户新限定的三轮优化中第一轮记为未达标，当前1/3；第二轮转向缓存探测与冷安装的资源分配审查。最低cache≥60%/APP≤20%、理想≥80%/<10%保持，三轮仍未满足可验收条件即暂停。

此前[200前置修复](DEPLOYMENT-AGENT-PRECREATION-REFUSAL-200.md)已本地提交`4e5a45b68aed4fb221ae2a4dc18bb0113f364599`，最终有效35个定点case与skipTests包通过，不能算真实业务或provider收益通过。本轮没有从旧日志补证结清200不适用的旧UNKNOWN。

## 实际范围

仅原197 Task `985c3325-5dcd-4304-a899-800b36075cd6`，原Runtime `agent-live-task-board-sjncmxwjc`。使用现有产品入口`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，`originalTaskOnly=true`、`originalPreparationOnly=false`、`configureAgentRecoveryModel=false`，不配置新模型或补做能力探测，不重放原部署。

原产品Recovery负责核对原身份、归属、依赖和buildActive，并要求逐命令独立审批及新grant。主控没有直接SSH或按名称/路径删除资源。旧199、其它任务和正式App没有纳入本次恢复范围。

运行前已在正常用户环境持原产品空lock、拒绝源写入句柄，备份原产品数据库及DPAPI引导文件两项，共56,004,966字节，至`test/data/original197-before-capacity-recovery-201-0d80e73f835b4e7493dd8753ef3bd502/`。CurrentUser/SYSTEM私有ACL、逐长度/SHA核验，未复制远端大目录。它不是完整Runtime或远端回滚能力，不自动恢复备份。

**术语更正：** 辅助记录中的`encryptedProductPairOnly`是历史标签，不能证明整个H2文件已加密。实际保全为受保护产品DB与DPAPI引导对；产品连接URL未设置CIPHER。数据库备份只在上述私有目录，证据工作区仅保存摘要。原backup/helper/seal字节保留，没有为更正表述而伪改历史原件。

## 原失败与只读调查

唯一运行exec81279已终态exit1，不可重开。2026-10-15 23:10:24.897—23:10:33.406+08，Maven7.626秒，原XML为1 FAIL/3.660秒。TaskSnapshot仍UNKNOWN，progress为`AI_RECOVERY_ORIGINAL_EVIDENCE`，阻塞为原两个FILE快照及business reconciliation；不能由缺少CUSTOM blocker推断controller账本已确认。

入口只打印TaskSnapshot和有界usage，未打印本次恢复Problem。父按[H2只读连接说明](https://h2database.com/html/features.html#custom_access_mode)，以原产品锁、已有引导只在内存解包、IFEXISTS/ACCESS_MODE_DATA=r读取精确任务一行；不经过PlatformDatabase initializer或Runtime，前后DBpair SHA相同。首次辅助查询未限定app schema而失败，原件保留；只改为`app.platform_task`后读取成功。

公开`result_json`仍为UNKNOWN/`DEPLOYMENT_AGENT_STOPPED`，166字符，SHA `6d9e4ba96bcf661ae95ee1ac341654b95e643cf36ca91c9c3f5e90fed4278a77`。它与原197旧公开结果相同，不能当本次恢复原因。正常UNKNOWN提交应覆盖此字段；前置/事务失败等可能保留旧值，但尚未证实201具体原因。

代码另确认诊断契约不匹配：`DeploymentDiagnostics.effectResult`向LogEvent.outcome传UNKNOWN，`LogRecordCodec`拒绝UNKNOWN，日志服务返回NOT_SAVED/LOG_RECORD。因此缺少效果日志不能证明没走过某个恢复分支。此处只读发现，没有顺带修改日志契约或弱化状态守门。

第二次最小查询仅覆盖精确原Task和201窗口前后各1秒，deployment日志及同Task平台日志均0行；没有扩大至其它任务、NULL-task或全controller payload，不把0行当不存在故障。仅输出白名单错误码/异常类、长度与摘要，未输出正文、details、凭据或模型内容。真正恢复早退原因仍未知，未再运行恢复。

## 用量、构件和收尾

本次有界100行窗口排除81条旧usage，新窗口0条可观测记录；已知Token小计为空，完整调用次数、缓存、审批占比及费用均UNKNOWN。0行不等于0调用或免费；不与旧197或未来FULL的用量合并，不补请求取账单证据。

实际3307源码清单/JAR/HEAD前后一致。JAR仍为`1bb4f3fd2c46ec41778d997f66d85ca75072f230d8a7bbe37cbe64edac906918`；父从原XML的55项classpath核19类实际解析及冻结字节，另核39项宿主runtime路径/1041文件、200三个完整类族与196其余15组、198 policy。辅助元数据捕获不冒充JVM内部CodeSource跟踪。

原Runtime经产品正常打开/恢复后，DB SHA由`a53dcb32…`变为`daad31c4f311a1dee0afe51c4276a383bab761d6ea159c8e5f91d52abd7ad954`；DPAPI引导SHA仍`c15b0f9f818dc0af24960341f139386d5aef472bd91655a680d07d4848a56ac3`。这是本次产品留下的状态，不覆盖或恢复旧备份。之后只读查询前后同SHA。

正常CIM核本轮10组PID/creation ticks全退出，精确marker TMP/UDS已清；三次本地reader的JVM均退出、各自TMP已清。原Runtime、source、UNKNOWN、占用及调查证据保留。202 FULL工具仍ready/authfalse，未复制新凭据或调用部署入口。Sol完成实现、工具与只读调查，Astra本阶段未新增调用。

原件根`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original197-capacity-recovery-201/`：父closure SHA `fddb3e3f2b8de09d700d712a0d64daa427a469fc15f1cb1f152c7a332ece5aa3`，81份原件seal `3936fe1b6dd32a7260d9f428af78dbb58c904723640a9e3132d1caf732bca976`。父最初以普通Windows路径读取长XML失败，改扩展路径后核验通过，未重复业务。

本次只隔离提交本记录，不推送，不暂存混合源码。完整Goal尚未完成：task-board完整业务/真实browser/产品STOP-DISABLE及可核收益、其余七项目、冻结后全新机八项目、非默认目录真实部署、整机重启与旧未结资源安全结清仍待。
