# 原194产品恢复验收与只读取证228

2026-10-16，本轮唯一真实产品恢复为 **1 FAIL、0 ERROR、0 SKIP**，原Task仍为 `UNKNOWN`。本轮未完成远端资源结清或业务验收，没有启动新部署，也没有增加两个所选项目的7次 FULL 数量。

## 范围与当前基线

用户要求 CSV Inspector、File Transfer 逐个部署和调试。两项目各自完成调整及真实业务验收后，分别核 FULL 审批占比、缓存命中及已知/未知范围；若审批仍明显偏高，暂停并讨论后续方案。本次仅处理旧Task占用的恢复问题，纯恢复窗口不作为完整部署效率的比较样本。

- 唯一Task：`a67e3b89-d04e-49a7-8b5e-20ed93803d53`；原Runtime：`src/app/db/data/agent-live-task-board-q3k1ohhos`；App身份：`wtl-agent-task-board-q3k1qalz8`。
- 基线HEAD：`6e5edd939c7d365c9e32835bea0124046e060f5a`，index为空。3310份实际源码与227最终清单一致，清单SHA为 `a129196bd27cd1aab3650005a42a797c820c1dd41ad1139e67e7876328518fe6`。原227封存188文件逐SHA复核相同，保留混合工作树及其它任务改动。
- 实际版本：JDK21.0.10、Maven3.9.12、Python3.12.10；规范普通工作目录重新查询成功。本轮没有源码修订、打包或全套测试。
- SSH仍为 `45.192.105.213:22/root`，沿用用户确认的重装后指纹 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc`。DeepSeek URL/model及数据出站沿用会话授权；凭据不进入模型正文、文档或日志。

六个当前JAR在运行前后均未改变：

| 构件 | SHA-256 |
| --- | --- |
| deployment.jar | `f4b82ec46c9d4cd7471805c454f8d31874ee6235962278b2c2eed693d3e7f9b7` |
| host AI | `0ed29bfbaeb8912aba1bc938ed027d71a65c177bd37ea2e3387d26a83fd37d80` |
| appapi | `65d664ed7535def269f981759717dafcbf830f103e30c543c9c65c1e4f565791` |
| app service | `53a745e512b64d356cdbcb75eba3be8d88bc249f1126322fca1d245b4d3e78ef` |
| app db | `f08df4aa543d9dc0a3f90ba4116f67d484d539f3e97613188c1701c3c829d4ab` |
| app main | `b274feab588c3e41994cc3fb29a7880495d4d18423a10ee5dbb5cfa148f48aab` |

## 必要保全和工具准入

恢复前新建私有目录 `test/data/original194-before-product-recovery-228-35c434b032c648cead75a16c1228ac7c`，仅保全数据库59060224字节和引导358字节，另存清单；没有导出整Runtime或远端23 GB。持既有产品空锁及原件 `Read/FileShare.Read` 句柄拒写删，复制及独立验证均exit0。目录仅当前用户与SYSTEM，owner、保护ACL、无重解析点及源副SHA均通过。此备份是恢复前状态，禁止在远端动作之后自动还原。

原数据库恢复前SHA为 `92e170a2fec8718a3077be0f84c957db98af3c652c77961d55b701204d478a7c`，引导为 `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。旧225的pre副本仅保留历史，不充当本次新备份。

执行工具沿225入口最小派生，2份PS AST、2份Python AST及6项新增纯守门通过；保全工具另2份PS AST及6项纯守门通过。初次离线capture在读取227深路径XML时遇到Win32路径错误，发生于Maven、数据库和网络之前。保留失败和旧绑定，仅将原XML逐字节复制到228根的短路径，未修改执行helper，随后同一离线核验exit0。

准入核54个非空有效classpath入口/3202文件、当前源码、六JAR、部署29构件组、AI3族及四个当前宿主类族，排除末尾空项。offline binding SHA为 `4c946db46b36a03c59f34a1612174b9b85d291894b99e4a6902d077ac9724832`。父方另核四个当前宿主类在首有效CP入口的字节相同；这是静态字节绑定，不是JVM CodeSource。

