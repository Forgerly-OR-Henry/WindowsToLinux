# Agent 原238恢复审批完整输入修正：255

255完成本地修正版与定点验证，未执行新的真实APP、SSH或256产品恢复。原238仍为UNKNOWN。本记录及同名patch只覆盖255捕获preimage到最终manifest的8个既有源码增量（4生产、4测试），不包含迁移工作树的其它文件，也不以Git HEAD整份源码为差异基线。

最终source inventory为3319项，manifest SHA256 `8bdcb32a96e2a22db672608aa36b7de3a43677c47e19a8767aa98d05f9f29824`。真实包metadata核验exit0，48个部署归属组、3个AI族、4个平台族、5个完整宿主模块，54个classpath入口/3249文件；实际JUnit路径与当前source/target/JAR字节绑定，不是JVM CodeSource。fixture仅编译，未执行实服业务。

deployment.jar为3917160B，SHA256 `8a3a3db409d5f66e29ffa98fa787a665b3961103dfc086210ba269de3efa0c6c`；五个宿主JAR字节未变。metadata SHA256 `5308db42236821b25da3ca97e304945584e8743844c72fc1d5f0ca3fc71ed192`，classpath SHA256 `392aa7034674cde7724c4ad4513ec81cac98ffb1640708d5cc574cc27d3692e9`。本地提交及封存标识由CURRENT和本轮delivery记录承接，本文不写自身提交标识。

## 修正合同

252的原INSTALL被独立APP拒绝且NOT_SENT；254仅提取了绑定拒因，未授权发送。255保留同一固定program字节/version及原OPEN身份，仅修正审批证据呈现和已知未发送拒绝后的协调。

INSTALL使用固定`PUBLICATION_RECOVERY_PYTHON_LITERAL_V1`：`sources.bash`与`loader`各出现一次，controller由三段、installer由五段按原canonical JSON_STRING编码无损重构；严格核字段、类型、引用顺序、Unicode、长度/SHA及真实stdin。program事实改为pointer/摘要导航，避免再次携带完整源码。InputCoverage要求七个全文叶子真实交付，notes/摘要不能填缺口；完整交付只是必要条件，不产生APP批准或grant。

ROLLBACK另有`PUBLICATION_RECOVERY_SOURCE_VIEW_V1`非stdin视图：唯一Bash/loader及controller三段，无INSTALL wrapper；核真实source/loader字节。该view不注册InputCoverage，也不能替代当前request.script内完整loader的交付。READINESS原事实不变。未改变分页8000字符/工具封装14000字符、history或provider预算；无新执行入口、编码或eval。

安装协调仅在完整INSTALL REVIEW=DENY、RESULT=NOT_SENT、无grant/dispatch、owner仍未安装且绑定当前阻断action时释放本地阻断安装变量，历史与action/grant防重保留。`mayInstall`仍false；下一次显式协调须由新的独立readiness严格MISSING恢复资格，再用新action、完整独立APP与fresh grant。UNKNOWN、已dispatch、APPROVE但未发送、缺review或source/version/review变化均不放宽。当前调用不自动循环重发，不将原DENY改写为批准；原producer、归档及任务终态守门未绕过。

## 实际本地验证

| 运行 | 精确范围 | 原始结果 | 打包 |
| --- | --- | --- | --- |
| attempt01 | 7方法、4类 | 15cases：14P / 0F / 1E / 0skip | 未执行 |
| attempt02 | 唯一失败method | 1case：0P / 0F / 1E / 0skip | 未执行 |
| attempt03 | 同一失败method | 1case：1P / 0F / 0E / 0skip | skipTests package exit0 |

最终覆盖7方法15cases全部通过：首轮其余14P与第三轮该method的1P组合；raw三轮17cases=15P/0F/2E/0skip，两份原错误XML/result/log完整保留。首轮后生产源码完全未改，仅`DeploymentCommandEvidenceTest`修本地native continuation fixture：去掉APPROVE的拒绝讨论字段，并提供真实ChatCompletions assistant JSON及与continuationStates对应的实测payloadBytes。未为了测试扩大生产可见性或放宽APP协议。三轮sourceFreeze均0；仅失败组复核，未跑全量。

