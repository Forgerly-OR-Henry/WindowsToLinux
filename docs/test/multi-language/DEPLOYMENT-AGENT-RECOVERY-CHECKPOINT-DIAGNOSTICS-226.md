# 原194恢复检查点拒因取证226

2026-10-16，已通过一次本地裸 H2 只读查询和离线完整消息 SHA 匹配，查明225的直接错误为 **`UNAVAILABLE/MOD_TASK_BINDING_CLOSED`**。当前多候选恢复在缺失进度时调用普通 `beginCheckpoint`，与宿主恢复合同冲突。未修改产品代码、重试恢复或进行新部署；原任务仍为 `UNKNOWN`。

## 当前约束和基线

用户最新要求：CSV Inspector 与 File Transfer 保持两个选择，部署、调试必须逐个进行。两项目各自调整并完成真实验收后，分别核 FULL 轮次的审批用量、缓存命中及未知范围；若审批仍明显偏高，先暂停目标并讨论后续方案。当前恢复窗口不用于该判断，两个项目尚未完成调整验收。

基线 HEAD `e090e14549fb954db700f68e3bc4ca382ce29f6c`、index空，3310份实际源码和三个JAR与224/225最终状态相同。225封存66文件逐SHA相同；原数据库59060224字节、SHA `92e170a2fec8718a3077be0f84c957db98af3c652c77961d55b701204d478a7c`，引导358字节、SHA `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。保留必要私有备份、原工作树及全部未结证据。

实际工具链为JDK21.0.10、Maven3.9.12、Python3.12.10。初次 Maven 版本查询因extended cwd被CMD当作UNC而回退，虽然version输出exit0，仍保留原警告；仅同一版本查询改规范非extended cwd重核exit0/无警告。该辅助路径问题不是产品或服务器失败，没有构建或业务测试。

## 实际只读范围与收尾

执行工具由已验证的224/225只读入口最小派生，沿用原10项classpath/319文件pin；没有扩大依赖或引入Runtime。两个PS AST、7项实际纯准入检查及6项新增消息投影源契约检查通过；Java字面/源检查不冒充javac，实际source-mode编译及读取由下面唯一执行验证。

唯一 `tools/diagnostics-read-01` exit0、stderr空：

- 正常用户DPAPI仅内存解开数据库引导；H2使用 `IFEXISTS/ACCESS_MODE_DATA=r/AUTO_SERVER=false`，核schema1、generation及真实mod/log_category身份。
- 外层原数据库/引导 `Read/FileShare.Read` 句柄拒写和删除；Java持原产品空锁，读取前后核pair及锁不变。
- 仅原Task `a67e3b89-d04e-49a7-8b5e-20ed93803d53`，查询225本轮 `06:41:45.1888006Z` 至 `06:42:49.3423541Z` 的持久诊断，128条上界；原97条事件完整摘要、最多128条新增事件，以及两个精确CUSTOM checkpoint。
- 没有读取3454份全资源、凭据表、配置或模型正文；未知message/payload只输出长度/SHA，固定安全字段单独投影。缺失记录不解释为零发送。

正常Close exit0，父另CIM独核reader launcher、Java和closer共3组精确PID/创建时间全部退出，自有TMP不存在。原文件、97条事件前缀均未改变，未创建UDS、初始化产品、迁移数据库、调用模型/SSH或执行恢复。本轮无新增provider usage，未跑整类、模块或全项目测试。

## 实际持久证据

本轮时间窗口完整返回3条诊断：2条既有225 usage，以及1条 `DEPLOYMENT_AGENT_FAILURE`。错误记录id为 `56532e78-0960-4df5-91dd-3e95754ff1fc`，时间 `1792132959482`，异常类别 `ApiException`，details为空。消息110字符/UTF-8字节，SHA为：

`a36b905a2089e3e914f975bf24ab47522ce63dfdd92099b91a45764a57540f14`

平台Task结果为 `UNKNOWN/DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED`，outcome和endedAt为空。原97条事件序列SHA仍为 `a4462b793363ccbe71a086ad46ce98c93a73e7f3abbcae94ee7c48d62e781044`；新增事件0。`agent-multiprepare-compensation.v1` 与 `deployment-model-commands` 两检查点均缺失。这些缺失不证明此前未审批、未发送或无远端副作用。

## 精确错误与合同冲突

从当前 `ApiException`、`DeploymentDiagnostics.agentFailure` 的真实编码及 `PlatformTaskRuntime.beginCheckpoint` 唯一固定消息，重构得到：

```text
ApiException: The owning Mod activation cannot start new checkpoint work [UNAVAILABLE/MOD_TASK_BINDING_CLOSED]
```

长度110及完整SHA与实际记录精确一致；父方独立计算再次匹配。无需第二次打开数据库，也没有导出自由错误正文。这是完整消息的身份等同证据，区别于按进度、模型usage或检查点缺失猜测原因。

宿主 `PlatformTaskRuntime:1489–1495` 先以 `revoked || recoveryOnly` 拒绝新author checkpoint，随后还有 reconciliation 不得创建新author checkpoint的独立守门。`PlatformModContext:128–129` 将RECOVERY模式静态接为recovery-only TaskService。消息本身没有读取这两个运行时flag的值，不能仅凭OR条件区分哪个flag为真。

当前 `DeploymentAgentRecovery:379–382` 在原目标、helper probe和资源核验后调用 `DeploymentAgentMultiPreparationCompensation.Progress.open`；该方法292–302在进度不存在时调用普通 `beginCheckpoint`。这条静态链与实际错误及平台结果相符，是最窄合同冲突；原日志无stack，不能把唯一源码候选表述为实测调用栈，也不能声称此前零发送。224的受控MemoryCheckpoint代理没有覆盖此真实宿主限制，34项本地通过不能证明该合同兼容。

下一步应以真实宿主recovery-only/reconciliation合同建立定点回归，评估已有合法生命周期或专门恢复进度能力。不能直接放开上述普通begin守门、直写平台表、补造旧checkpoint、改写旧UNKNOWN或自动重试。新恢复动作仍须独立审批、新grant及SDK前持久未知边界，全部原候选严格absence后才能释放和归档。STATIC/ASSISTED/AGENT编排保持独立，数据库版本维持1。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-recovery-diagnostics-226/`。入口为 `parent-reader-release226.json`、`parent-reader-verification226.json`、`parent-exact-cause226.json`、唯一read的原stdout/result/closure及 `audit-read226.md`（SHA `2af079812d9e9923a67dff17e77e1de1eb539b188b9273547a7f3340bcda465b`）。未使用GPT-6-Astra；后续由Sol XHigh只读审恢复持久化合同，尚未实施修复。完整业务、容量回收、冻结八项目、非默认目录和整机重启验收仍未完成。
