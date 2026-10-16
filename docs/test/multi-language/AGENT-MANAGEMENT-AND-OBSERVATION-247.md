# Agent 内部管理与原发布观察：247 局部交付

日期：2026-10-17。本地最终15个指定方法覆盖44个案例通过，skipTests打包、metadata capture及最终进程收尾均结束0；本地提交标识与最终封存由本阶段 `delivery247.json` 及各根 `final-seal*.json` 另行绑定。本交付合并243内部管理、246原发布观察与247验证揭示的精确审批路由修正；没有进行新的实服或业务验收。

补丁：[AGENT-MANAGEMENT-AND-OBSERVATION-247.patch](AGENT-MANAGEMENT-AND-OBSERVATION-247.patch)，SHA256：`f7fc9aef4397efe9d21a1e455002c8253a3cd2e007d641ad3a7042037b9bf6bd`。精确13源码路径：12个既有文件只含相对原快照的任务差异；唯一完整新增为 `DeploymentAgentPublicationObservation.java`，原文件为 `/dev/null`。既有迁移源码没有整体列为新增。

## 行为与边界

`DeploymentApplicationsService` 在真实QUERY/ACTION TaskWork内捕获模型配置、开启workflow及独立APP command scope。显式Agent policy使用AUTOMATIC、rememberAuthorization=false；每条inspect/helper/current/health/lifecycle命令仍独立APP，再取新的单次host grant，不由aiEnabled、代理接口或旧授权替代。管理Task不伪造Agent SOURCE；默认构造的恢复服务从原v2事实重建审批策略。显式Agent策略尚未覆盖的切版、接管和外部动作明确拒绝。

`DeploymentApplicationRunner` 保留完整图release/owner/current/layout/target守门，只把选中组件START/STOP限制为一个mutation目标。raw运行态与健康派生态分开：单组件STOP核选中项停止及其余raw/autostart不变，允许仍运行的Node因Go下游停止显示DEGRADED；START要求声明健康恢复。整App DISABLE核原raw运行态保持及目标autostart达标，不套STOP/START目标。前一STOP未正式结清时不以新START或finally强制补偿。

`DeploymentManagementActionLog` 在原BLOB中加入严格v2字段、scope/组件/发布/owner/target/model revision及不可变事实校验。v2 UPDATE同时比较固定行身份与原component_state实际字节，防止同phase读取后并发覆盖；v1 SQL分支和原整App恢复语义保留。UNKNOWN管理恢复只观察，不重发STOP/START/DISABLE；ACK丢失保留原verified=false，使用另行只读证明表达结论。未改DB表、平台API、Mod注册、公开UI或schemaVersion；STATIC、ASSISTED和main-only默认路径保留。

`DeploymentHandoffChannel` 仅将 `managed-current-status` 的完整实际request交给已有 `DeploymentCommandReviewRouter`；其他verb默认路径保留。`DeploymentFixedCommandApproval` 仅登记精确operation `deployment.handoff.managed-current-status.v1`。实际diag04由测试正确拒绝了原bare approve绕过APP；这两处生产修正未扩授权器或操作集合通配范围。

Agent File Transfer夹具补齐BUSINESS v2目标：64MiB、1MiB分块、部分接收跨产品RESTART续传、重复/冲突块、10并发complete只生成一个版本、两个历史版本下载长度/SHA、取消持久、整文件摘要错误拒绝；并按正式endpoint/dependency graph选择独立Go/Node unit，真实STOP Go→Node静态/health存活和API502→新独立Task START/健康及旧版本恢复。管理命令按task/动作摘要记录APP→fresh grant→单次send；UNKNOWN不在finally强制补偿，浏览器门槛保留。夹具业务尚未真实执行；实际图缺独立unit或精确endpoint/依赖时保留真实阻点。半传重启不冒称已覆盖短暂verifying窗口。

原发布协调在publication已开始、缺FINISHED的既有分支新增一次有界 `GraphPublisher.observeOriginal`。窄HealthEvidence模式只接受精确original-status，核原事件前缀及task/execution/template/proposal/candidate/owner/release/layout/target/revision；如实记录CURRENT缺失或不同，不把它改写为期望release。每次显式协调最多16组件，每组件最多一次status；拒绝或缺证后不重试，已成功receipt保留，publish/health/runtime/cleanup动作拒绝。

