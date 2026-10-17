# 原 File Transfer 285 已由产品结清

仅本次 Task `40109210-4105-4dcc-a332-3f46ee42013f`，原 Runtime `src/app/db/data/agent-live-file-transfer-5c929zxeg`、App `wtl-agent-file-transfer-5c92bmlwg`。使用实现基线 `9e457582d7e51af882a0f14c545d40f829ab6764`，没有重新部署或重入未知模型会话。

## 产品实际结果

唯一 `reconcile-285-01`，session17969 实际退出0；JUnit1P/0F/0E/0Skip、57.42s，UTC2026-10-17T15:25:39.5740004Z—15:26:42.2467769Z。显式只运行 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true，configureAgentRecoveryModel=false，原PreparationOnly=false；没有改 fixture、配置新模型、提供新凭据或遍历其它原任务。

产品返回精确原 TaskSnapshot：ENDED、outcome FAILED、blockers空、progress AI_RECOVERY_ORIGINAL_EVIDENCE，endedAt15:26:41.413Z；`AGENT_LIVE_EXACT_ORIGINAL_RECONCILED` 的 Task/outcome一致，正常 Runtime.close 后进程退出。FAILED 是原部署失败结果，不能计业务或恢复失败。原候选收尾走既有 beforePublication 恢复：重新校验原归属/状态及正式查询、清理、清理后确认；每动作仍独立 APP 和新 grant，不按目录删除。

## 事后独立核对

复用原固定286 reader源码，在独立输出目录重新 javac/read 均0，UTC15:27:22.035556Z—15:27:23.528213Z；没有修改 reader逻辑、运行 Runtime/TaskContext、恢复、模型或SSH。existing-only、H2 READONLY/IFEXISTS、现有空锁，pair属性锁内与关闭后不变。

- 原平台 Task不存在，platform_task_resource总数0；Mod Task/event均0。
- 原 App、component、release、execution均0；没有完成应用可启动。
- 正式 deployment_operation_receipt恰1：operation/task均为原UUID，server static-live，mode AGENT，outcome FAILED，event `DEPLOYMENT_AGENT_PREFORMAL_RECONCILED`，problem IO_FAILURE。
- receipt proof摘要 `2ed4797e853aa8d85ccb269d6a5bf46a1acbf80015637bbef37a7b84ed19158b`，公开生产row codec通过。reader本身不是新的远端absence证明；这里结合产品真实恢复结果与正式回执确认结清，不能只凭本地0行推断远端。

平台、资源及Agent明细由产品正式归档；没有手工SQL、补ACK、回填旧typed proof、伪造continuation或修改UNKNOWN结果。原92事件及失败FULL证据在只读前快照/封闭stdout保留，不要求正式归档后原表仍有92行。

CIM自有Java0、terminated0；owner/绝对边界/无reparse后，仅清r286a/u286r及事后reader三个编译/临时目录。Runtime、正式receipt和全部原始证据保留，source176未由收尾清理打开。

## 纯恢复用量边界

从原Task最新100条有界诊断窗口，按startedAt剔除旧FULL记录，观察到12个新APP generation/physical attempts：input110211、output2684、IO112895、cache hit87936/miss22275。已知IO/cache12，reasoning12未知；APP已知IO占比100%，输入加权cache79.7888%。这是有界窗口中的已观察纯恢复量，完整provider/config/HTTP账单未知，不混入FULL或两个项目最终指标，没有新增付费统计调用。

证据根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-capacity-refusal-286/`，含reconcile-285-01、reader-after-reconcile285、usage-reconcile285和reconciliation-cleanup。预算修正及其1定点见 [286说明](DEPLOYMENT-AGENT-ISOLATION-QUOTA-286.md)；285失败FULL见 [285记录](DEPLOYMENT-AGENT-ISOLATION-LIVE-285.md)。

原194继续保持已结清，不恢复；原238/280/281/282/283/284仍不恢复、改判或重放。新一轮File Transfer仍保护这六组，原285无需加入新UNKNOWN保护集合；实际新容量及端口必须重新经产品准入。File Transfer完整BUSINESS v2、真实浏览器及STOP/DISABLE后才开始CSV，两个项目完成后暂停汇报指标。
