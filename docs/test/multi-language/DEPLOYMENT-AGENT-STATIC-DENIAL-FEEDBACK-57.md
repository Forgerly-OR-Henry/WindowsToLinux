# Agent 发送前静态拒绝反馈：57

已完成最小本地修复及直接验证，尚未启动新一轮真实部署。新增11个不同边界案例最新通过；原完整 UNKNOWN 恢复结清检查仍有3个失败，不能表述为全部检查通过。

56真实纯部署在 `DeploymentCommandStaticGate.requireAllowed()` 抛出静态拒绝后终止，模型未收到下一轮工具反馈。本次生产改动仅位于 `DeploymentAgentSession.executeCommand()` 的首个静态门调用：严格捕获其 `STATIC_INVALID/DENIED`，转换为既有 `IllegalArgumentException`／`TOOL_REJECTED`，由原 native history 返回下一模型请求。反馈包含固定错误码、此次输入 `NOT_SENT`、原任务、受限组件身份和脚本 SHA，null 为 ABSENT，不输出命令正文。

拒绝发生在候选准备、命令执行记录和发送之前，不伪造 dispatcher 凭据或回滚证据，也不声称前序效果已结清。模型自行选择不同合法输入、其他获授权工具或停止；新命令继续接受完整独立审批并按原字节发送。安全门之外的相同错误码或其他 `SecurityException`、实际命令 UNKNOWN 仍沿原终止路径处理，不重放。

实际只改 Session、现有 WorkflowTest 和新增 StaticDenialFeedbackTest，共3路径。共享 Gate、AgentReview、Protocol、Scheduler、ModelToolLoop、STATIC、ASSISTED、模型资源、Skill、配置和数据库版本1均保持原字节，没有固定部署步骤或模型特化。

11个新增不同案例覆盖：三个真实 Codec 的拒绝反馈与模型自主改命令、三个 Codec 完整同参拒绝的有界停止、三个安全门外异常消息的原异常传播、直接无效输入零准备／账本／发送副作用，以及实际合法命令 known=false 后 UNKNOWN／不再请求或发送。重复输入验证包含原 assessment，两次日志均确证静态拒绝码和脚本 SHA，不把 assessment 校验错误算作静态门拒绝。无效文本已被原 Protocol 提前过滤，因此其静态 INVALID 仅通过直接 Session 边界验证，未伪造 native 闭环。

测试均显式限定方法，使用本地受控模型、真实 TaskService／JDBC 和受控远端。首轮13案例后，只重跑失败方法及新增的直接 UNKNOWN；复用已通过的主闭环和无效输入结果。后续仅重复拒绝方法补精确日志断言，不再次执行整组。实际历史26次执行含9 FAIL、3 ERROR、0 SKIP；另有1次夹具编译失败未执行案例。全部失败和修正轮留存，未运行模块或全项目测试。

原 `nativeProtocolsBlockUnknownCommands` 三参数完整恢复检查仍失败：命令进入 UNKNOWN 后出现受控清理、`AI_RECOVERY_SETTLED` 和 Agent journal FAILED，但平台仍 UNKNOWN，阻塞原因为 `Business result requires explicit reconciliation`，原 ENDED 断言失败。未做基线对照证明本次 catch 对该失败的因果独立性；没有改断言或扩大生产恢复修复。新增直接 UNKNOWN 通过只证明停止边界，完整恢复结清仍待调查和验收。

3个 Java 文件 IDEA 格式写入／检查及跳过测试打包通过。父独立核验 before3295／after3296 全部原像与当前文件，实际仅3 owner、nonowner0；真实 Git 在独立目录正向／逆向应用完整补丁后逐字节匹配，新增测试逆向后不存在。JAR 的9个 Session 类条目与 target/classes 相同，113个生产资源与当前源码逐字节相同。JAR SHA 为 `688093fa9adb8c0beffd0b787e60ed03c8241ec9b874175c2717f9208fe2252d`。

完整增量见 [AGENT-STATIC-DENIAL-FEEDBACK-57.patch](patches/AGENT-STATIC-DENIAL-FEEDBACK-57.patch)，SHA 为 `3fdb273583e08f1d3903d4eaaa91979df200dad9dc92426412696ce153c8f915`。Session 和 WorkflowTest 混有原迁移，本地交付保留原工作区，精确自有增量由该补丁承接；仅新增独立测试、本文及完整补丁直接暂存，不整体提交混合文件，不推送，未证明独立干净 checkout 可构建。

父当前正常用户 CIM 正控制通过，19组精确 PID／UTC 创建时间均已退出，无人工终止。证据保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-static-denial-feedback-57/`，父核保留于 `agent-static-denial-parent-57/`。没有真实 provider、SSH、生产数据库或真实部署 API 调用；未访问原48 UNKNOWN 数据库或改已发布 App／持久数据。

下一步回到新 task-board 完整纯部署，审批模型整链关闭，运行期间冻结源码和 Skill。完整前后端业务、浏览器、重启和产品收尾通过后才逐步加入独立审批。其余七项目、真实恢复清理、非默认目录真机／重启及最终冻结八项目实际成本目标继续保留。