BEGIN/COMPONENT/END版本化帧在既有事件容量内严格核字段、原前缀及实际请求/结果/receipt引用，拒绝重放、越序、漂移和跨invocation拼接；最新不完整帧不回退旧完整帧。即使observationComplete=true，结果也**恒为UNKNOWN**、authority=NONE，只表示观察收集情况；不提交App、不继续发布、不回滚、不删除资源，不宣称业务结清或远端无效果。原默认已完成发布验证路径保留；本交付没有新的原Task实服观察结论。

## 实际本地验证

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-management-observation-validation-247/`。所有原失败报告保留，最终覆盖不抹掉历史失败。

| 实际轮次 | JUnit原始结果 | package / 说明 |
| --- | --- | --- |
| compile01 | 0案例；testCompile失败 | 未package；fixture缺CommandRequest import。 |
| compile02 | 0案例；testCompile失败 | 未package；同fixture缺ArrayList/Objects import。 |
| attempt03 | 44：29P / 12F / 3E | 保留首批真实失败。 |
| diag04 | 1：0P / 1F / 0E | 揭示managed-current-status走bare approve，独立APP测试正确拒绝。 |
| diag05 | 1：0P / 0F / 1E | 正向四动作已经过；末尾DENY接缝等待future不符合真实UNKNOWN语义。 |
| attempt06 | 44：43P / 1F / 0E | 8份生产源码的修正理由重核原15方法范围；其余14方法43案例通过。 |
| attempt07，session89390 ENDED0 | 仅剩方法1：1P / 0F / 0E / 0Skip | skipTests package0、sourceFreeze0；未重跑其余已过方法。 |

最终**15方法 / 44案例通过**的证据来源是attempt06的14方法43案例，加attempt07的1方法1案例；不是单轮44P，也不是全量测试通过。7轮raw合计**91案例＝73P / 14F / 4E**，compile01/02各0案例，失败原样保留。完整15方法见 `before-binding247.json.allTargetedMethods`（Lifecycle 5、Autostart 3、HealthEvidence 5、RecoveryRestart 2）；最后 `methods` 只选择 `DeploymentApplicationLifecycleTest#agentManagementUsesActualTaskAppAndFreshGrantForEveryRemoteAction`。

父末次 `parent-final-coverage-crosscheck247.json` 逐核11份原XML的SHA及原selector，确认raw91与最终按整method去重的15方法/44案例，案例名集合与首次44案例完全一致；没有按单个case拼接或丢弃失败。

编译修正仅同fixture三条import。后续两份新增生产差异只涉及上述精确current-status路由/operation登记，原6份生产源码没有其他改动。Lifecycle测试使用真实原callback结果Map与snapshot UNKNOWN的有界20秒观察；预期DENY放入自有子进程，核实际ApiException event、UNKNOWN、无grant/无send且不虚报cleanup，不造TaskContext、不换task type、不改registry。Health测试只校正unknown参数的missingReason期望。上述测试接缝修正不放宽生产审批或把UNKNOWN改成成功。

管理恢复测试覆盖真实Service QUERY/ACTION TaskWork、真实Task对象及原owner日志，AI/host/peer为受控替身；在认证原回调内核只读恢复合同。**真实Mod registry的runtime.tasks().reconcile(originalId)管理恢复集成仍未验证**。原发布观察的替身/codec通过亦不证明真实APP会批准、远端CURRENT已知或原任务已结清。

| 实际产物/收尾 | 事实与边界 |
| --- | --- |
| deployment.jar | 3,810,221 bytes；SHA256 `fda0d1fc09bcd6ffc013954ef1ce1f98634a9f5a35d615b7117a133b92996619`；父已核五份host JAR保持旧字节。 |
| 各轮正常close | 依次核8 / 8 / 43 / 11 / 12 / 45 / 20个精确PID pairs；14个自有TMP/UDS目录已删除。不是去重后的全局进程数。 |
| 最终aggregate及closer退出 | `verify-final-processes247.ps1` 正常验证actual0：156个精确PID/time pairs（七轮sampler/launcher、7个closer及metadata父子）全部退出，14个自有TMP/UDS目录不存在，无终止操作、正控成立。证明为 `parent-final-process-verification247.json`，verifier自身工具另核结束0。 |
| metadata capture | 唯一normal byte-only session69219已ENDED0，stderr为空；无Java/DB/provider/SSH。`metadata-tools/after-metadata247.json` SHA256 `7d1d36b6b851107f367d62d77acaa67cc78456a235754d30f54e0c74582a571f`。 |
| source-family / CP | source3312；deployment39 groups、AI3、platform4 families、complete host5；changed production8 / test5；CP54 entries / 3223 files。`metadata-tools/classpath247.json` SHA256 `329ca73649e5147289249fa261a7aeec91318b93ad7ff5444be9ae7e2933bada`。 |
| 原验收fixture class | 实际SHA256 `5b8ba4ef5e2312e7c60ab8a452be5fd9ff309573a9d5983fd630bc07b6eccfb6`；仅编译，未执行该实服业务夹具。 |
| consumer241 canonical compatibility | 本地实际检查true；不是原任务恢复、远端观察或业务通过证明。 |
| Git提交、最终封存 | 父独占；实际提交标识和封存摘要见本阶段delivery记录，不推送。 |
| File Transfer BUSINESS v2、浏览器、实服STOP/DISABLE | 未实际执行；夹具已纳入本地编译，不记业务通过。 |

