# Agent 发布收尾安全诊断：60

已补齐 Agent 专属收尾观测，并完成单一故障方法验证。仅增加诊断，58真实根因仍未知、原任务未恢复；未新付费部署，也没有清除 UNKNOWN、补登记 App 或接手远端操作。

生产改动仅 `DeploymentAgentPublishRunner`，测试仅其现有测试文件。记录资源释放、模型命令释放、清理证据、候选清理及 finally 命令释放五个固定阶段的 CALLING／RETURNED。返回标记仅表示调用返回，不能代替清理回执或业务成功。错误记录保留 ProblemCode 枚举及五个源码确认的安全 eventCode；其他 eventCode、异常消息只保存 SHA，null 为 ABSENT。不保存原命令、凭据、context 或堆栈。

诊断存储失败不会阻止原调用；错误记录失败附在正在传播的原异常上。原 finally 仍无条件释放模型命令，其原有异常覆盖优先级保持不变；未证明58发生了双故障。释放条件、候选清理、正式 App 登记顺序、UNKNOWN、独立审批和重放边界未修改。共享 Task API、OriginalCommit、其他模式、Skill、配置及数据库版本1保持原字节。

仅执行 `DeploymentAgentPublishRunnerTest#resourceReleaseFailureRetainsSafeFinishPhaseAndOriginalApiException` 一次，三个参数全部通过，0失败／错误／跳过，原 XML 耗时0.498秒。受控 Frame 在验证与 finish 评估通过后，单点注入资源释放 API 异常；验证阶段／错误码／安全过滤、原异常同实例传播及未进入候选清理。未跑整类、小范围全量或已通过的其他方法。正常成功路径与双故障没有新增运行验证，57原三个完整恢复失败继续保留。

两个 Java 文件格式写入／检查通过，跳过测试打包通过。父独立核验3296条 before／after 原像及当前文件，实际仅两个 owner、其他源码零变化；真实 Git 在独立临时目录正向／逆向应用增量后逐字节一致。JAR 三个相关类与 target/classes 相同，两个直接模型资源与源码及 target/classes 相同。JAR SHA 为 `9efd85161a73359851659e9524bfaa126927e363b5251ba4266173f980370283`。

完整增量见 [AGENT-PUBLICATION-FINISH-DIAGNOSTICS-60.patch](patches/AGENT-PUBLICATION-FINISH-DIAGNOSTICS-60.patch)，SHA 为 `2533fe3626d36777d29eb1e0df3d5741bc319ec9b41710279e04f3feabf67bc7`。两个源码文件混有原迁移，精确自有增量由补丁承接；仅正式文档和补丁直接暂存，不整体提交混合文件，不推送，未证明独立干净 checkout 可构建。

三个验证启动器的精确 PID／UTC 创建时间已退出，父正常用户 CIM 正控制通过，无人工终止。辅助封口首轮因类前缀漏 tools 而失败的记录保留；仅修正辅助脚本，没有业务重跑。证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-finish-diagnostics-60/`，父核位于 `agent-publication-finish-parent-60/`。真实 Runtime、provider、SSH及原调查数据库读写均为0。

继续以 task-board 完整纯部署为目标，审批模型整链关闭。先利用既有59元数据检查本地可复现的收尾问题；只对实际故障点修改和验证，不用本地诊断通过代替真实业务、浏览器、重启或恢复验收。