7个方法：

- `DeploymentAgentHistoryArchiveTest#deniedUnsentInstallationRequiresFreshIndependentAttempt`
- `DeploymentAgentHistoryArchiveTest#pendingPublicationRecoveryNeverResendsUnknownMutation`
- `DeploymentAgentPublicationRecoveryProgramTest#fixedInstallerExposesCompleteDecodedSourceAndCanonicalBoundRequests`
- `DeploymentAgentPublicationRecoveryProgramTest#installationEvidenceRejectsStructuralAndCanonicalDrift`
- `DeploymentCommandEvidenceTest#publicationRecoveryReviewIncludesCompleteDecodedFixedProgramAndRejectsChangedInput`
- `DeploymentCommandEvidenceTest#publicationRecoveryFitsActualStandaloneAndAgentNativeRequestEnvelopes`
- `DeploymentAgentInputCoverageTest#recoveryInstallerRequiresEveryLeafAfterActualDelivery`

通过范围包括已知DENY/NOT_SENT后必须新readiness/APP/grant、UNKNOWN禁止重发、固定源字节锚、结构篡改及七叶子缺失、ROLLBACK view不能填script缺口。真实既有TaskWork/日志夹具仍是本地测试；模拟READY/APP verdict不证明Linux安装、registry、真实APP安全审阅或SSH可用。

第三轮实际INSTALL交付26页、`reducedFrom8000=0`，提前APPROVE被delivery gate阻挡，完整28个模拟native request均经真实Codec编码且不超过1MiB；旧首page已不在最终history。该结果验证送达/封装能力，不证明模型同时保留、理解或批准全文，也不能反推252实际逐页trace。容量测试使用固定fixture输入及16384字符附加事实，不代表所有未来history预算。实际六个初始envelope（standalone/Agent）分别为：READINESS 251286/47250B，INSTALL 227709/42053B，ROLLBACK 231854/50863B。

原固定产物的default-layout硬锚在定点测试通过（来自父对旧冻结编译产物的实际读取，测试不依赖.ai-workspace）：

| 产物 | UTF-8字节 / SHA256 |
| --- | --- |
| version | `4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021` |
| controller | 165726 / `477ca1395417bbf1ec024c7e427066775da8d7861f2d2ee759896d598cd59ca2` |
| loader | 7586 / `46dc35ea0c0c9b2eb5d2f1d4903891d9a62a391d8c80182a01efc9efdc7d0151` |
| installer | 189420 / `0a16f4fccb224865bbcb42a4472070f54ae7f04a786f18e1bc73bda7bdb94d22` |

三轮正常close均exit0，分别核11/11/18个精确PID+创建时间pair退出及各2个自有TMP/UDS目录删除；最终normal CIM核50个已观察精确pair全退、7个自有临时路径全无，验证及capture开关均false，无强杀。50个pair包含三轮验证和收尾器、旧产物指纹读取、两次metadata工具；最终核验器自身的exit0由父观察。

首次metadata工具误把本轮已声明修改的InputCoverage class列为未改动产物而拒绝；修工具使其核当前完整source/target/JAR family，其他未改动产物仍逐字一致，然后实际通过。父首次JUnit汇总脚本因Windows长路径读取失败，改extended绝对IO后核同原XML。两项均为辅助工具失败，未改生产源码、原JUnit结果或原数据库，不冒充实服失败。

`programIdentityProof255.json` SHA256 `45fb96cc3f3d5573b4a97b6b2dd6bc459c89eeec3362c13f0be266587b9aa5cf`，连接原253 OPEN、旧冻结产物指纹、当前Program及测试源码、实际PASS的原始JUnit、最终manifest/metadata/完整Program class family及新JAR。两个纯字节消费者验证器AST一致并实际验证通过；证明仅针对该原default-layout程序身份，不授权动作或判定原Task结清。

## 精确源码增量

