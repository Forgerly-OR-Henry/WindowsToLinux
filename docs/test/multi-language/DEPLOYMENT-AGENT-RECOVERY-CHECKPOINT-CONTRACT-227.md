# 227：原任务恢复检查点合同修复与本地定点验证

日期：2026-10-16。结论：恢复进度新增专用宿主能力，8个具体方法、20个JUnit用例通过；唯一显式跳测打包成功，原收尾工具因AI JAR整包哈希变化拒绝后，独立只读构件扩核通过。未重新调用原194产品恢复，未访问真实模型或SSH，不是业务验收通过。

## 原因与修复

[226实际取证](DEPLOYMENT-AGENT-RECOVERY-CHECKPOINT-DIAGNOSTICS-226.md)已将原194错误完整消息及SHA定位至`MOD_TASK_BINDING_CLOSED`。恢复流程缺进度行时调用普通`beginCheckpoint`，与宿主对recoveryOnly绑定及reconciling阶段的两道独立禁令冲突；224代理测试未覆盖真实宿主准入。本轮保留这两道禁令，不从缺行推断此前未发送。

5个生产owner、4个测试owner，均为既有文件；实际源清单3310项，仅这9项改变，无新增生产文件。生产改动为TaskService、PlatformTaskRuntime、TaskAuthorCheckpoint、PlatformModContext、DeploymentAgentMultiPreparationCompensation：

- 新增`beginRecoveryCheckpoint/findRecoveryCheckpoint`，仅为实际原任务根owner/version当前显式恢复回调保存进度，不授予审批、SSH或重放权限。
- 新句柄每次snapshot/advance/close重新认证真实TaskContext、根owner/version、未撤销绑定、reconciling、无provider scope及同次宿主token。token仅在原恢复回调实际调用紧前建立，内层finally清除；旧句柄不能在下一次恢复中复活，须重新find。
- 复用现有CUSTOM存储、完整Snapshot CAS、12000 UTF-8字节限制、单次key及released证据语义。固定新protocol为`recovery-checkpoint.v1`，ID为`owner/recovery-checkpoint/key`，新用途只认持久根身份；普通AUTHOR协议、ID、委托逻辑及错误分类保留。DB保持版本1。
- raw retain不能表达含连字符的固定kind段，也不能自选recovery_json协议；新查找遇EXPLICIT、错误协议或畸形封套报错，不当missing。Agent发现同key旧author行时保守拒绝，不迁移或覆盖旧依据。
- 原候选全体absence、完整prefix/CAS/引用核验、Mod proof提交后才释放占用、未知不重发等路径保留；APP策略、grant、SDK、STATIC/ASSISTED编排、HistoryArchive及旧UNKNOWN均未修改。

## 实际验证

唯一格式执行write/check/source-freeze均0，仅9个明确文件。唯一Maven定点执行为`-B -pl src/mod/deployment -am -Dtest=<以下具体方法> -Dsurefire.failIfNoSpecifiedTests=false test`；父独立解析4份原XML并复核8份报告SHA，20次执行全部通过、0失败/错误/跳过，无重跑。

| 具体方法 | 实际case |
| --- | ---: |
| PlatformTaskRuntimeTest#recoveryCheckpointUsesAuthenticOriginalReconciliationAcrossAttempts | 1 |
| PlatformTaskRuntimeTest#recoveryCheckpointRejectsEscapedRevokedForeignAndRawClaims | 1 |
| PlatformTaskRuntimeTest#previousBootRecoveryUsesOriginalVersionAndRejectsNewBusiness | 1 |
| TaskAuthorCheckpointTest#recoveryCheckpointRetainsDistinctProtocolAndExactCasAcrossReopen | 1 |
| TaskAuthorCheckpointTest#exactValueCasAndSingleUseKeySurviveRelease | 1 |
| DeploymentAgentPreparationRecoveryTest#multiPreparationProgressUsesHostRecoveryCheckpointAndReleasesOnlyAfterProof | 1 |
| DeploymentAgentPreparationCompensationTest#multiPreparationDispatchAndReentryNeverRepeatUnknownCleanup | 9 |
| DeploymentAgentPreparationCompensationTest#multiPreparationProofCommitAndFinalStateFailuresRemainIdempotent | 5 |

