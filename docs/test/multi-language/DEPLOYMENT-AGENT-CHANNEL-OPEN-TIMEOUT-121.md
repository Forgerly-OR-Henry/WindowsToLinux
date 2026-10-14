# 通道打开超时的作者载荷缺席分类（121）

2026-10-14 恢复验收后，先核实 HEAD `c86357a3ef5462a884345931f6fd88e9b88ac3bc`、3299 项源码与119最终清单一致、原 deployment.jar SHA `67123d75998a9988af639ebc08f6b869aaa76e772c7f420e4076ff91326c48cd`，以及原受保护配置 pair 未变。更新后的本地工具链已核为 JDK 21.0.10、Maven 3.9.12、Python 3.12.10、Git 2.54 和 bundled PowerShell 7.6.5/ThreadJob。沙箱 CIM 无权限后改在正常非管理员用户环境核对，不将本机权限问题解释成服务器失败。

恢复时仅执行现有 `DeploymentStaticLiveAcceptanceTest#connectsThroughTheRealPlatform`：1 PASS/0 FAIL/0 ERROR/0 SKIP，8.422 秒，Maven exit 0、源码冻结 exit 0。目标 `45.192.105.213:22/root` 的实际指纹与授权值一致。本次仅产品认证与关闭，没有模型、部署、provider probe 或 Root 直接 SSH。正常用户 CIM 正控制确认本轮10组 PID/创建 ticks 全部退出；唯一 GUID 配置副本已精确清理，原 pair 未变，调查数据库删除零。这是当前连接检查，不是业务部署通过。

## 已确认原因与最小修复

120 的旧 UNKNOWN 保留。当前源码表明，固定 SSH exec bootstrap 不含作者脚本与 stdin；只有 `opening.verify()` 成功后才创建 sender，唯一 `frame.writeTo()` 位于该 sender 内。每次命令创建新的 execution，sender 在运行中不会被清空。因此未来同一次活执行对象的 sender 尚未创建、输入 NOT_STARTED 可以证明作者载荷尚未发送；无输出、无退出回执或 session 仍打开本身不能提供该证明，也不能据此断言 bootstrap 未活动或连接已释放。

修改仅涉及 `SshCommandExecution` 及其测试。timeout catch 先保留已观察到的取消/中断为 UNKNOWN，再严格要求 CHANNEL_OPENING、已请求打开、未确认打开、sender 为 null 且 NOT_STARTED，返回既有 `FAILED/IO_FAILURE/SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT`，诊断保留 deadline 原因及原期限。其它超时继续 UNKNOWN。同时修正明确中断异常恢复 interrupt 后仍落入 TRANSPORT 缺席分类的路径：已观察到的取消/中断直接保留 UNKNOWN，初始发送前取消仍为 CANCELLED。

没有放宽超时、自动重放或修改 Agent 编排。117 的实时原 Problem 对象、原配置与身份、已结清账本、原连接实际释放、模型显式 reconnect，以及新命令重新独立审批/new grant 门禁保持。STATIC、ASSISTED、AGENT 仍各自编排；此次共享修复只校正执行器对其实际发送状态的报告。旧120及其它 UNKNOWN、调查证据和资源记录均未改写。

## 定点验证与产物

只新增并运行 `SshCommandExecutionTest#channelOpeningTimeoutProvesAuthorPayloadAbsenceAndPreservesCancellationAndStartedInputUncertainty`，真实 execution 配合受控 Mina 对象，覆盖11个相关分支：打开期限届满的实时缺席、迟到 open 不发送、deadline/cancel/interrupt 竞争、明确中断异常、已启动 sender 的超时、关闭未确认、初始取消与安全诊断。

| 运行 | 实际结果 |
|---|---|
| linux-01 | 夹具覆写 Mina final 方法导致编译失败，尚未执行测试 |
| linux-02 | 1 FAIL；旧生产行为及夹具收尾断言失败，原报告保留 |
| linux-03、linux-04 | 每轮1 FAIL；8项旧生产行为断言。04在最终无条件收尾夹具上完成；未改生产源码 |
| format-01 | 仅两个 owner，write/check/freeze 均 exit 0 |
| linux-05 | 修复后唯一方法1 PASS/0 FAIL/0 ERROR/0 SKIP，7.561秒；Maven/freeze均 exit 0 |
| package-01 | `-pl src/mod/deployment -am -DskipTests package`，exit 0；未附带业务测试 |

117、119 的原报告及 SHA 已复核复用，没有重跑旧方法、整类、模块或全项目测试。121分类测试没有 Runtime、SSH 或 provider 调用。最终3299项源码仅两个 owner 改变，与 linux-05 测试时字节完全一致。deployment.jar SHA 为 `b84b6b74be06b73eaecf46a620a15aa1bd159425b09861977f4671702dcb7714`；其中1048个 class 与当前编译输出、113项源资源与源文件逐字节一致。共享 Linux JAR SHA 为 `1ef29cf5bccf739f655dcff277286a419a1dc7bafee23ba8e2ffa565d8c2e9b3`，其中执行器 class 与测试后编译输出一致。没有旧119最终归档副本，不宣称新旧 Mod JAR逐条目比较已完成。

完整证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/channel-open-not-sent-121/`。正式增量见 [精确补丁](patches/AGENT-CHANNEL-OPEN-TIMEOUT-NOT-SENT-121.patch)，已在独立临时目录正反应用还原原始 before/final 字节；临时补丁目录已精确清理。原工作树迁移及其它任务改动保留，只提交本文和精确增量补丁，不推送，也不宣称干净 HEAD 可独立构建。

正常非管理员 CIM 最后正枚举306个进程并核对自身，七个验证轮次记录的50组唯一 PID/创建 ticks 全部已退出，未终止其它进程。阶段自有 tmp 已按精确绝对路径和无 reparse 边界清理；原失败报告、旧 UNKNOWN 和调查证据保留。

用户在本阶段验证通过后要求暂停。122 仅准备七个离线 helper，保持 `WAITING_FOR_121_FINAL`、`admissionReady=false`，未复制凭据、未启动付费部署。恢复时先读 CURRENT、复核当前源码/JAR/配置/Git和连接状态，再封定新基线；不要直接执行待准入模板。task-board 完整独立审批业务、其余七项目、新服务器冻结八项目、非默认目录和整机重启验收仍未完成。