所有8个文件均为既有文件。preimage与当前源逐项SHA核同；patch使用三行上下文，保留全部正文和空行；六个evidence源的preimage为LF、当前为CRLF，正式Git文本patch统一LF，仅表达代码增量，不通过整文件替换表达行尾变化。下表仍绑定实际物理字节SHA。

| 文件（deployment模块内） | before SHA256 → after SHA256 |
| --- | --- |
| `src/main/java/gold/debug/windowstolinux/mod/deployment/ai/review/DeploymentCommandEvidence.java` | `0c4aceb799fa8dd06deb13d10627d43b8428668f62f1d93f9bc3ce208b62bb78` → `2ced956fdcb4507a86755b03be1e3e0f97d98ea2fc4d42656f65fc1f8d22d7c8` |
| `src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/DeploymentAgentPublicationRecoveryInstallation.java` | `f6d80e8b0aab315da3d9a85dbde7b83545ee26f061b6cbc765212ca9b4596f58` → `070c409579392977f22ba148cef4e83086c379551ab8d56e21090ec033c94a01` |
| `src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/DeploymentAgentPublicationRecoveryProgram.java` | `6fd5240685dd0dbc596e6b0491921e806d71d0af42c7793e0cedc11e835a19e9` → `961328ecf83a0df607610edb8d188d7cb20ed41145c965d46c3d38e47dd3dbf9` |
| `src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentInputCoverage.java` | `2822cc4cad1d64d2a126f8deee5934fcf5070862e7e20d808ad16a1ff792862c` → `2a8e1f75e72a7370b49f8d1f3ab425e7aad7c89568bb807d6e98c7f04fe1869b` |
| `src/test/java/gold/debug/windowstolinux/mod/deployment/ai/review/DeploymentCommandEvidenceTest.java` | `302fa0e4091e9556a60d2c541b29c34da2684483d6d4d2edbfbbaf554b60f3aa` → `58cc44426a457de23c110d36829932926c948bec3695dd65006648ed0c378d39` |
| `src/test/java/gold/debug/windowstolinux/mod/deployment/db/execution/DeploymentAgentHistoryArchiveTest.java` | `3d19523b38dad2233e6058d0dcc3fabb19cd51c8ceea3753f00a4c0a915022c0` → `ca7993fb2587511865f293567c324afe57919d677ef429a2f69c0f3158d64d2f` |
| `src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/DeploymentAgentPublicationRecoveryProgramTest.java` | `a855afde6b032ece6c22f1638732087c7f81b22564d350494c6e00cef604f570` → `f83c493ff3e4469c101ef1195a4c886197ea66a1bd015e3fe1a99964dd682e76` |
| `src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentInputCoverageTest.java` | `ac3677b29e56a5678fc90af5345758e2debfa103a041fdb6984cd83597162689` → `591086b18229326d0de434876490bf8e89fec8e6b0955d4615b1d8bcf48cdcbb` |

## 后续与停止边界

此修正版不表示原238已结清；新实际APP/SSH及256恢复未执行，原Task仍UNKNOWN。现9次历史FULL=File Transfer 8次+CSV Inspector历史208的1次，完整BUSINESS仍0。194/234不恢复，221不自动恢复；无新FULL或其它Task由此记录自动启动。

继续目标为File Transfer完整BUSINESSv2、真实浏览器与正式收尾，随后串行完成CSV Inspector真实业务及必要收尾。两项完成立即暂停，分别报告FULL独立审批I/O占比、缓存命中率及known/unknown；失败窗口与纯恢复分列，等待真人下一步方向，不自动扩第三项。新协调即使通过本地检查，真实APP仍可拒绝；每个完整动作始终需要独立APP和fresh grant，UNKNOWN不自动重试。

255证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-approval-complete-input-recovery-255/`，入口为metadata-tools的核验摘要、programIdentityProof、parent-final-process-verification及三轮原始reports。本文不统计本地模拟AiUsage为真实provider用量；实际业务、审批占比和缓存仍待后续串行验收。
