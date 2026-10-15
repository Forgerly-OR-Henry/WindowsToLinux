# 185：补齐独立 Agent 审批的工具链输入绑定与适用规则

184 的真实 task-board 验收停在独立审批拒绝 NODE 24 prepare，原任务 `95e595d7-5bee-49d0-ba5a-7de2c3fb9763` 保持 UNKNOWN/BLOCKED。此次修复已证实的审批接线缺口，不改写原 DENY，也不以审批必须批准为成功标准。完整业务验收仍未通过。

## 已确认原因与改动

实际 `DeploymentAgentRemoteTools → DeploymentCommandReviewScope → DeploymentFixedCommandApproval → DeploymentAgentApproval` 使用独立 native APP，跳过旧 Conversation 分支。已有 `DeploymentAgentToolchainInputEvidence.select` 和 `inline-program-review.md` 只在旧分支使用；独立 APP 收到基础 facts、完整分页输入及业务 helper 契约，却没有产品派生的工具链输入绑定和相应适用规则。184 审批理由是这一缺口的调查入口，不直接证明其描述的安全漏洞成立。

仅修改 `DeploymentAgentApproval.java` 及其直接测试。在真实独立 native 审批入口：

- 拒绝调用者自报产品派生 FIELD；从原任务捕获配置取得 layout，复用既有严格 selector，只识别 dependencies、resolve、prepare、bind 四类 typed 原始动作。
- selector 校验完整 renderer body、固定 launcher、argv、stdin 字节与 SHA、targets 和 layout；随后与原 intent、原 CommandRequest、business revision 和排序后的 typed targets 再核一致。只有全部相符才加入产品派生 facts，并进入原 review binding、分页及 coverage。
- 对匹配动作，将已有 inline 审阅规则与 helper 契约组合；SYSTEM 和 `contract` 工具读取同一份适用文本。任意 inline、notes 或伪造 FIELD 不能借用此范围。

派生事实只证明输入一致性，不证明安全、安装完成或取得授权。独立审批仍须审阅完整具体动作及证据，自主决定分页、讨论和裁决；CLOSED DENY、完整 coverage、新 grant、helper 守门及原静态条件不变。实际工具链 root 控制面的格式化、挂载、下载等副作用仍需审阅，vendor 工作的实际隔离也不能由元数据替代。没有凭假设增加 OS 程序 SHA 或禁止受保护路径上的正常 root-owned symlink。

插件 `reviewSource` 分支继续传原 facts/instructions，没有扩大插件合同。STATIC、ASSISTED、共享 renderer、资源规则、history、窗口、预算、超时和错误传播均未改；本轮不合并184的审阅效率候选方案。

## 定点验证

最终只重跑两个受影响的新方法，**2 PASS、0 FAIL、0 ERROR、0 SKIP，Maven exit0/source freeze0**：

```text
DeploymentAgentApprovalTest#independentNativeToolchainReviewReceivesDerivedInputAndTheSameApplicableContract
DeploymentAgentApprovalTest#toolchainInputMismatchAndCallerMarkersFailBeforeNativeReviewOrGrant
```

第一个方法通过真实 Scope→Fixed→native mock 接线，覆盖 NODE 24 prepare 的原任务自定义 layout 和 resolve；核对 SYSTEM、派生 facts、contract 工具的一致性，刻意使用不同的业务版本与审批配置版本，并验证独立 DENY 保持 CLOSED、零 grant、不自动重开。第二个方法覆盖 body、argv、targets、layout、exactAction、原 intent targets、原 request 和伪造 FIELD 篡改在模型调用/grant前拒绝，以及任意 inline 不借用适用规则。

此前 test-02 执行6个具体方法、7次用例，全部通过。随后收尾发现新增校验错误要求业务版本等于审批配置版本；两者描述不同对象。移除此等式，原请求重建改用自身 intent.revision，最终 test-03 只运行上述两个受影响方法。test-02 其余五次不进入 recognized-inline 分支的检查继续复用，未表述为在最终源码重新执行：