validation/capture授权位已全部撤为false；原release消费记录按原字节保留，不复用旧授权或删除原执行依据。

## 字节绑定与保留状态

before基线为HEAD `67bdabfed435fc53e599decd80a5d43eac69749b`、3311项源码、manifest SHA256 `42c4bd533b0facf478b9d0b6a4b0bb8a3a70d9f3f105703a6728dfd744af8e2d`。12份preimage的完整路径/SHA见247 `before-binding247.json.sourceSnapshots`，包括243/246原快照及两份additional-before247快照；后者binding SHA256 `8ae551535c027714f0cdad55f89f104c3d5f3a8a4bc7ed4909079a7c28e239e7`，与共同3311基线、原target family及240旧JAR绑定。唯一新Observation在原manifest不存在。

当前源码3312项，`after-manifest247.json` SHA256 `0bfe647ee5e64ade7e24e6f8d9880de50b4b2001b466b324180f76c4082f8d73`。本次正式补丁刷新仅检查13精确路径、12份原快照SHA、当前manifest对应SHA、内存逐hunk重建及生成前后13源SHA不变；没有再次运行Maven、Java、DB、provider、SSH、网络或Git，也未修改源码、capsule、metadata或执行证据。

| 精确源码路径 | 刷新时SHA256 |
| --- | --- |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/application/DeploymentApplicationsService.java` | `7812b0f9153b72fa2e0c4a185cbfb13edee7a96491e8082c3c53497476285d52` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/application/DeploymentApplicationRunner.java` | `de814be2321bf6de42b4039ed0960d55850ca4274993e5b7c562364da54fc205` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/application/DeploymentManagementActionLog.java` | `020df18571527e613485acea9162b3f34f4df2268760c3f9656089ba9af4b6f6` |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentApplicationAutostartTest.java` | `aa4fe8afdbe194fc01cb4f5ef14eebf0de1bc85255dbc068bebb826eace9174b` |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentApplicationLifecycleTest.java` | `bbb1f5e62aa1b4410cd8b26f6ff8420f78155cb581a78f0654a19e8fa614fd35` |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentStaticLiveAcceptanceTest.java` | `2764f3d0086ede815dacb39fdecf50589021e7895983effae146ef7997ecadc1` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/DeploymentAgentRecovery.java` | `5630d1825f3f5c219b79792cf105cfb3cf40b50f32fb75773e19038c8ad8fc76` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentHealthEvidence.java` | `ae2ac1d32281cba118bbffe315043d42ef5108f4e1757ed20072cc2707ab13fb` |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/review/DeploymentAgentHealthEvidenceTest.java` | `d4d203a1d8be974cc684234f46c5d0ce304e5583f2e53bd5a2944276487edd3e` |
| `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/agent/DeploymentAgentRecoveryRestartTest.java` | `b21d8112c400696a6d9f16f9b3f91bfc5ab58a0c46ccfa167f0a84e201eab316` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/handoff/DeploymentHandoffChannel.java` | `79ba6828c4b0f129772b04842dd7f2daae32c41ce1fddb2de4a695388fe3b024` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/ai/review/DeploymentFixedCommandApproval.java` | `16d71434e0e229393d1311d1a72102dd008af300ebe4f0e9fb5fcc6865ee0a90` |
| `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/DeploymentAgentPublicationObservation.java` | `4f8e76ba375de167663db9c2369eec3c1d279146ad36f8efff883a8d22efe0be` |

当前仍为**9次actual FULL / 0次完整业务通过，CSV Inspector未启动**。原238保留UNKNOWN；本地修复和观察帧不等于恢复成功。原194、234不再恢复，221不自动恢复。File Transfer与CSV Inspector严格串行；两个验证完成即暂停，分别报告各自FULL的APP占比与cache，等待真人决定，不自动开始第三个或八项目。补丁仅含本任务delta，AGENTS和其他迁移脏树排除，禁止整块迁移源码加入本次提交。