模型路由只复用225已验证的安全元数据，结合恢复前pair与225 post逐字节相同、配置恢复模型开关为false及用途预算源码未变；没有新SQL模型查询或能力探测。保存的DEP LOW/APP NONE、普通32768/审批8192与此前一致，不将复用证据表述为新网络可用性证明。

## 唯一真实产品执行

仅运行 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，Maven显式 `-pl src/mod/deployment -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false`。设置 `originalTaskOnly=true`、`originalPreparationOnly=false`、`configureAgentRecoveryModel=false`；原30分钟等待不改。每个当前完整动作仍需独立审批和新grant，不接手SSH，不改静态门禁，不重放旧动作。

exec64886已终态exit1，Maven1/before0/after0。窗口为 `2026-10-16T07:55:44.6127590Z` 至 `07:57:35.9428914Z`，原JUnit XML为1F0E0S、79.915秒。产品真实进入 `RECOVERY/MOD_RECOVERY_READY`，返回Task `UNKNOWN`、progress `AI_RECOVERY_ORIGINAL_EVIDENCE`，未出现 `AGENT_LIVE_EXACT_ORIGINAL_RECONCILED` 成功marker。不能从生成成功或进度推断审批裁决、grant、SDK调用或远端absence。

恢复后数据库为76132352字节、SHA `e37dfde9b81d7e20b46bf359b77b4d677da4b1d3d5c498835a378240962ed16b`；引导358字节/SHA保持原值。保留这一实际产品运行后的状态及旧UNKNOWN，不回滚到备份；源码、六JAR和完整classpath前后相同。

## 本轮用量

最后100条诊断为 `OBSERVED/recordCount=100/truncated=false`，并非完整provider账本。84条usage中排除72条旧记录（含225的两条），按Task及startedAt/recordedAt双时间界定得到12条本轮记录、12个不同generation ID。父方独立核原行SHA、身份、时间、原始attempt字段、整数汇总和来源文件SHA。

| 项目 | 本轮观察到的已知值 |
| --- | ---: |
| Input / Output | 161639 / 4644 |
| 合计Token | 166283 |
| Cache hit / miss | 127229 / 34410 |
| 已知输入缓存命中率 | 78.7118208% |
| 已知Token中的审批占比 | 100% |

12条均为APPROVAL，实际effectiveMaxOutput8192，平台记录generation SUCCEEDED/transportFailure NONE；这些不是审批裁决或12个独立动作的证明。remote provider模型名未出现在usage记录中，保持未知，不从配置补入。reasoning各条未知且属于output子集，不重复相加。完整generation、physical HTTP和账单仍未知，不新增付费调用补证。

这是纯恢复窗口，不与FULL审批目标20%或最终八项目指标混比，也不据此触发“两个项目调整完毕后”的条件暂停。

## 本轮只读取证与收尾

随后完成唯一 `diagnostics/diagnostics-read-01` 裸H2只读取证：exit0、stderr空。正常用户DPAPI只在内存加载既有引导；原pair外层Read/FileShare.Read拒写删，Java持原产品空锁，核IFEXISTS/ACCESS_MODE_DATA=r/AUTO_SERVER=false、schema1、generation及原Mod/log身份。不初始化Runtime、不读credential表或联网，不查询全部资源。最小10项CP/319文件pin由228实际after-metadata刷新，5项与旧224不同；Java实际source-mode编译和数据库查询通过，2份PS AST及7项纯守门仅作前置证据。

本轮时间窗完整返回14条诊断：上述12条usage、1条 `DEPLOYMENT_AGENT_PARTIAL_OBSERVATION` 和1条 `DEPLOYMENT_AGENT_FAILURE`。实际阻塞是前端PREPARE事件95的恢复前观测：

