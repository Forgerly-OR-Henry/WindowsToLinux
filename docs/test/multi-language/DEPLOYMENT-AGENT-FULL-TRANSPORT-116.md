# task-board完整独立审批部署116：容量查询传输失败

2026-10-14，通过产品入口执行一轮新task-board FULL Agent部署，唯一参数项为 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry/task-board`。AUTOMATIC独立审批、remember=false，公开请求端口48600。部署和审批模型自主选择工具和动作，监控者没有SSH操作、部署接手、旧计划重放、逐步指导或用量强停。107已定点验证的安全capacity/unable实时投影首次用于新的完整独立审批轮。

实际窗口18:54:10.5054237至18:56:46.7582338（UTC+8）。JUnit1项失败、0错误、0跳过，XML时间144.7秒，Maven1、freeze0。原Task `50b1c2e3-26f9-435d-9034-bb0462ad4662` 为UNKNOWN，目标为BLOCKED；main responses6、shared tools15、host commands0，仍停在IDENTIFIED，尚未发布或进入业务与浏览器验收。本轮实际SSH连接确认的主机指纹与已授权 `SHA256:ioa2rovqWA0tIF954jRvT2RUnbzjPEirj3BM7HTAySU` 一致，没有重新信任新身份。

实际停止事件为 `CapabilityOutcomeException: Deployment capability returned UNKNOWN [UNKNOWN/SSH_COMMAND_TRANSPORT]`。调用链是AgentSession.plan → RemoteTools.selectWorkspaces → CandidateChannel.capacityObservation → BuildCommandChannel.send → PlatformLinuxService.Session.execute。已有公开安全诊断：CHANNEL_OPENING、channelOpenConfirmed=false、inputState=NOT_STARTED、sessionOpen=false、sessionAuthenticated=true、channelClosed=true、commandOpenRequested=true、exitObserved=false、outputObserved=false、elapsedMillis7515、timeoutMillis20000。审批已运行，但没有据此发现审批拒绝；不能将本轮失败归因为审批过严、磁盘不足或模型主动unable。

框架exec发送的是固定SshCommandFrame bootstrap，作者脚本和stdin由独立输入帧提供；当前实现仅在opening.verify之后启动sender。以上实际诊断支持继续核对“业务帧未发送”与通道打开请求的区别，不代表原UNKNOWN已经结清。网络或会话关闭的具体底层原因尚未证实，不补造原回执，不直接重放容量查询或旧计划。

主模型6项物理attempt已知input94,209、output8,582、合计102,791；审批6项已知input53,703、output3,411、合计57,114。合计已知159,905 Token，input147,912、output11,993、cached input100,864、cache miss47,048。已知输入缓存68.1919%，审批占已知input+output的35.7175%；reasoning7,690属于output子集，不重复相加。普通/审批输出预算32,768/8,192，未观测超预算。另有原fixture要求的四次能力探测，其provider usage及transport count UNKNOWN；完整调用量与费用仍UNKNOWN。这是失败调试轮，不计入最终冻结八项目标准。

运行HEAD `df248af81e23dfcce4e3acbca841a65570971695`、index为空，source3297与112最终manifest严格一致，JAR SHA256 `66038d993f6d02f1ee17addbde3b1dd36bff81c8c1363b93398ca4f08f85953b`。运行前后源码冻结一致，未改生产、Skill或配置。普通用户CIM正向控制核11组本轮PID/创建时间均已退出；原安全配置pair指纹未变，本轮专属GUID凭据副本已精确删除，调查数据库删除数0。原进程退出检查已经PASS后，父辅助脚本误用残留LASTEXITCODE报错；没有重跑该检查，复用已落盘正向退出证明完成Cleanup及seal，辅助拒绝不改实际部署失败结论。

原证目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/approved-full-business-live-116/task-board-approved-116-01/`。保留maven.log、原XML/TXT及SHA、source before/after、result、sealed-evidence、parent-known-usage、processes-final及credential-cleanup。实际调查库为 `agent-live-task-board-f5ig6ns40`，原App身份 `wtl-agent-task-board-f5ig8iudw`，源码快照引用0932138e-73fa-47f5-8e2d-b28b07a000f8及8b24f2c4-8ebf-45ec-b217-7bb2f4aee42e均保留UNKNOWN。

本轮结束后，117只针对实际传输阶段与Agent安全接续边界作源码核对，不付费补证。task-board完整独立审批部署仍未通过，其他七项目、最终全新服务器冻结八项目、非默认目录真实部署及整机重启恢复仍未验收；历史102纯部署通过、105失败与原58未结清状态继续保留。
