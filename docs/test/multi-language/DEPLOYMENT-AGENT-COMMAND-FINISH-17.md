# Agent 命令检查点在发布收尾前结清

接续 [真实主 Agent 对照 16-03](DEPLOYMENT-DYNAMIC-WORKSPACE-QUOTA-16.md)。该轮主模型在工具链准备失败后自主使用命令路径，完成远端发布和验证，但 `finish` 未完成产品收尾，任务保留 UNKNOWN，正式应用/发布表为空。

## 阻塞与证据

`DeploymentAgentCleanupEvidence.requireBinding` 要求 `deployment-model-commands` 检查点已释放。宿主命令执行成功后，原 producer 将命令记录为 ENDED，却保留检查点；`DeploymentAgentPublishRunner` 原来只在整个发布循环的 finally 调用严格 `releaseKnown`，晚于 finish 创建清理审批依据的时机。

原 task `dc79c361-80f1-467a-8d36-4935ff4c8355` 的只读账本与这一顺序一致：两个宿主命令均 ENDED/exit 0，检查点 task/server/指纹/元数据绑定匹配；187 FINISHED 之后出现 188 ApiException 和 189 OTHER，检查点在 ERROR 后 7 毫秒才释放，没有 CLEANED/DELIVERY_RESULT。formal execution `1cf87400-2b94-4672-a6e2-28d1cdf6217c` 保留 EXECUTING/UNKNOWN，业务未提交，publicationTemplate 存在。

原 ApiException 的具体 message 未持久化，不能将 OTHER 补成某个错误文本。直接 peer 再现进入实际 PublishRunner 的 verify→finish：ENDED 检查点触发 ApiException，其 cause 为 `Original modifying effects are unsettled`，位置为 CleanupEvidence.requireBinding。原任务证据和定点再现分别保存。

## 最小修复

仅在 Agent 已独立验证当前发布、模型选择 finish、原 guard 检查成立且资源回调完成之后，创建 cleanupEvidence 之前，调用既有 `DeploymentModelCommandRunner.releaseKnown`。原 finally 兜底保留。共享 runner、候选清理绑定门、命令回执协议、审批和其他模式均不修改。

此调用只结清原任务的已知命令检查点，不伪造 command receipt 或成功。DISPATCHED 仍拒绝释放；外来任务、损坏载荷和未结副作用不发起清理。释放保留原检查点数据供后续核对，原历史明细只按真正成功结清的产品收尾规则清除。

## 当前验证状态

直接再现 focused-04 的 6 个参数中 5 个通过，ENDED 场景精确失败并保留 XML；ABSENT、已释放的幂等情况、DISPATCHED、外来任务、损坏载荷通过相应边界断言。最小修复后 focused-05 仅运行这 6 个直接受影响参数，实际 6 个参数全部通过，零失败/错误/跳过，20.544 秒；原相关 unsettled 方法保留最新通过，合计 7 个不同直接逻辑场景。不重跑动态额度 68 场景或全模块测试。

3279 个源码/配置/Skill 文件仅两个 owner 改变，非 owner 差异及新增/删除为零；两个 Java 文件限定格式检查通过，自有验证进程为零。父独立核实实际 XML、原失败再现及生产一行增量，补丁正向/反向应用后的字节均一致。五轮原编译、夹具和失败日志保留；本地定点通过不等于原真实任务已恢复。

[精确增量补丁](patches/DEPLOYMENT-AGENT-COMMAND-FINISH-17.patch) SHA256 为 `32f603b9a5628565aa80339a258da450780551dc1a3d00eb170ff1d5644e8984`。测试文件的原前像与 HEAD 一致，直接提交；生产文件含原迁移，只交付本次一行增量补丁，不整体暂存。补丁相对于原工作区，未证明干净 checkout 可独立构建。

## 原任务恢复入口

现有平台原任务 reconcile 回调进入 AgentWorkflow/AgentRecovery，支持接纳已 healthy 的真实发布。它依据原 formal template、AI_PUBLICATION/FINISHED、target/profile/layout、候选/来源/制品/secret/ref 集合和命令回执；使用冻结的原审批路由或明确授权的新恢复审批路由，重新核实原 current、逐组件和整图健康，再提交原业务结果并严格清理候选、释放来源、归档历史及结束平台任务。

该入口不重放 build/publish 或主模型决策。缺失、错配、健康或审批不确定时保留 UNKNOWN；若产品要求原 inspection，先通过对应入口完成 inspection，再 reconcile。16-03 已有 FINISHED 和保存模板只是必要事实，不能提前称恢复成功。修复交付后优先通过此产品入口结清16，避免重复完整部署取证；真实前后端业务、浏览器及生命周期验收仍需完成。
