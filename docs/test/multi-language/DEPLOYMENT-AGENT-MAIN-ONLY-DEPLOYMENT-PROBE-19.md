# task-board 纯主 Agent 部署对照 19

2026-10-13 用户恢复后重新明确：先部署一个项目，暂时关闭审批模型，先判断主 Agent 能否自行完成部署。原16恢复轮实际调用52次审批，不能作为纯主Agent成功证据；该恢复问题留待本对照结束后处理，保留原应用、数据、UNKNOWN及调查。

本轮使用原产品DeploymentAgentWorkflow提交task-board新任务，诊断授权仍严格绑定原owner/task/server/source/configuration，发送APPROVAL前拒绝；生产完整审批没有关闭开关。主Agent自行选择Skill、工具或命令，监督代理只观察实际状态，实际阻塞才修代码或Skill，不接手服务器操作或逐步给答案。

测试入口新增显式mainOnlyDeploymentOnly选项，仅允许已启用的主only诊断，默认完整验收入口保持。它在主任务自然ENDED/SUCCEEDED后，核验产品已完成任务使用记录的App/server/健康组件/publicUrl和平台成功归档，输出MAIN_AGENT_ONLY_DEPLOYMENT_PASS。该轮不启动业务写入和浏览器验收，不能称完整前后端验收或双Agent成功。

后续通过正式产品管理入口仅停止本轮新App、禁用自启；部署、业务QA、管理和Runtime收尾失败分别记录，避免把管理故障误判为部署模型失败。Runtime关闭后再次核APP尝试、调用、tokens均为0，完整成功标记还要求管理结清。测试独立publicUrl端口46200；原16应用46100及持久数据保留。

首轮真实执行准备使用task-board-main-only-19-01和受保护凭据副本；凭据不进入提示词、文档或模型正文。新配置能力探测若发生单独注明未知用量，实际部署provider usage按返回证据记录；不混入最终冻结八项目。

当前只完成本地test-compile（显式限定该测试入口、跳过测试），exit0，运行冻结3279文件零变化；1个Java格式write/check通过。testsExecuted=0，没有新真实API、SSH、Runtime或部署成功声明，也未重跑已通过旧测试组。父核3279项原前后像/当前字节、唯一测试owner和精确补丁正反向字节，非owner/新增/删除为0，自有验证进程0。

唯一源码owner混有原迁移上下文，仅交付[自有精确增量](patches/DEPLOYMENT-AGENT-MAIN-ONLY-DEPLOYMENT-PROBE-19.patch)，SHA256 f10a6a69801927511e28d7137da2a11ee58cc492970d4dd65020ad1bffdd4251，不整体暂存原文件。该补丁基于本阶段完整工作区前像，尚未证明独立干净checkout构建。默认只本地提交，不推送。

因果判断：纯部署成功表明当前版本主Agent在本条件下能闭环，随后重点隔离审批阻塞；仍需同版本双Agent对照。纯部署失败需区分主模型、平台/工具和环境，不能凭一个失败结果直接归因模型。完整task-board业务/工具验收、成功恢复历史清理、非默认目录真机部署和重启、其余七项目及最终冻结新服务器八项目仍待验收。

实现使用GPT-6.1-Sol；本包未使用GPT-6-Astra。证据目录为.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-main-only-deployment-probe-19，真实运行另行记录。