- `phase=PRE`，`approveReturned=true/approveOutcome=SUCCEEDED/approveValuePresent=true`；执行已调用、返回且带结果。
- `executeOutcome=FAILED/executeProblem=IO_FAILURE/executeProblemEvent=SSH_COMMAND_NONZERO`，exit64，stdout0字节、stderr73字节、未截断。当前观测已收到命令退出回执，不是225的检查点创建错误，也不是仅凭usage推导审批或SSH结果。
- `partialStage=FILES/partialReason=NON_PRIVATE_FILE`，strictPresent与strictAbsent均false。固定stderr完整重构为 `FORMAT=WTL_AGENT_PARTIAL_REJECTION_1\nSTAGE=FILES\nREASON=NON_PRIVATE_FILE\n`，SHA `4bd7d520619dcbb03947c1c3bd951a4d24db6e019e065543a41e3e2ed0eee298` 与实际诊断相同。
- 最终错误为 `IllegalArgumentException: Original pending observation is incomplete`，68字节、SHA `262b4e7a6567a02d5c8893fae549d4cf0567bed15e805fa0335f403d1feac916`；固定公开源码完整字符串及SHA均匹配。平台及Mod仍UNKNOWN，平台problem为 `UNKNOWN/DEPLOYMENT_AGENT_PREPARATION_SET_UNCONFIRMED`。

`DeploymentAgentPartialPreparationProbe.private_file` 将路径元数据不安全/未知或硬链接数不为1合并为NON_PRIVATE_FILE。实际元数据检查包括非符号链接、普通文件、root:root归属和无组/其它写权限；stat失败也不能通过。它被用于六个控制文件、可选.agent-mode及source.tar.gz。本轮诊断没有指出具体文件，也没有分别记录失败谓词，故不能断言是chmod问题、硬链接、文件缺失或服务端被改动，更不能凭通用拒因放宽门禁或删除候选。下一步只补这一缺口的有界产品观测，再据实际证据选择最小修复；本轮不自动付费重试恢复。

原97条事件完整SHA `a4462b793363ccbe71a086ad46ce98c93a73e7f3abbcae94ee7c48d62e781044` 不变，新增事件0。三精确CUSTOM身份中，旧AUTHOR_MULTI和AUTHOR_MODEL_COMMANDS均缺失，缺失不证明未发送；新RECOVERY_MULTI存在，protocol为 `recovery-checkpoint.v1`、revision1、未release。两个成员分别为 `INTENT/PRESENT` 和 `INTENT/UNOBSERVED`，无afterResult。这证明227恢复进度能力已在实际产品中创建并推进一次，不等同于完整恢复、absence或可清理证明，也不追补历史零发送分类。

只读前后pair SHA未变，唯一正常Close exit0；父另正常用户CIM核reader launcher、Java、closer三组精确PID/创建时间均退出，自有marker TMP不存在。没有新provider/SSH调用、恢复或FULL。

产品正常Close已exit0，15组观察到的本轮精确PID/创建时间均退出，自有TMP及短UDS已清。父正常用户CIM另核包括两次保全进程共17组全部退出。原数据、私有必要备份、未结UNKNOWN、失败和资源证据保留，没有按候选名称或路径删除，也没有重试197/201或58/74/116。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-product-recovery-228/`；入口为 `parent-backup-verification228.json`、`parent-product-release228.json`、`parent-product-closure228.json`、`parent-runtime-process-closure228.json`、`provider-usage228.json`、`parent-usage-verification228.json`、`parent-diagnostics-release228.json`、`parent-diagnostics-verification228.json` 和 `parent-diagnostics-process-closure228.json`。diagnostics-plan及static-preparation是解除false/false之前的准备快照，实际读取以父release、run原件及closure为准。未使用GPT-6-Astra。两个所选项目的真实业务验收、冻结八项目、非默认目录及整机重启恢复仍未完成。
