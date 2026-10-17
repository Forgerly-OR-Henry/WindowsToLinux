# Agent existing-only 早拒绝回执合同 285

FULL284 中，已有 controller READY 后的 Go prepare 实际返回 FAILED/65、完整单行 `TOOLCHAIN_FAILURE=isolation:EXISTING_INSTALLATION_REQUIRED`；下一 Node resolver 成功，但 controller READINESS 被 `DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED` 阻断。源码确认旧 typed precreation proof 只接受空 stdin/store loader 的 HOST_SPACE_REQUIRED 合同，不能识别当前 existing-only 完整 stdin，因此 finish 保留 checkpoint。原 284 的 UNKNOWN、候选和证据不回填、不结清。

## 最小修正

六路径窄 patch：生产 FailureEvidence、隔离 Probe，三个相关测试类及 live fixture。没有修改 controller Python、已安装版本、共享 SSH 或 STATIC/ASSISTED 原生编排。

- 仅对当前精确 existingController PREPARE 增加版本化 typed proof：固定基底源码身份及字节、唯一 guard 插入、完整 stdin、规范 launcher/argv、branch/exact/release descriptor、原 live INTENT 的 action/task/server/layout/operation/index/exactAction 必须联合一致。
- 当前 SDK 必须实际 FAILED、IO_FAILURE/SSH_COMMAND_NONZERO、exit65、未截断、空 stderr、唯一完整拒绝行。不同源码/参数/结果、额外输出、不确定或历史 FAILED 均不能产生 proof。
- 复用原 ADVANCE → RESULT → CLOSE 顺序。任何持久化阶段失败继续保持未确认；失败仍是 FAILED，不伪造成功安装、binding 或 SDK 回执。ROOT 和 `.prepare.lock` 可能已存在，只证明本 operation 私有资源尚未创建，不表示零副作用或 cleanup complete。
- proof 保存执行源码摘要、长度及完整 exactAction；留存校验绑定原 immutable index/request 和原 RESULT，不随今日 renderer 重新解释历史结果。旧 HOST_SPACE_REQUIRED 合同保留。
- 真实 284 两候选成为第六组保护对象。metadata 行数 Java/Python 两处由 96 改为 128；24576 B、20s 协作总预算、30s SDK、四批查询、unit 批上限 96 等边界保持。实际 94 行/19236 B 追加 22 行的保守投影为 116 行/23616 B，余 960 B；这是本地投影，不是远端证明。

## 实际验证

offline Maven 显式 `-pl src/mod/deployment -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false package`，只选择七个直接相关方法：

| 方法 | 结果 |
| --- | --- |
| ProgramStateTest#existingOnlyRefusalRequiresLiveTypedProofAndDurableOriginalResult | 1P；live proof、旧 FAILED 不追认、结果变更及持久化故障 |
| ProgramStateTest#existingOnlyRefusalRejectsUnboundAndUncertainEvidence | 24P |
| ProgramStateTest#precreationRefusalReleasesOnlyAfterDurableOriginalResultAndRetainsFailure | 1P；直接受影响旧合同 |
| ProgramStateTest#precreationProofRejectsUnboundMalformedAndUncertainResults | 33P；直接受影响旧合同反例 |
| CommandsTest#existingOnlyRefusalAllowsFreshNodeResolutionAndReadinessWithoutUnconfirmedCheckpoint | 最终 1P；真实 Commands/channel 消费者，Go→新 Node 各 resolve/READINESS/FAILED prepare，六独立新 grants、四 checkpoint released、两 FAILED 保留 |
| IsolationTest#completeSixRetainedNamespacesStayWithinBoundedReceipt | 1P；116 行通过；合法短保护域 129 行完整 frame 22380 B，在 byte cap 内被 row cap 拒绝 |
| IsolationTest#fixedPythonProtectedProbeUsesSameFiniteMetadataBound | 1P；固定 Python AST 受控执行，116 行及 129 行拒绝 |

表中类名前缀分别为 DeploymentAgentToolchainProgramStateTest、DeploymentAgentToolchainCommandsTest、DeploymentAgentIsolationTest。

test-01/session82066 实际退出 1，UTC 14:54:28.5879523Z—14:54:48.2899488Z：62 例次为 61P/0F/1E/0Skip。唯一错误是新增 Commands 测试从 retain 后空 value 取 exit；产品原合同把实际退出码保存在失败附件中。只修测试 Go/Node 两处断言，核 actualExit65 及 transport/product FAILED；已过 61 例不重跑。

test-02/session61824 仅重跑此 Commands 方法，实际 1P/0F/0E/0Skip、package 退出 0，Maven 17.659s；UTC 14:55:55.8615275Z—14:56:14.4190104Z。最终七方法全通过；物理累计 63 例次为 62P/0F/1E/0Skip，首次错误保留。没有全量、模型或 SSH 调用；未重复已过且未改的批量观测、总预算、真实取消等方法。

六源在最终 package 开始后未改变；deployment.jar 4249207 B，mtime 实际在该窗口。CIM 本轮 Java 0、terminated 0；核 owner、绝对边界及无 reparse 后，仅清 c285a/u285t。辅助原始字节、失败及最终报告保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-after-second-resolver-285/`。

## 交付及后续边界

工作树保留前序迁移与其它改动，只交付相对本阶段原字节的六路径窄 patch，373 增/25 删，reverse check 通过。仅本地提交阶段生成十项限定摘要：六源、JAR、patch、本说明及 284 LIVE；不做全源/依赖 hash，不推送。

没有新增工具链安装能力。existing-only 缺失时仍明确拒绝；正式 238 记录证明模型已有候选内 CLI 路线可选（Go 官方摘要及构建成功，整命令因后续缓存清理 exit1，不能改记整体成功）。下一次新 File Transfer 仍由模型自主选择，先经产品真实容量准入，每个动作独立 APP 和全新 grant；不逐步提供命令或直接 SSH 接管。

原 194 不恢复，238/280/281/282/283/284 及候选、queued post 保留，不改判、不清理、不重放。新端口拟用 49290/49291，六保护和 3693 B requirements 已准备，尚无本轮远端 READY。File Transfer 完成 BUSINESS v2、真实浏览器和 STOP/DISABLE 才进入 CSV；两个项目完成后暂停报告审批占比和输入加权 cache，失败 FULL 与纯恢复单列。