宿主新方法使用真实PlatformTaskRuntime、TaskService和测试自有H2，覆盖recoveryOnly绑定在真实reconcile内缺行首建、CAS及下一次恢复读取；普通author两禁令、回调结束/跨scope/撤销、foreign owner/version、复制context及raw近似claim拒绝。数据库实际重开验证新旧purpose隔离、精确CAS、畸形封套拒绝及released行保留。

Mod集成使用正常enabled加载后的真实PlatformRuntime/RecoveryTasks/TaskService及Mod proof事务，验证公开合成absence回执下的进度、proof先于占用释放；该用例不是recoveryOnly Mod重启。真正recoveryOnly绑定由宿主用例覆盖。ACK丢失由真实DB CAS后抛错包装器模拟，wouldDispatch仅本地计数；原14参数例仍是NativeAiFixture/MemoryCheckpoint边界。未动态构造active nested provider scope，生产该守门已审；不冒称真实断电、SDK或Linux验证。

唯一打包命令`-B -pl src/mod/deployment -am -DskipTests package`返回0，没有新增测试。原after核验返回1：工具误把本轮被reactor重新生成的AI JAR容器哈希要求为旧值。保留原runner/capture/result及失败stderr；不重打包、不重测试，只新增独立只读post核验：

- deployment 29组（21类族57成员、8资源）、冻结AI 3类族5成员，以及本次API/DB/service/main 4类族29成员，源/target/JAR逐成员核对通过。
- AI完整target 29文件与225已封存classpath逐字节同，当前JAR完整成员恰为该29文件及3项受核Maven元数据，逐字节一致。内嵌pom等于当前冻结源码，manifest无额外执行入口，坐标精确匹配。
- 旧AI JAR没有完整字节副本，不宣称整包差异仅为ZIP元数据；上述证明也不是JVM CodeSource或远端运行证明。独立post capture实际exit0，原after=1没有改写。

最终源清单SHA为`a129196bd27cd1aab3650005a42a797c820c1dd41ad1139e67e7876328518fe6`。deployment.jar SHA为`f4b82ec46c9d4cd7471805c454f8d31874ee6235962278b2c2eed693d3e7f9b7`；AI JAR为`0ed29bfbaeb8912aba1bc938ed027d71a65c177bd37ea2e3387d26a83fd37d80`。六JAR完整摘要见本轮parent-package-verification227.json。

## 隔离与后续边界

原9份dirty字节已保全。本任务最终增量见[精确补丁](patches/AGENT-RECOVERY-CHECKPOINT-CONTRACT-227.patch)，SHA`138796e7503cc566603c1c1988a28b8fa7b1ca2dc9dc95042166195610d82f4a`；在自有副本真实git apply正反重建逐字节一致，当前源码reverse-check通过，副本已清理。原迁移及其它未提交改动保留，不把完整混合源码纳入本次提交。

正常用户Close及父独立CIM核49个观察到的自有PID/creation pairs全部退出，格式TMP、Maven TMP/短UDS均消失；共享格式cache保留。父额外CIM工具曾把数字0误按数组计数，主体CIM/删除前拒绝，保原脚本后仅修该解析并核通过，不计为产品测试失败。原194 DB/bootstrap字节和原UNKNOWN证据未变，0原DB打开/DPAPI/真实provider/SSH/新FULL。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-recovery-checkpoint-contract-227`，入口为parent-source-review、parent-unit-verification、parent-package-verification、parent-process-closure及implementation/patch-byte-verification。228产品恢复工具仅完成未放行草稿；仍须新基线、当前必要pair私有保全和本轮逐动作独立审批新grant，不能据本地通过自动认定原194可结清或复用旧批准。

CSV Inspector与File Transfer保持严格串行，本轮无新FULL或审批/cache收益；225纯恢复APP100%不参与部署趋势。两项目各自调整及真实验收完成后再按FULL范围评估，审批仍明显偏高则按真人要求暂停讨论。原完整业务、冻结八项目、非默认目录及整机重启验收仍待。
