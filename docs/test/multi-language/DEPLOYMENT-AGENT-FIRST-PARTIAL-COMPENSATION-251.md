# Agent 原生首次部分发布补偿：251 本地修正版

日期：2026-10-17。**本地17个定点方法最终44例全部通过，skipTests打包及字节metadata核验完成；尚无本轮实服补偿或结清结论。** 本文与补丁只交付250实现及251诊断修正后的精确源码增量。提交与250/251封存仍由父任务后置完成，不把定点结果称为全量验证。

补丁：[DEPLOYMENT-AGENT-FIRST-PARTIAL-COMPENSATION-251.patch](DEPLOYMENT-AGENT-FIRST-PARTIAL-COMPENSATION-251.patch)，268196B，SHA256 `e3936e764d428f40553055ad111dfb4e2b588ff5db2f7aa7a58a4e1e9be26dc9`。

基线为250 before-manifest（3312条）及before / additional-before-input / additional-before-capacity原字节快照；当前251 after-manifest（3319条）SHA256 `088988fb02ef6b9687378cbffb00f1ab3704167bce9d20dfeeab0b79e1285160`。**16个精确delta＝7新增、9既有；12 production、4 test。** 既有文件只表达相对本轮before快照的增量，7个新文件用 `/dev/null`。不以旧HEAD代替迁移中的基线，不把其它dirty/untracked迁移源码整体列为新增。

## 实现范围

原Agent任务在已有部分发布、缺FINISHED的恢复分支中，仅对严格可证明的原生首次发布部分效果形成有界补偿。原Task/execution/template、事件前缀、完整图、component/unit/owner/candidate/release/layout/target/profile/model revision均须保持原绑定。Plan、Intent、实际result、观察及Verified事实按版本化协议保存；不能补造FINISHED、原成功ACK、STANDARD checkpoint、successor或正式App来跳过门禁。

新增固定sidecar与不可变安装namespace。只读取并复用原installer模板、原helper完整资源片段及原catalog/layout/build-entry渲染规则；不借旧toolchain PREPARE/BIND安装协议。原helper protocol16、全局SHA pin及旧资源/dispatcher/loader/manifest/state/路径保持原样，新增定点测试已在attempt04通过本地render逐字核同；不等于远端安装或Linux运行通过。sidecar不包含旧dispatcher/job片段，末尾strict no-job检查遇存在或悬空job即拒绝，不先执行旧orphan cleanup。

唯一guarded-first-rollback在同一Bash/application锁内，核原expected CURRENT、owner、candidate `.agent-task`/deployer、build停止及进程/cgroup空、snapshot不存在、原maintenance token、唯一允许retained release、native DAEMON unit完整字节/FragmentPath/no drop-ins；通过后调用原first rollback。随后确认CURRENT/unit/attempted release absent，再复核并删除精确原maintenance marker，不持锁exec旧helper或再次取同一maintenance锁。candidate清理另走既有精确task-bound正式动作，保留其真实stop build/workspace清理等副作用；持久数据、配置和toolchain不借机删除。

readiness只读核完整immutable source与安装receipt。明确MISSING与未知安装分开；残留staging、缺receipt、路径/权限/身份不安全或未知传输不授权自动修复、删除、重装。安装及每个后续动作的完整source、loader、installer、stdin长度/SHA、typed argv、目标及原业务证据进入独立APP审核，再由实际SDK取精确fresh host grant并单次发送；source一致性或旧批准都不是新权限。Root不替模型提供任意远端命令。

未知mutation不重发；后续受审只读观察可证明其目标已经满足，但不把原UNKNOWN ACK改成成功。durable Verified后仅接续本地失败收尾，不能因此再发送补偿。失败专用delivery proof与原同连接事务核原Task、execution/template、释放引用及FAILED结果；原子归档/receipt持久化失败须整体回滚，不能借成功delivery或effectsSettled单字段删历史。原公开查询/独立业务数据保留，不增加DB表、APP公开API、UI或schema迁移。`ActionLog.find/events`两个既有同连接读取方法仅为Mod内部跨包证据读取调整可见性，没有扩大公开平台能力。

## 精确变更表

Java路径前缀为 `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/`；测试为同模块 `src/test/java/` 对应包。完整路径及逐文件before/after SHA见补丁和本阶段交付记录。

