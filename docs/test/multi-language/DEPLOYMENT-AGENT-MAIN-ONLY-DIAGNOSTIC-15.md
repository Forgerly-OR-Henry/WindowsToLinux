# 主 Agent 独立部署诊断 15

2026-10-12：本轮完成用户授权的主Agent独立部署对照，真实入口失败并保留UNKNOWN。用户要求完成本轮后暂停，以便更新电脑工具链；没有继续修复、重试或部署其他项目。

## 对照条件

只使用 `DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic`，实际进入 `DeploymentAgentWorkflow.submitLocal` 和原Session主循环。范围是task-board，MANUAL_REVIEW、remember=false、主模型LOW、普通输出32768及产品有限预算均保留。监督代理监控真实状态，不执行服务器命令，也不向模型提供部署步骤或答案。

Workflow包内显式构造器注入 `DeploymentAgentReviewSource`，由本次测试提供 `USER_AUTHORIZED_DIAGNOSTIC` domain判断。默认公开构造器仍使用原双Agent路线，没有生产YAML、ENV、生产`-D`、ThreadLocal、静态注册表、反射或独立主循环切换审批。

诊断permit绑定原owner对象及身份、task对象及ID、server、源码摘要和完整配置修订。native命令判断前后保留来源、task/server、回执、身份角色及配置复核；补丁仍经过原受保护路径、validatePatch和配置检查。测试判断要求原平台的单次宿主授权，不制造AiResponse、usage、模型裁决、分页阅读记录、执行回执或grant。`review_response` 在本轮不可用；审批模型的完整输入投递覆盖及判断被明确暂停，不能称为完整审批验收。

主profile仅通过TEXT和TOOL_CALLING两项真实能力验证，随后登记为休眠APPROVAL路由以维持原双purpose配置修订合同。它不承担审批判断；任何APPROVAL业务请求在调用真实AiService之前hard fail。主Agent自主选择产品工具，源码、Skill及配置运行中冻结。

第14轮已在服务器产生工具链准备效果，第15轮使用同一服务器但独立task/DB/App。这是隔离审批模型调用的诊断，初始服务器状态不同，不能当作干净服务器等条件的严格A/B。第14轮已确认的审批契约问题未同时修改。

## 真实结果

| 项目 | 实际值 |
|---|---|
| run | task-board-main-only-15 |
| task | f5b542be-650e-4fea-bf69-ec4e17b58c2d |
| DB目录 | agent-main-only-diagnostic-live-task-board-458om5t3cs |
| App意图 | wtl-agent-main-only-diagnostic-task-board-458om7d3v8 |
| 终态 | UNKNOWN，没有正式App或成功标识 |
| 入口测试 | 1 failure、0 errors、0 skipped，217.8秒 |
| 结束时间 | 2026-10-12 18:38:41+08 |
| 诊断domain判断 | 14 |
| 审批业务尝试/调用 | 0 / 0 |
| 实际业务响应 | 17，全部LANGUAGE |

第一prepare_tools中resolve成功，prepare实际exit65、未截断；日志结构化诊断为HOST_SPACE_REQUIRED / VOLUME，available9485029376、required9663676416、reserve1073741824 bytes。投影字段形状、类型、范围和关系符合现存codec分支；安全投影无法证明原request operation对应及完整codec接受，二者保留UNKNOWN。原CAP59→60记录FAILED，反馈已交给主模型。它随后自行读取Skill、尝试host_command、执行check两次并再调用prepare_tools。host_command有TOOL_REJECTED反馈，具体拒绝字段仍未知，没有成功执行证明，不将尝试当作完成磁盘释放；监督代理没有现场提示这些步骤。

第二prepare_tools中host probe与capacity命令均成功exit0。随后产品候选容量检查抛出 `Insufficient verified agent candidate space`，实际检查为 `availableBytes < 2 × workspaceBytes + 1 GiB`。本次available、workspace与required具体数值及物理空间原因无法由投影证明，保持UNKNOWN。原PREPARE70/action `83aa302b3693b1db9b831c8303a1d5027378395c75f7edef13e87eca0a835a54` 没有RESULT，异常传播后任务进入UNKNOWN，主模型没有收到再次处理机会。第一个SSH非零并非直接终止点；本轮没有审批拒绝，也没有超时证据。

