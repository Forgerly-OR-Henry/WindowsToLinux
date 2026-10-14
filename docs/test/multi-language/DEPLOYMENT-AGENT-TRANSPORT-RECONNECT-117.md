# Agent 初始容量连接失败与自主重连（117）

116 的新 task-board 独立审批部署在首次 plan 容量查询时发生 `SSH_COMMAND_TRANSPORT`，作者输入尚未开始，任务 UNKNOWN。117 修复未来同类故障的可信分类及模型继续入口；116、58 等历史 UNKNOWN 保持原状态，不用旧日志追补未发送证明。

共享 SSH 执行器只有在传输故障、已经请求打开通道、输入 sender 对象尚未创建且输入为 NOT_STARTED 时，返回 FAILED/IO_FAILURE 的 `SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT`。固定 bootstrap 可能已经活动，不能声称整个 SSH 命令没有发送。sender 存在或已有输入、取消、超时及其它不确定故障继续保留原 UNKNOWN 处理。诊断包含作者 payload 与 sender 的实际状态。STATIC、ASSISTED、AGENT 共用此下层执行器，编排和审批仍各自独立；本阶段未完成三模式真实部署验收。

Agent 的初始 plan 容量路径，仅在尚未接受计划、没有候选和准备结果、原账本及命令均已结清、原配置与来源仍绑定时，接收执行器实时返回的精确 NOT_SENT 失败。原连接必须实际关闭并释放资源，才将有界错误反馈及 `reconnectAvailable` 交回模型。新零参数 `reconnect` 工具由模型自行选择，固定原任务、服务器、地址、用户及真实主机指纹；信任撤销、身份或配置漂移、未结命令及转移均不能重连。工具只重新认证，不重放命令、预算或计划。新计划仍由模型另行提交，新容量命令必须重新独立审批并取得新的一次性 grant。替换连接由 Agent 工具所有者正常关闭。

未重连时误调用 inspect 只反馈本地 `RECONNECT_REQUIRED`，不提交无效的新审批、不执行命令，也不丢失原缺席证明。UNKNOWN 或资源关闭失败仍阻止继续。实现限于八个 owner：共享 SSH 执行器及其测试、Agent Session/RemoteTools/DeliveryPort/Protocol、新内部连接 holder 与新 Agent 测试；未修改 Scheduler、公共 Linux API、Workflow、Skill、数据库版号或配置。

只运行两个具体方法：`SshCommandExecutionTest#authorPayloadNotSentBeforeSenderRetainsKnownFailureAndStartedSenderRemainsUnknown` 与 `DeploymentAgentPlanReconnectTest#planCapacityNotSentRequiresExplicitModelReconnectAndFreshApproval`。最终 Linux-02 为 1 PASS/0 FAIL/0 ERROR/0 SKIP，Agent-08 为 1 PASS/0 FAIL/0 ERROR/0 SKIP（20.95 秒）。Linux 使用真实执行器和受控 Mina channel；Agent 使用真实 native 分派、产品任务及审批 grant，AI/远端为受控替身。正例证明显式重连、无自动重放、两次独立审批和不同 grant；负例覆盖 UNKNOWN、关闭失败、配置与身份漂移、信任撤销和未结账本。Agent 正例按夹具的 unable 结束于计划验证，不代表部署成功。

失败轮原日志/XML/TXT 均保留。Linux-01 的 channel 夹具类型不匹配、Agent-01 编译缺现有 facts 参数、Agent-02 缺正式 workflow scope、Agent-03 unable 断言不匹配均只修对应问题。Agent-04/05 已通过；信任撤销负例加入后，Agent-06/07 被前一个故意未结负例的真实 requireAvailable 门挡住，未进入重连逻辑。Agent-08 只调整夹具排序与异常收尾，未删除未结账本或放宽生产门禁。已通过 Linux 方法没有再跑，未跑整类、模块或小规模全量。

最终 format-04（20:06:07—20:06:19 +08）八 owner write/check 均 exit 0；package-03（20:06:22—20:06:49 +08）明确 skipTests，exit 0。最终 3299 条源码仅八 owner 变化，Agent-08 测试字节与最终源码完全一致，原 XML/TXT SHA 通过父核；此前 package-01/02 保留并仅对应早期版本。最终 deployment.jar SHA 为 `9b53d5db4c4e78f4ae4df58c841310eef3b9b27056189dc47580ac7d3785db27`。精确增量补丁正反应用均还原原 before/final 字节，自有 p117 校验目录已按原路径和 reparse 边界核实后移除。

证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/transport-not-sent-reconnect-117`，包括 `parent-final-verification.json`、最终两方法原报告、format-04/package-03 与失败轮原记录。20:08:12 +08 普通用户非管理员 CIM 正枚举 387 进程并确认自身，171 组本次已知 PID/创建 ticks 均已退出，未终止其它进程。本阶段真实服务器部署、恢复、浏览器业务与 provider 调用均未执行；本地 Agent 夹具实际打开 Runtime 并启动开发 Mod，不能把 liveRuntime=0 解释成全部 Runtime 调用为零。下一阶段只启动一次新 task-board FULL 独立审批产品部署。

正式增量补丁见 [AGENT-TRANSPORT-NOT-SENT-RECONNECT-117.patch](patches/AGENT-TRANSPORT-NOT-SENT-RECONNECT-117.patch)。原迁移与其它任务改动保留；提交仅包含本阶段文档及可正反应用的精确增量补丁，不推送，不宣称干净 HEAD 可独立构建。