| 类型 | 路径/文件 | 作用 |
| --- | --- | --- |
| 既有production | `ai/review/DeploymentCommandEvidence.java` | 固定program完整输入与精确targets审核事实。 |
| 既有production | `ai/review/DeploymentFixedCommandApproval.java` | 仅登记三个固定recovery operation。 |
| 既有production | `linux/helper/DeploymentManagedHelperBundle.java` | 复用固定原片段组装sidecar，保原helper输出入口。 |
| 新production | `service/agent/recovery/DeploymentAgentPublicationCompensation.java` | 严格原补偿事实、Plan/Intent/result/Verified及失败delivery证明。 |
| 新production | `service/agent/recovery/DeploymentAgentPublicationRecovery.java` | 原Task受审producer、实际SDK调用与UNKNOWN不重发。 |
| 新production | `service/agent/recovery/DeploymentAgentPublicationRecoveryInstallation.java` | 不可变安装/readiness的原任务持久证据。 |
| 新production | `service/agent/recovery/DeploymentAgentPublicationRecoveryProgram.java` | 固定产物、请求/targets、完整审核源与严格receipt解析。 |
| 既有production | `service/agent/recovery/DeploymentAgentRecovery.java` | 原缺FINISHED分支及localVerified失败续尾。 |
| 既有production | `service/agent/recovery/evidence/DeploymentAgentActionLog.java` | 原连接读取与失败delivery收据边界。 |
| 既有production | `service/agent/recovery/evidence/DeploymentAgentHistoryArchive.java` | FAILED补偿证明与同事务归档门禁。 |
| 新resource | `src/main/resources/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-recovery-guard.sh` | 固定同锁native-first守门与精确marker收尾。 |
| 新resource | 同目录 `publication-recovery-loader.py` | 独立immutable store/readiness及唯一固定入口。 |
| 既有test | `ai/review/DeploymentCommandEvidenceTest.java` | 完整source审核、已安装controller回归及native请求容量夹具。 |
| 既有test | `db/execution/DeploymentAgentHistoryArchiveTest.java` | 失败proof/原子回滚/原Task producer/UNKNOWN与local-only续尾。 |
| 既有test | `linux/helper/DeploymentManagedHelperBundleTest.java` | 原字节pin、固定片段与完整guard行为夹具。 |
| 新test | `service/agent/recovery/DeploymentAgentPublicationRecoveryProgramTest.java` | 请求、完整产物及严格receipt正反例。 |

`DeploymentAiJsonTest.java` 已按captured before原字节精确恢复，manifest before/after及当前文件SHA核同，**不在patch内**；capacity方法移至同包的 `DeploymentCommandEvidenceTest`，没有为测试放宽CommandEvidence生产可见性。

## 实际定点验证与保留失败