这确认候选容量及其错误反馈边界参与阻塞。主模型已经对第一失败自主做出反应，本轮不足以判断它本身无法闭环，也不能据此证明双Agent中的审批过严。下一阶段需要按真实证据处理该产品边界；本次暂停前未实施修复或新尝试。

没有进入真实浏览器业务、生命周期成功或发布验收。只读H2审计确认734资源、Agent语义版本4、effectsSettled=false，operation摘要与formal execution均0、正式十表0，模型1/服务器1保持。主库SHA256为 `e50085b90b5c8f3bd8ffd339fa1417425147791c81d555239e852385d4ff4df5`，审计前后相同、锁已释放。第二操作不能借第一FAILED回执结清；原13/14/15的未结账本、候选、共享工具链和调查保留，原373正式业务未操作。没有恢复或历史清理成功证明。

## 实际用量

17个监控请求均捕获provider响应：input577243、output18034、合计595277，prompt cache hit419456、miss157787，捕获输入命中率72.67%。reasoning12460是已知输出子集，不额外相加。两项配置能力探测的usage及实际transport次数UNKNOWN，不计为已测零。

审批业务调用为0是显式对照条件，不能当作完整审批成本比例通过。本轮是失败开发诊断，全部排除最终全新服务器冻结八项目统计；完整原日志、实际XML和一次保存的独立usage保留，没有重复付费取证。

## 本地验证和交付隔离

focused-01缺少ServerId import，编译失败且0测试；修正后focused-02显式筛选FixtureTest两项及ApprovalTest二十五项，27个直接场景全部通过、零失败/错误/跳过。覆盖诊断原owner/task/target/source/configuration、取消、APP发送前拒绝、真实host grant与拒绝、静态恶意命令、来源/回执/身份/配置变化、补丁冻结和缺失/失败执行回执；原默认native/shared路线的选择用例继续要求审批调用。

沙箱formatter首轮因IDEA DirectoryLock/UnixDomainSocket失败，原日志保留；正常用户限定11个Java路径write/check通过。没有全模块或全项目测试。直接验证只证明这些受控场景，不能替代真实API模型部署成功。

实现前14基线3272项中只修改8项自有旧文件，新增3项自有源码；其他旧文件保持。scope、fixed/static审批、Config、Skill、launcher字节不变。真实运行期间3275源码与Skill冻结零差异；会话25903 exit1，18:42:41实际CIM相关验证进程0，原IDEA34300及其17:29创建的Java50092保持。

本次11个source owner中5项可独立提交；另外6项文件已有迁移或其他任务上下文，只交付相对于完整原前像的自有精确增量，不整体暂存原迁移。完整[诊断增量补丁](patches/DEPLOYMENT-AGENT-MAIN-ONLY-DIAGNOSTIC-15.patch) SHA256为 `8f4ec57a300266b607f456a9ca8ce7af2499c4f1c74659a81a36c0cb7a259f41`。原始前后SHA、独立前像正向patch check、当前源码反向check及27实际XML由父代理复核通过。该补丁相对于本阶段原工作区，不是已证明可独立构建的干净checkout版本；没有整体提交迁移或推送。

完整双Agent部署成功、非默认目录真机部署与Linux重启恢复、旧373实际历史清理、其余七项目及最终全新服务器冻结八项目仍待验收。部分旧任务专项恢复harness的名称筛选不接受新诊断DB前缀，本轮未扩展这些入口，也未声称跨重启恢复通过。数据库保持v1，无版号迁移。

实现与有界只读因果核对由GPT-6.1-Sol完成；GPT-6-Astra没有参与本次诊断实现或最终因果核对。证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/` 下的独立15目录，旧失败轮和原证据保留。本轮完成后暂停，等待用户更新工具链并明确恢复。