```text
DeploymentAgentApprovalTest#incompleteExecutableApprovalReportsRealGapsBeforeOnlyFinalIndependentVerdictGrants  (request/evidence 两 case)
DeploymentAgentApprovalTest#missingInputCanEndDenyClosedWithoutAutomaticPagingOrReopening
DeploymentAgentApprovalTest#diagnosticCommandStillObtainsExactHostGrantWithoutAnyApprovalModelRequest
DeploymentHelperReviewContractTest#contractRequiresItsIndependentlyPinnedSourceAndCompleteGuard
```

最初 test-01 因新增测试误调用跨包 package-private SHA 方法而测试编译失败，0方法执行；改用现有公开 `DeploymentAgentInputs.hash`。原失败日志、test-02 的完整7PASS、修正前 final-01 与最终 test-03 证据分别保留。两 owner 定点格式 write/check 均exit0；没有全类、模块或全项目测试，没有真实 provider、SSH 或 Runtime。

正常用户 CIM 核六轮共56组已观测 PID/creation UTC ticks全部退出，cleanup进程亦退出；自有 marker、creation 与 noReparse 绑定的TMP/短UDS已清。父独立核12个封存原件、原XML、3299项当前文件及两 owner边界，并在自有scratch实际执行Git apply正逆check及应用，原/最终字节逐一相同，scratch已清。临时校验命令显式关闭全局autocrlf转换，没有修改全局Git配置或实际源码/index。

## 源码隔离与证据

工作树保留原迁移及其它任务改动，部分实际源码不在HEAD。提交采用本记录与[精确增量补丁](patches/AGENT-APPROVAL-INLINE-BINDING-185.patch)，相对本轮 before 原字节；不能将其解释为缺少迁移的干净HEAD可独立构建。仅该补丁路径设置 `-text`，保留原字节；正文与属性按默认空白规则检查，unified diff上下文按补丁语法检查。

3299项 before manifest SHA `464a949dfa5e276e5544e18127ea575524452ec3e76724a7449cdbb3ccb193d3`，after SHA `cc3e0d51d4c228b670eaa1e9c0eb03a77970ff6b6c09d30fcbbf47feea81f444`，只有两 owner变化；patch SHA `73731f97b42957b80ec720f5c3467dd1156da04550531c2b6f97fba5db48fa89`，19805字节。

原件位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-approval-inline-binding-185/`。本地实现seal SHA `6a33920f52a629e3a2cb439ddab990cc8ea3881ccfb0d9546c5efff96d2803f3`；父独核 `parent-verification185.json` SHA `16f160403d450ffea8736b380fae3b1a36e7f12ef61ba55c6d312144a56bce3c`。184原DENY/UNKNOWN、正式数据与远端资源证据不动。

随后唯一执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、16.143秒；日志14处明确跳过测试，无测试执行记录。前后3299项源码与上述最终 after完全一致。正常CIM核12组自有PID/creation UTC ticks全部退出，自有TMP与短UDS已精确清理，没有读取或删除原数据。

最终 `deployment.jar` SHA `c2a31885f9ee9715f45b0c3b2667188453e2251bfe52455ca793cb237448a1ba`；JAR内 `DeploymentAgentApproval.class` 与target相同，SHA `39c95c126a1060a8eb23a8328b6ad833a1fe0c191a36c046c96a835204c528da`。宿主AI JAR仍为 `a7d283d6bd8fa23ad415d9ef8729e7e6269ee60cf8845f65b87a478760119353`，两个HTTP类仍为 `5f2f45c8ddf0bc2afd09786e7c4bd8803e6978c7197dbab3146a0bf07480a43c` 与 `b3ac78b0599f05c36bf5e529292cdc78dd1d488cbadeaeb7776800ef9db6c603`，分别与宿主JAR内成员一致。原验收fixture class SHA `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421` 不变。

打包原件另存 `approval-inline-package-185/`，seal SHA `dc79cc11298b4c2f7755fe4237d50658edba1da0b0bd54bed3185276de12bbe5`。父独核37个原件及当前六个构件、JAR成员、3299项前后清单和物理收尾，通过；`parent-package-verification.json` SHA `6c259852f3700fa3ae629a3943fe686460d73b595aac1312cdf6e322244e6b5e`。未进行真实provider、SSH、Runtime或DPAPI操作。真实独立审批、前后端、浏览器及产品 STOP/DISABLE 仍需下一轮通过正式入口验证。