证据根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-partial-publication-validation-251/`。首JUnit不可变17方法/来源锚为 `first-junit-evidence251.json`，SHA256 `94bedbac170aceeed9bb906e34d708028fd31537eaf975bb99e47e60f4006cc9`。原失败logs/result/manifest/XML/closure均保留，没有重写原报告。

| 实际轮次 | 结果 | 修正与边界 |
| --- | --- | --- |
| attempt01 | MAIN_COMPILE失败，2可见性error；0JUnit，未package | Compensation跨包调用ActionLog.find/events；只增加两处public关键字。 |
| attempt02 | MAIN_COMPILE失败，2 checked-exception error；0JUnit，未package | Archive transaction lambda不接收Exception；以窄SQLException包装，保cause及事务回滚。 |
| attempt03 | MAIN production编译通过；TEST_COMPILE 1可见性error；0JUnit，未package；收尾0 | capacity方法移至同包CommandEvidenceTest，AiJsonTest原字节恢复，没有放宽CommandEvidence生产可见性。 |
| attempt04（首真实JUnit） | 44 cases＝17P/27F/0E/0skip；sourceFreeze=0，未package；normal close=0，36精确PID/time pairs退出、自有2目录清理 | 20F为归档自有DB connection内重复physical gate；另7F为fixture混淆业务引用与physical handles。原27F保持为失败。 |
| attempt05（仅直接失败组） | 原Archive 8方法35P/0F/0E/0skip；test/package/sourceFreeze均0，package跳过测试；normal close=0，18精确pairs退出、自有2目录清理 | 其余3类9方法9例已在attempt04全过，不重跑；没有增加方法或案例。 |

最终覆盖由attempt04的三类9方法9P与attempt05的Archive 8方法35P组成，**17方法、44个最终案例全部通过**。五份原JUnit XML累计raw79＝52P/27F/0E/0skip；raw运行量与最终覆盖分别统计，首Archive中已过case不与重跑35重复计入最终覆盖。三轮compile-only失败均为0JUnit，不伪造案例。Archive的 `originalRecoveryProducerArchivesSuccessKeepsSharedBusinessAndNeverDispatchesAfterReopen` 与 `originalDeliveryProofAndAllCleanupWritesRollBackTogether` 仍在原8方法内。

首JUnit到最终manifest只有Compensation与ArchiveTest两文件变化。Compensation移除归档自有连接内重复physical gate，保留Archive打开连接前的physical gate及原引用身份校验；fixture分别处理业务reference与physical handle。同一正向方法新增真实held DB connection反例：持有期间settle返回UNKNOWN、证据不变，释放后成功，未增case。原REMOTE_TARGET漏追怀疑由 `ActionLog.create(endpoint)` 原子写排除，没有新增冗余事件。

## 打包、metadata与收尾

attempt05的skipTests package成功。metadata session61144 exit0，只读字节捕获，不启动Java/provider/SSH；`metadata-tools/parent-package-verification251.json`记录3319源码、47 deployment owner组、3 AI族、4 platform族、5完整host模块、12 production/4 test变更，以及54个有序CP入口/3248个成员。五个host JAR与247原字节核同。fixture只编译未执行；这是源码→target/JAR及有效CP字节绑定，不冒充JVM CodeSource、Mod registry或实服验证。

| 产物 | 实际绑定 |
| --- | --- |
| source manifest | `088988fb02ef6b9687378cbffb00f1ab3704167bce9d20dfeeab0b79e1285160` |
| deployment.jar | 3913637B；SHA256 `73420899848332b503ef4eec5e5c25d9d5aa1614b2aaa480b41a611016c9bcf5` |
| after-metadata251.json | SHA256 `4328761536287389a22cb8e3b165e254e77004d66f3f25072427210407599327` |
| classpath251.json | SHA256 `8f033d21a85abcda49f36b541a23159cab86f6242b8f9b339f7943362e79eded` |

父已实际完成 `git apply --reverse --check`，exit0，未修改stage。交付静态检查另外确认16份3-context hunk在内存正反向逐字节重建before/after，9份快照及16当前文件SHA匹配，保原行尾与空行。父最终普通非管理员CIM独核 `parent-final-process-verification251.json`：81个记录的精确PID/time pairs全退、10个自有TMP/UDS目录全无、全部validation/capture执行开关false，provider/SSH=0；没有以kill代替正常退出。

| 后置事项 | 当前状态 |
| --- | --- |
| 正式本地提交、提交后HEAD/index、250/251 seals | 最终标识由阶段 `delivery250.json`、`delivery251.json` 与 CURRENT 记录；本文不自引用提交SHA。 |
| 原238实际补偿/FAILED结清/正式归档及远端状态 | PENDING；原Task仍UNKNOWN，252未实际capture/offline/实服，不能由本地通过推断。 |

## 验证边界与容量

Windows guard夹具执行完整原guard、原rollback和自有临时文件比较/删除；CURRENT symlink表示、root/stat、flock/systemd及expected unit render为明确窄stub。它不能证明Linux no-follow/race、内核锁、systemd/cgroup或完整unit渲染。codec及真实TaskWork局部测试不替代真实APP/SSH或Mod registry正向恢复。

attempt04 stdout记录的六个实际编码native请求envelope如下，均由对应本地capacity方法通过1MiB请求上限检查：

| 固定operation | standalone bytes | Agent bytes |
| --- | ---: | ---: |
| readiness | 251286 | 47250 |
| install | 500958 | 39906 |
| guarded-first-rollback | 252882 | 48846 |

该夹具包括16384字符额外容量输入，使用本地native请求捕获与固定DENY回复；没有真实provider/APP批准/SSH发送。结果只覆盖实际构造的输入，不证明最终生产所有history/receipt上下文均在预算内，也不代表费用或缓存收益；正式facts及encoded-request预算继续fail closed。

## 实服与停止边界

原238的UNKNOWN与已观察部分发布效果保留；本交付不是它已经结清、远端全空或File Transfer业务通过的证据。当前两项目合计**9次FULL / 0次完整BUSINESS验收**：File Transfer八次，CSV Inspector历史208一次；CSV当前接续新轮尚未开始，不能抹去历史运行。本轮本地验证及未来纯恢复窗口必须单列，不能充作新FULL或业务完成；194/234不恢复，221不自动恢复。

继续顺序仍为File Transfer、CSV Inspector严格逐个。两项目完成真实完整业务验证及必要正式收尾后立即暂停，分别报告FULL审批占比、cache已知命中率/覆盖和未知项；失败与纯恢复窗口、物理HTTP/probe/账单未知分开。等待用户决定后续，不因旧cache>60%规则自动开始第三项或八项目复验。当前草稿没有费用或缓存优化收益结论。
