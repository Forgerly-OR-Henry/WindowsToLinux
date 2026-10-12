# task-board 纯主 Agent 部署对照 19

2026-10-13 用户恢复后重新明确：先部署一个项目，暂时关闭审批模型，先判断主 Agent 能否自行完成部署。原16恢复轮实际调用52次审批，不能作为纯主Agent成功证据；该恢复问题留待本对照结束后处理，保留原应用、数据、UNKNOWN及调查。

本轮使用原产品DeploymentAgentWorkflow提交task-board新任务，诊断授权仍严格绑定原owner/task/server/source/configuration，发送APPROVAL前拒绝；生产完整审批没有关闭开关。主Agent自行选择Skill、工具或命令，监督代理只观察实际状态，实际阻塞才修代码或Skill，不接手服务器操作或逐步给答案。

测试入口新增显式mainOnlyDeploymentOnly选项，仅允许已启用的主only诊断，默认完整验收入口保持。它在主任务自然ENDED/SUCCEEDED后，核验产品已完成任务使用记录的App/server/健康组件/publicUrl和平台成功归档，输出MAIN_AGENT_ONLY_DEPLOYMENT_PASS。该轮不启动业务写入和浏览器验收，不能称完整前后端验收或双Agent成功。

后续通过正式产品管理入口仅停止本轮新App、禁用自启；部署、业务QA、管理和Runtime收尾失败分别记录，避免把管理故障误判为部署模型失败。Runtime关闭后再次核APP尝试、调用、tokens均为0，完整成功标记还要求管理结清。测试独立publicUrl端口46200；原16应用46100及持久数据保留。

首轮真实执行准备使用task-board-main-only-19-01和受保护凭据副本；凭据不进入提示词、文档或模型正文。新配置能力探测若发生单独注明未知用量，实际部署provider usage按返回证据记录；不混入最终冻结八项目。

启动前只完成本地test-compile（显式限定该测试入口、跳过测试），exit0，运行冻结3279文件零变化；1个Java格式write/check通过。testsExecuted=0，没有新真实API、SSH、Runtime或部署成功声明，也未重跑已通过旧测试组。父核3279项原前后像/当前字节、唯一测试owner和精确补丁正反向字节，非owner/新增/删除为0，自有验证进程0。

唯一源码owner混有原迁移上下文，仅交付[自有精确增量](patches/DEPLOYMENT-AGENT-MAIN-ONLY-DEPLOYMENT-PROBE-19.patch)，SHA256 f10a6a69801927511e28d7137da2a11ee58cc492970d4dd65020ad1bffdd4251，不整体暂存原文件。该补丁基于本阶段完整工作区前像，尚未证明独立干净checkout构建。默认只本地提交，不推送。

因果判断：纯部署成功表明当前版本主Agent在本条件下能闭环，随后重点隔离审批阻塞；仍需同版本双Agent对照。纯部署失败需区分主模型、平台/工具和环境，不能凭一个失败结果直接归因模型。完整task-board业务/工具验收、成功恢复历史清理、非默认目录真机部署和重启、其余七项目及最终冻结新服务器八项目仍待验收。

实现使用GPT-6.1-Sol；本包未使用GPT-6-Astra。证据目录为.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-main-only-deployment-probe-19，真实运行另行记录。


## 2026-10-13 02:48+08 纯主Agent真实19-01结果

task-board-main-only-19-01已结束，实际1测试/1FAIL/0error-skip，源码与Skill冻结3279项无变化。全程38捕获响应全部LANGUAGE，70诊断domain判断；Runtime关闭后APP尝试/调用/token均0。主Agent自主识别两组件、规划、处理工具链空间不足与ownership失败、读取Skill、检查环境、改用获授权命令、修正127构建失败、封存、deliver、选择产品publish、verify、finish；监督代理未写运行中源码/Skill，未现场提示工具步骤或接手SSH。

原任务1a5f7268-6fed-4e41-ba1d-c659954ee1fd自然返回Published healthy=[backend,frontend]，平台自然ENDED/SUCCEEDED且PLATFORM_TASK_SUCCEEDED归档；新正式App wtl-agent-main-only-diagnostic-task-board-779tb0t9o保存。验收失败原因明确为正式入口契约：frontend Endpoint web的protocol=tcp、value=tcp://45.192.105.213:46200、browser为空，backend endpoints为空，未通过原授权http://45.192.105.213:46200网页入口断言。不能将此轮直接说为完整纯部署通过，也不能将它说为主模型卡死、审批阻断或单纯断言误判；HTTP服务/网页入口与TCP监听的契约需要继续定点调查。

测试finally通过普通产品管理入口仅将本轮新App停止并禁用自启，实际STATIC_LIVE_CLEAN已出；Runtime关闭后APP0，正常CIM按本轮捕获PID及CreationDate匹配确认0进程。受保护凭据副本19已按精确workspace/GUID目录校验清除、原凭据源双文件SHA保持，原16应用46100、其他业务/UNKNOWN及调查DB保留。没有业务写入、真实浏览器验收或Linux重启，不重复付费获取已存在部署回执。

已知provider usage：input2073765/output78693/合计2152458，cache hit1394048/miss679717，捕获输入命中率67.22%；reasoning62537是输出子集。38请求均有响应usage；DEPLOYMENT两项配置探测的provider用量/transport次数仍UNKNOWN，不将它们计为0。审批捕获占比0%，此轮只是main-only开发对照，不能混入最终冻结八项目或计作完整双Agent达标轮。

自有入口交付本地提交43a26151，未推送；真实19证据及XML/usage/父核结果保留。下一阶段先只读查HTTP入口工具/Skill和Agent判断的具体契约缺口，再按证据最小修复并定点验证，继续保持main-only优先。原16成功恢复历史清理、完整task-board业务/浏览器/工具与双Agent验收、非默认目录真机与重启、其他七项目和最终冻结八项目仍待完成。
