# 四项 ASSISTED 修复：本地定点验证记录（PAUSED_BY_USER）

本记录只陈述直接受影响的定点测试、编译、工具契约和本地浏览器验证，不是全模块/全项目测试，也不是八项或Agent真机验收。当前PAUSED_BY_USER：三项完整通过，file-transfer尚未完整验收。03、04失败及原恢复/收尾保持原结论；05已发布/64块200，但首次下载仅BEGIN，父按用户要求精确停止Surefire47092，Maven/wrapper97287实际exit1，无JUnit终态，不能记为自然业务失败或完整PASS。05原RESTART已产品恢复结清，cleanup03已核STOPPED/DISABLED，暂停交接完成；未作远端备份完成声明。见[主报告](ASSISTED-FOUR-FIXES-20261023.md)及[短交接](ASSISTED-FOUR-FIXES-20261023-HANDOFF.md)；真实入口和中断均不计入本地用例数。

证据根为 `.ai-workspace/evidence/assisted-four-fixes-20261023/`，表内辅助路径相对该根。Maven数字是对应运行的test runs，含参数化变体；缺逐testcase XML时不推断唯一方法总数。不同组/复跑有重叠，不相加作“唯一总数”。旧源码版本的通过仅支持该版本，不宣称无变化地覆盖后续合同。

## 既有产品修复与交付定点

| 验证组 | 实际范围和结果 | 证据 |
| --- | --- | --- |
| 首组 Java 集成 | 24 runs，0 failure/error/skipped。AiJson 1、CommandEvidence 1、ToolchainDiagnostics 5、HelperInstallerContract 6、HelperReviewContract 1、ManagedHelper 3、PublishChannel 4、BuildCommandChannel 3；均为显式选定类/方法 | `targeted-java-01.log` |
| 安装审批补充 | `DeploymentApprovalConversationTest#helperInstallerRequestBindsCompleteSourcesAndSegmentsToActualStdinAndDenialStaysFinal`，1 run 通过；该次其余恢复 3 runs 尚失败，不将整次记为通过 | `targeted-java-recovery-03.log` |
| 原任务安装恢复 | `DeploymentProtocolBootstrapTest` 的 originalTaskDoesNotReplayUnknownInstallerAndSettlesExactReceipt、preDispatchCancellationLeavesNoUnknownInstaller、processExitClassificationPreservesUncertainInstallations，3 runs 通过 | `targeted-java-recovery-04.log` |
| 原 helper-status 证据 | `DeploymentAssistedHelperStatusEvidenceRegressionTest`，24 runs 通过：3 个方法，其中绑定篡改方法展开 22 个参数化场景，另含原 journal 精确绑定及独立拒绝保持 UNKNOWN | `helper-status-evidence-sol-01.log` |
| helper-only 恢复 | DB 排除检查 1 run、宿主 checkpoint 排除授权 1 run、产品 helper-only 恢复 7 runs，最终分别通过；含额外资源、拒绝、receipt/digest/host 不匹配和真实释放/关闭边界 | `helper-only-host-tests-04.log`、`helper-only-product-tests-01.log`、`helper-only-product-tests-04.log` |
| CSV 工具链子任务隔离验证 | 8 个不同 Java 方法及 6 个不同 Python 方法通过；包括新增诊断、原 BuildCommandChannel 3 方法和原 tracked 子进程锁继承。Java 隔离临时 Bundle 按当时资源 hash 绑定，仅属本地行为证据，最终正式 pin 集成见首组 Maven | `toolchain/RESULTS.md`、`toolchain/java-targeted-rerun.log`、`toolchain/java-final-affected.log`、`toolchain/python-final-affected.log` |
| systemd 观察与生命周期 | 7 个显式 Python 方法、27 个受控场景通过；3 个修改 shell 片段分别 `bash -n` 通过。相关 Java PublishChannel 4 方法和 HelperReviewContract 1 方法已包含首组 24 runs，不重复计数 | `systemd-validation.md`、`targeted-java-01.log` |
| 诊断 checkpoint key | 旧诊断方法 3 runs 通过；新增 `DeploymentToolchainDiagnosticsTaskServiceTest#realTaskServiceRetainsExactOriginalRequestAcrossReopenAndRecovery` 修正后 1 run 通过。真实 TaskService、ASSISTED observer、H2 重开及原任务恢复验证，key 从 68 字符修至 64 | `diagnostic-checkpoint-targeted-02.log`、`diagnostic-checkpoint-targeted-02-unit.xml`、`diagnostic-checkpoint-targeted-03.log`、`diagnostic-checkpoint-targeted-03-integration.xml` |
| 原诊断只读恢复 | 首次 14 runs 中 13 通过，独立大附件场景因 fixture 序列化错误中断；拆出并修正后的大附件方法单独 1 run 通过，形成 14 个只读恢复场景的验证证据。已通过 13 场景不复跑、不再次累计 | `diagnostic-recovery-tests-01.log`、`diagnostic-recovery-tests-02.log` |
| 原诊断明确收尾 | DB 完整作者集合 1、宿主原任务授权集合 1、产品完整范围/新鲜成功/实际资源释放 6，共本组 8 runs 通过。覆盖额外已释放 operation、额外资源、拒绝、明确 FAILED 及关闭后重开；不把成功诊断等同于原业务成功 | `single-diagnostic-conclusion-tests-01.log` |
| SFTP安全诊断 | 3个选定方法通过：实际task绑定、白名单元数据/秘密正文丢弃、logger失败不改变原结果。包含在较早60 runs（Program20/State37/Diagnostics3）中，不把60与后继场景重复累计成唯一总数 | `candidate-compensation-java-01.log` |
| 后继候选补偿Java | 三类共115个不同场景最终取得通过证据：Program32、Compensation状态56、Review27。01先通过32+56+15，另12个Review为5 failure/7 error；只改触发既有危险shell规则的一句Python注释后，02仅重跑这12并通过。不是一次全绿，也不把115+12当127个不同场景 | `candidate-compensation-successor-java-01.log`、`candidate-compensation-successor-java-02.log` |
| 后继候选补偿Python | 5个相关类共32个不同方法：08先28通过、3个旧mock断言失败及1个TEMP权限错误；补齐fixture的lexists并改任务TEMP后，09只重跑4个方法通过。旧阶段相关方法保留原日志，不继续叠加成最新版本总数 | `candidate-compensation-python-08.log`、`candidate-compensation-python-09.log` |
| 交付工具 | 4 个合成测试通过：CRLF/新增/二进制/无末尾换行/Windows 长路径、final 拒绝 activeWork/manifest 冲突/并发漂移、方法投影及路径范围拒绝 | `tools/source-delta-final-contract-03.log` |
| candidate-04交付检查 | 只重跑1个直接相关synthetic模式/manifest冲突/漂移/apply方法通过；随后67文件真实独立apply check/apply及字节比对通过，未重跑其余无变化契约 | `tools/source-delta-candidate-04-contract-01.log`、`tools/source-delta-candidate-04-verification.json` |
| candidate-06交付检查 | 原builder未改、未重跑契约。67文件真实独立git apply --check/apply均exit0、目标逐字节一致；源码/输入漂移0、临时树已清理。仅交付候选验证，非业务测试或--final | `tools/source-delta-candidate-06.log`、`tools/source-delta-candidate-06-verification.json` |
| 生命周期阶段解析 | 仅新增1个synthetic方法通过，含1正例和17个拒绝变体：独立LIFECYCLE_REACTIVATION需唯一严格marker、file-transfer/app绑定及原始整数exit0，阶段bundle不合成平台root。未重跑旧2/14组，未改finalizer；这不是实际START成功证据 | `tools/lifecycle-reactivation-parser-test-02.log`；Windows python别名启动失败且未执行测试另存 `tools/lifecycle-reactivation-parser-launch-failure-01.txt` |
| 文件分块阶段诊断编译 | f24 skipTests test-compile通过，16.934秒；只证明新增安全阶段日志的编译，不代表新业务、超时根因或正式规模验收通过 | `file-transfer-chunk-trace-compile-01.log`、`execution-source-05.json` |
| 历史逐Write期限Go定点 | 本地Go1.26，2方法/5叶场景通过：64MiB进展超过原绝对期限仍完整、HEAD、Range、Not Modified、停滞时保留idle期限并拒绝短响应；main另仅编译，0测试。Go1.24未实际执行 | `tools/local-download-truncation/go-download-focused-05.log`、`go-main-compile-01.log`及作者manifest |
| 历史未改Node转发背压定点 | Node24.16最终positive06，1个64MiB真实源码loopback场景通过、SHA一致、1459 pause/0 timeout；原30000ms idle、受控client pause50ms，listener关闭/temp按精确边界清理。positive05不重复计数，非目标主机业务通过 | `tools/local-download-truncation/node-download-backpressure-positive-06.log` |
| sample路径builder契约 | 仅新增1方法：授权4路径synthetic真实apply/bytes通过，13相邻/越界路径拒绝；摘要篡改、重复baseline、新test缺SHA或preexist拒绝。既有src规则保留，额外baseline只读引用不覆盖最早49 | `tools/source-delta-sample-scope-test-01.log`，exit0；未重跑旧全套 |
| candidate-07交付检查 | 71文件真实独立apply check/apply均exit0、逐bytes一致、源/输入漂移0、临时树已清理；actual f24 2358唯一路径及sample30快照复核一致。候选未final，不是业务或HEAD独立构建验证 | `tools/source-delta-candidate-07.log`、`tools/source-delta-candidate-07-verification.json` |

Java集成在隔离f24副本按显式 `-pl ... -am -Dtest=...` 选择器运行；依赖模块参与编译不等于执行其全套测试。历史candidate-04/06/07只对各自生成时源快照成立；表中71文件、2358路径及sample30均是source06阶段，不能当作当前source07统计。

## 文件传输03之后的新增直接验证

| 验证组 | 实际范围和结果 | 证据 |
| --- | --- | --- |
| ASSISTED finally原异常保留 | `DeploymentAssistedAcceptanceFailureTest`四方法，JUnit4/0失败/0错误/0跳过，0.122s、Maven17.148s/exit0；实际共享结清逻辑验证business+cleanup双失败、仅cleanup失败、无cleanup失败及自抑制/既有suppressed。Exception/AssertionError身份与栈保留，未镜像实现或静态字符串假测 | `tools/finally-failure-focused-02.log/.xml`、`finally-failure-focused-verification-01.txt` |
| 下载阶段Java诊断编译 | f24显式skipTests test-compile BUILD SUCCESS，13.594s；只证明被动下载阶段日志可编译，没有执行live/JUnit，不是根因/业务通过 | `tools/download-stage-compile-01.log` |
| Node被动终止诊断 | 原64MiB背压成功方法及独立终止顺序方法取得通过证据；首个本地终止事件保留、固定版本/请求/顺序/UTC/已缓冲字节，不改变超时和传输政策。初次2方法组中终止fixture失败，后只重跑终止方法03通过；不把本地观察顺序当网络原因 | `tools/file-transfer-proxy-diagnostic-01/node-focused-01.log`、`node-termination-focused-02.log`、`node-termination-focused-03.log` |
| Node背压被动观测补充 | 2方法/exit0；64MiB完整、1510次pause、0 upstream timeout，另有两种断开方向，记录暂停/恢复/累计字节/下游drain；缓冲计数明确不是最终客户端收取证明，不作新场景累计 | `tools/file-transfer-proxy-backpressure-02/node-focused-01.log` |
| 最终客户端helper合同 | Node24.16 Web Streams，9叶场景（父容器含计为node tests10/pass10），exit0。64MiB持续35s以上；stall、无headers、迟headers后无首块均按真实30000ms取消上游；短EOF/错SHA/超长拒Blob、零字节原摘要通过、网络错误不替换成idle。源码helper SHA b566b548…75472b | `tools/file-transfer-client-idle-01/client-focused-01.log` |
| 新合同Node代理 | 2方法/exit0：64MiB、1038次pause、0 upstream timeout，成功下载body时计时为0；终止/取消传播保留。与前述旧政策组重叠，不相加成独立总数 | 同目录 `proxy-focused-01.log` |
| 新合同Go | 2方法/5叶场景通过：64MiB持续进展、HEAD/Range/304，以及客户端取消后写流退出/失败诊断；main另只编译、0测试，各exit0。测试原server窗口人工1s/200ms属机制检查，不冒充远端30s时序；本机Go1.26，不是目标Go1.24实测 | `tools/go-client-idle-focused-01.log`、`go-client-idle-main-compile-01.log`、`go-client-idle-verification-cleanup-01.json` |
| 固定TypeScript构建 | 隔离13源/config/static；Node24.16/TypeScript5.9.3、@types/node24.10.1，原lock SHA ff3024df…6f5a9e不升级。npm ci及原npm run build均exit0，8产物hash保存，13输入最终0漂移 | `tools/file-transfer-browser-contract-01/npm-ci-02.log`、`build-01.log`、`input-bindings.json`、`source-recheck-01.json`、`build-output-hashes.json` |
| 实际Chrome UI四场景 | 现有Playwright Java1.58/系统Chrome156.0.8078.47，实际编译client.ts下载按钮，受控127.0.0.1 HTTP只GET。64MiB Blob真实保存，SHA103f23a1…4dbd5b5，35331ms；短EOF/错SHA显示原错误、0下载、按钮恢复；stall在35586ms检查点已ERR_ABORTED/报错/0下载/按钮恢复；exit0。35586是并行成功下载后检查时刻，不是abort触发延迟 | 同目录 `browser-focused-03.log`、`stall-error.png` |
| 实际Chrome精确30秒 | 新增独立stall一次，不复跑前三场景。被动记录原生非空read resolve与原生abort，保留原方法/结果/clock/30000值。最后非空read→DownloadIdleError abort=30000.0ms，请求失败即时回调距唯一body写30002ms/距click30021ms；ERR_ABORTED、0下载、按钮恢复、exit0 | 同目录 `browser-stall-timing-01.log`，`BrowserStallTiming.java` |
| 本地编译阶段账目解析 | 仅新增1个synthetic方法，02 exit0：显式LOCAL_VALIDATION_EVIDENCE归aux/LOCAL_PROCESS_ONLY，保留原始int退出码及日志SHA、不合成root/不消解原UNKNOWN、不算模型业务；缺/非法退出值不伪造。01因Windows CRLF fixture摘要预期不符而失败，修fixture后仅重跑此方法 | `tools/parser-local-validation-evidence-01.log`、`parser-local-validation-evidence-02.log` |
| candidate08限定路径/冻结target契约 | 两个直接方法各exit0：sample精确路径范围、三处frozenTargetOverrides证据/字节绑定；没有重跑旧全套。属于归属交付检查，不是新业务测试 | `tools/source-delta-sample-scope-test-02.log`、`source-delta-frozen-target-test-01.log`，摘要列于candidate08 verification |
| candidate08真实交付检查 | 77归属路径/76入patch（53旧/23新）；Go main与baseline同而排除。独立apply check/apply均0、76目标逐字节相同、生成时源/输入漂移0、临时树清理；UNFROZEN/final=false，现属source07/04历史 | `tools/source-delta-candidate-08-verification.json`，SHA6c1f4045…3e75625 |
| Go新增上传相位定点 | 3个指定方法exit0：`TestUploadObservationBodyAndCommitPhases`含慢真实body/收齐后处理stall两个子例，`TestUploadObservationIdentityDuplicateAndSafeErrors`核正常/重复/冲突及安全错误，`TestUploadObservationCancellationThroughNode`含真实Node→Go取消子进程。后者copied32768B/ctxCancelled=true/committedChunks=0，原Node destroy2次，unpipe后data observer0 | `tools/file-transfer-upload-observation-01/go-upload-focused-01.log`；冻结清单source-sha256.json SHA492e79bd…08f469b |
| Node新增上传相位方法 | 单一新方法“upload proxy distinguishes body transfer from processing and detaches observers”，02 exit0；3个受控请求区分body与处理，观察器移除，保留原取消控制调用。真实Go取消child由上行Go方法覆盖，不另加作新的独立Go方法数 | 同目录 `node-upload-focused-02.log`；取消child stdout在Go日志 |
| 上传server-only TypeScript | 作者server停笔SHA bff2de36…b6cf1后，仅实际server.ts/contracts.ts及原tsconfig/package/lock共5输入；原lock固定TS5.9.3/@typesNode24.10.1，npm ci与`tsc -p tsconfig.server.json`实际exit0/无诊断，输入前后0漂移，只出server.js/contracts.js。未client/full build或browser | `tools/upload-typescript-build-01/compile-verification.json`，SHA `92c31c5f80d19153829841893940abb50ae6a2ffdf698fa3e6309be66f8d3c09`；原stdout/输入bytes/清理另存同目录 |
| 上传改动Go main编译 | 父实际 `go test -mod=readonly . -run ^$` exit0，0测试，只证明main编译；两个专用临时目录绝对范围/无reparse核后删除，共享module cache保留 | `tools/go-upload-main-compile-01.log`、`go-upload-main-compile-cleanup-01.json` |
| candidate09真实候选验证 | 80归属/79入patch（54旧/25新）；原生独立apply check/apply均0、79目标bytes全同、185输入证据无漂移，临时树清理。既有上传范围contract单方法exit0被引用，本打包步骤新测试0；UNFROZEN/final=false，不是业务验收 | `tools/source-delta-candidate-09-verification.json`、`source-delta-candidate-09-verification-03.log` |
| observer上传匹配静态检查 | Parser0错误、5个限定上传路径/方法匹配case；actualCimExecuted=false，只核新增匹配规则，未执行最终进程观察 | `tools/process-observer-upload-check-01.json` |
| source08正式源码冻结 | 正式79文件（54旧/25新）、695328B，真实apply均0、当前源和输入证据0漂移。actual final仅源码快照，不证明四项业务通过；candidate09仍保留UNFROZEN历史，未改候选标签 | `tools/source-delta-final-source08-01/`；正式SOURCE状态FROZEN_VERIFIED_DELTA/final=true |

finally四方法选择器为 `businessFailureKeepsIdentityAndStackWhenCleanupAlsoFails+cleanupFailureKeepsIdentityWhenBusinessSucceeded+absentCleanupFailureDoesNotCreateFailureOrSuppression+sameFailureDoesNotSelfSuppressOrLoseExistingSuppression`。主fixture修复后原bytes SHA ad56f0a8…65ccaba；新增test SHA6764f8a1…af6fcb。初次Maven失败未进入JUnit，限定重试未扩大范围；环境适配后的f24实际bytes与主树文本SHA分开记录。随后Java被动诊断有新增改动，不能把该旧hash称当前fixture。

最终合同是客户端下载方按非空body进展执行30s idle：Go有效下载路径清除继承写deadline，Node成功下载body清除上游socket计时；连接、等headers及其他路由保留原政策，Java验收10/20s保持。历史逐Write续期测试和旧未改Node positive06保留为旧版本证据。浏览器是实际UI专项；Node Web Streams不替代浏览器，Go/Node loopback不替代目标正式业务。未新增产品运行依赖。

## 保留的失败、负例与修正

- `compile-01.log`、`compile-files-02.log`、`compile-files-03.log`、`compile-recovery-fixture-04.log` 保留本地编译/fixture 集成过程；不能把这些本地失败列作远端业务失败。
- `targeted-java-recovery-02.log` 的安装命令顺序断言和原任务 fixture 启用失败，随后审批单方法通过，恢复组仍失败，最终仅对恢复组重跑通过（03、04 日志）。
- helper-only 测试曾遇 sandbox TEMP 的 AccessDenied、失败结果缺少 problem 的 fixture 构造错误，以及 f24 所用模型 profile/assignment 构造器接口不匹配；02、03 等原日志保留，后续指定组分别通过。执行副本的四项环境/API 适配与正式补丁投影边界单独记录，不能当成本轮新增模型迁移功能。
- checkpoint 定点首命令因 PowerShell 参数解析成为未知 Maven lifecycle phase 而没有执行测试；第二次真实集成测试因 fixture 把旧 key 长度误写为 67 而失败，原值实际为 68；修正断言后真实集成通过。旧 3 个诊断方法已通过，不复跑累计。
- 原诊断大附件场景首次使用默认 ObjectMapper 序列化 Optional 失败；独立大附件方法修正后通过，未将原先中断当作产品远端诊断失败。
- 工具链与 systemd 的 Python 初次 TEMP/MSYS 权限、Windows signal fixture、marker 子 shell 及 bash 路径问题和修正，详见上述子任务记录。Windows 临时长路径与补丁工具清理失败历史保留在 `tools/source-delta-candidate-01.log`、`tools/source-delta-contracts-02.log`，后续真实 apply 和安全清理通过。
- 新审证测试早期22个fixture错误因缺必需server引用，补齐原server intent及recordReference后限定重跑通过（`candidate-compensation-review-java-01/02.log`）；最新successor阶段的12个失败另因固定Python注释命中已有危险shell规则，只改注释并重跑12，不放宽规则。早期22与最新Review27有重叠，不相加作唯一总数。
- Python08中mock lstat把不存在的永久claim看成已存在，导致3个预期REFUSED的场景返回UNKNOWN；另1个真实临时文件遇sandbox TEMP拒绝访问。修正fixture和TEMP后09仅重跑4个失败方法通过。旧sandbox临时目录已按确切路径及无reparse检查清理，见 `candidate-compensation-python-sandbox-temp-cleanup.json`；不据此宣称当前所有任务临时目录或进程均已清理。
- 下载修复保留原模块缓存权限拒绝、依赖下载网络/零字节中断、Go/Node断言、harness超时及连接reset。原Go整响应期限人工缩为80ms时，持续loopback读取64MiB仍只收1015597字节并有socket写超时；Windows返回net.OpError，早期只期待UnexpectedEOF的断言因此失败。人工Node upstream100ms idle负例只证明截断会被拒绝，不能当目标30秒真实时序。早期小TCP缓冲/Connection:close场景即使handler accepted全部字节仍client reset；最终Go fixture使用与实际Node upstream一致的默认keepalive，差异未作远端唯一归因。见 `tools/local-download-truncation/manifest.json` 及其retainedEvidence原日志，不把重复运行累计成新场景。
- 旧阶段Node最终positive06仅在临时副本替换导入扩展名，该旧阶段server.ts/contracts.ts生产源未改；临时root的realpath/直属父目录/固定前缀/非symlink核验后才删，listener关闭。Go缓存、复现exe及作者自建进程清理证据为局部预检；复现exe删除前未捕获二进制SHA，源码/stdout保留。局部清理不替代最终全任务CIM，也不证明本轮新产品入口已结束。
- finally定点首次Maven在resolver-status.properties遇AccessDenied，exit1且未进入JUnit；限定重试沿用原四方法、完整引用-D参数后通过，01/02日志均保留，不归为远端故障。
- Node被动诊断首轮终止fixture产生LOCAL_CLIENT_ABORT；下一轮原首事件断言期望RESPONSE_ABORTED而实际PROXY_ERROR，exit1。03只重跑终止顺序方法通过。两失败harness及首个事件的本地含义保留，不能拿观察顺序倒推远端因果。
- `tools/local-backpressure-proof-01/manifest.json` 明确MECHANISM_NOT_REPRODUCED、testExit1：人工500ms后端窗口、小socket缓冲与慢本机客户端未形成预期backend背压；backend已接受67108864B、timeout=false，客户端当时只读9984B，200ms后12416B；无自然EOF/完整SHA，主动关闭client并回收Node/临时模块/cache。没有参数调优或第二次探针，不将未复现记为通过或证明远端没有背压。
- 固定依赖首次npm ci在沙箱创建@types报EPERM/exit-4048；改用已授权环境和任务cache后固定lock ci成功。首次Playwright.create误触默认浏览器下载，已只取消本次新driver，Java exit1/监听回收；禁止下载并指定系统Chrome后，Windows JDK长Unix socket临时路径仍初始化失败，exit1、未开浏览器；沿用项目既有短临时路径后实际UI03成功。保留所有失败日志，均不是产品远端业务失败。
- 03的原business failure被finally STOP UNKNOWN覆盖，原异常正文不可恢复。fixture修复及4方法通过只保护后续诊断，不追造原business异常。GET01额外120s cap/exit3与GET02无cap自然短EOF/exit2分别保留，后者46624797B与Go同version journal timeout=true同字节，不能覆盖更早10510949B/timeout=false记录，也不能改记03业务通过。
- 新上传Node定点01假定刻意断开上游必得到固定JSON502，实际既有TCP链路可直接ECONNRESET，exit1。只改新test接受既有失败结果，02限定单方法通过；没有生产fallback、重试、超时或转发行为修改，原失败日志保留。真实Node→Go取消child实际32768B后Go ctx取消/committed0，不把Node本地OS finish说成服务器提交或最终客户端回执。
- candidate09辅助校验01错误期待unchanged Go main行有targetSha256，真实schema为null；02把LF重建bytes与Windows实际CRLF执行bytes直接比，均exit1。原生apply及目标bytes当时已通过；修辅助断言后03 exit0，精确CRLF重建等实际执行SHA，规范化文本未变。两失败脚本/日志保留，不当源码编译或远端业务失败。
- candidate09 cleanup01的CIM发生非终止访问错误，原matchingOwnedAuxiliaryProcesses=0没有有效进程证明。cleanup02保留并引用原记录，只纠正该项：ErrorAction Stop、查询无错误/匹配0，范围仅本打包的Python/pythonw/git命令，未碰full05；185输入摘要及临时树事实独立有效。不能把01无效零值或02局部成功当成最终全任务CIM。

## 完整04已知结果与诊断边界

04实际exec48916 exit1，JUnit1/0失败/1错误/0跳过、1265.403s，原SocketTimeoutException: Read timed out完整保留为primary，说明finally诊断修复在本次失败中生效；不是业务通过。原log SHA `491e9312d7942e3cbb533b358b078075a7a4159174dc28d04515be1440a8a33a`。publication `c7d36e89-730c-4d04-a2bb-fcb1945d481f` 本身SUCCEEDED，app `wtl-assisted-file-transfer-9zpvbig78`、state `assisted-live-file-transfer-9zpv9ukyc`；revision08d7fbf6…43e5694、ownershipd362c6a2…a9c70df9。模型deepseek-flash、LOW/LOW、请求65536/观察阈值1500000/90min及独立审核不变，发布成功不等于完整验收。

64MiB upload `3915daf8ecc6be4a0ba829681f624454` 使用原1MiB分块；16块全200、重启后16状态保留通过，续传到index47均200。index48（第49块）10:41:03.351316Z进入RESPONSE_WAIT，10:41:23.353Z报read timeout，等20001ms、无响应头；本地write约2ms仅证明写入本地缓冲。未到complete/下载/第二版本或管理成功导出。chunk45/46/47本地write2/2/3ms而等200需13.493/5.676/8.608s，不能把本地write时长当线上上传完成证据。

原finally STOP `9fd92f41-d0b3-4cbe-8bd2-dbad35450859` SUCCEEDED RUNNING→STOPPED，后续inspect `28867199-390c-492b-ae2c-45b6efee79c7` 和 `5fc7d94b-dfa9-4c67-8257-1be6be554bde` 均STOPPED/DISABLED。`tools/file-transfer-04-finally-stop-receipt-01.json` 绑定原日志实际结果，非另发STOP，无UNKNOWN/重放；不补造成功管理导出，原数据/发布保留。

独立监督只读 `file-transfer-04-upload-read-only-01.log` actual exit0，仅两个unit show/journal和原upload精确chunks：MainPID0/inactive、物理0..47各1048576B/uid979、无48；不证DBcommit，未重启或重传。旧journal无上传相位日志，尚不能定位请求体/处理/响应链路具体停点。20s响应等待可能包含实际线上上传和处理，1MiB平均链路低于约51.2KiB/s时可能超原20s；环境原因没有证实，也不保证存在必需代码修复。下载30s合同不适用上传，Go/Node全局30s和Java20s未变。

随后已完成Node/Go被动上传相位诊断及上述新增定点，冻结清单为 `tools/file-transfer-upload-observation-01/source-sha256.json`（SHA492e79bd…08f469b，5源）。Go只增加读调用/stagingWrite/Sync/metadata/reply耗时及ctx/errorType、每请求最多32条；Node仅合法32hex upload/chunk路径的incomingbody/end/OSfinish/headers/cancel、最多12条。data observer在原pipe后挂接并于unpipe/终态移除；不记录payload/headers/任意异常正文/路径、不硬编码UID，不改锁/落盘/commit顺序、超时/规模/重试/审批。计时不是纯网络时间，诊断不是回执，04原因仍UNKNOWN。client/browser/GET旧组未重跑。

05原11:08Z用source08启动，publication `3864c25a-fc06-4031-8f17-a07679067037` SUCCEEDED，app `wtl-assisted-file-transfer-ay1rdwz3k`、state `assisted-live-file-transfer-ay1rcfcb8`，revision `4419245542a25cee77b572beb937683ddc0eaf8debba57bf2d5b648c9eb7003b`、ownership `e016476951696a9bb9cebed221bb94832c3d6b0311cf010c03c0545fee0cbe9d`。前16块HTTP200后提交过原重启 `1c3d7b34-4e02-42a3-8820-98a1b201bc37`，不靠后续进展补推独立终态；截至11:25:05Z原upload982f…的0–63共64×1MiB HTTP200，进入FILE_TRANSFER_DOWNLOAD_BEGIN，version `01a1a17dde8b3c332fc9297fbe02c616`/expected67108864B。

用户随后要求流程较多时简单收尾并保存当前状态。父精确停止本轮Surefire47092，Maven/wrapper97287实际exit1；首下载只有BEGIN，无return/SHA，未产出JUnit终态/完整V2 PASS。该exit1来自用户中断，不能当自然SocketTimeout/业务FAILED。当前首下载未验完，后续仍有第二版上传、另4次完整下载、取消/错误SHA、重启持久化、真实downstream502/组件恢复、原真实管理导出等。原模型/审批/预算/connect10/read20/Go30/Node30/1MiB/64MiB/无retry不变，新增本机定点不补齐这些远端验收。

05暂停收尾已完成，过程分列保留：cleanup01 wrongcwd启动失败；02只到MOD_RECOVERY_READY，inventory唯一原RESTART1c3d7b34…中断后UNKNOWN，inventory-local-01.log实际exit2是清单读取成功但close保持资源占用。随后原产品RECOVERY只核这一条，file-transfer-05-original-restart-reconcile-01.log actual0，task1c3d7b34-4e02-42a3-8820-98a1b201bc37 ENDED/SUCCEEDED、blockerCount0，release/close正常、newLifecycleSubmissions0，没有重发RESTART。

再另开正常cleanup03 actual0：前inspectcdf7808f-24c4-4f5c-9fa3-00cfece74b69，新STOPf24658bc-ba03-40ea-8880-e8ad79dbb9f6，后inspect84af1484-88b8-4e18-af09-19e7d57a6e9e，STOPPED/DISABLED。独立tools/file-transfer-05-user-requested-cleanup-receipt-01.json SHA70f3b525b9104cb34d89fb2cddeca3e00e41b053ebab7300fd2a7c16574a5656是收尾回执，不是管理导出；旧失败/UNKNOWN不删但已不阻塞移交。通用status对简写原恢复行仍UNCONFIRMED属格式未识别，实际原task/receipt/releaseclose证明已结清，本次不扩展parser。

file-transfer-05-user-pause-read-only-01.log actual0保存正确fingerprint/两原unit路径/212Go及40Node安全日志，0reject/0truncation，无远端SQL/文件/服务写。其PID状态在停服前采集，不代停服后PID证据，最终停止以产品cleanup03为准；原数据/发布没有删除。本次文档回填没有执行测试、产品、DB或远端操作。

## 执行来源、候选交付与未验证边界

04执行来源为 `execution-source-07-verified.json`，SHA `0c4615ea582c43aeb0db6908a862a561926518656198086a2ed1c6aeffd7467b`，现为该失败轮次历史来源。它只纠正原07继承的计数/指针摘要，files2361逐项仍完全相同；原07 SHA `2bf7bf48efd855047eb2415424bc59a3f25daf4e40c4f443c6388399a2058eca`保留，不代表改源或新部署。启动前及Maven后2361文件复核零漂移，见 `tools/source07-post-maven-start-check-01.json`，本次没有重复检查或编译。样例为f24/test普通副本32文件/115952B，快照manifest SHA `33371b9081f3b75df406da978bb6e0c731e94754fb0bcf0642bd08b0959e2bb9`，不是junction；旧source06的2358/sample30及更早误算2386不用于本阶段。

05执行来源为 `execution-source-08.json`，SHA `f27042f8dcacc3cad340556710def68c355d07e75d1af179e83c3022766e1a8c`；父启动前及Maven后2363路径均零漂移，后者为 `tools/source08-post-maven-start-check-01.json`。样例34文件/150755B快照manifest SHA `e27fbff9fd683b2a9db8e980896ac3c93ca99ad3b323da29f456a3ed5d9456ab`。仅六个普通f24/test副本：Go http/uploads、Node server、README四旧+两新上传测试，pre-sync保存实际旧bytes/absence（manifest801af109…4775ab7），4 changed+2 new，其他依赖漂移0。Java fixture/f24实际源、helper16/guard和四适配沿07不变。source07/08统计按各自阶段保留，不覆盖旧清单。

README仅补上传相位观测含义及每轮ASSISTED实际报告链接，现SHA `472434da3548271e56380240f7f411c401913ba233887442f05f6d7383aa8a0b`，旧134fb4…原bytes为 `tools/upload-observation-readme-before-01.md`；不再沿用“标准Linux未执行”笼统陈述，也不把真实失败轮次写成通过。05预检原指纹、可用21577596928B、旧units无活动网站监听、exit0，是入口前观察，不代最终环境证据。

公共helper16/SHA4b44004b…4be0d3c和补偿compiled guard12693e8a…6a565c7不变。执行副本四适配仍为原credentialFile引用、数据父目录realpath、profile七参null/null、assignment enabled=true；未复制密钥/DB或改模型。Maven换行归一化以actual f24原bytes记录，不能用同步工具文本hash冒充实际执行hash。

当前源码增量仍基于最早混合现场逐文件baseline，不是原HEAD独立可构建差异。最早49不覆盖，后续sample额外真实原bytes仅只读引用，新文件依据各自编辑前absence/创建证明纳入。configureLiveModel仅delivery projection恢复原baseline方法，主树物理源保持。Approval/Scope/PublishPolicyContext三处其他任务改动已排除，comparison.json证实f24各自等candidate07重建旧target，精确frozenTargetOverrides绑定该旧bytes/来源，不能放开导入任意current或覆盖主树。

10:25Z主fixture另有外部configureLiveModel方法内变更：冻结main362cc6d6…3e25845、当时current d28c17ce…373496a；逆f24四适配得到旧冻结bytes，方法外逐字节一致，证据 `tools/source07-fixture-projection-drift-01/comparison.json`。既有restoreBaseline方法投影排除此变化，物理main/f24不改，source07和04运行来源不变。主树与执行源的一致性陈述只覆盖明确排除的三policy/该方法之外、计入环境适配和实际换行差异的范围，不能扩大为整树全同。

正式patch/SOURCE已由父替换为source08冻结版：79文件（54旧/25新）、695328B，patch SHA `51de3b841d82a5efe9c526c6e05077322ebd6803dd53f0558dc346fecba95f2e`，SOURCE SHA `f13a108a6ff9bd84af89425a6f46148fa2f0037c88c65d5395ae5396ba013019`。`tools/source-delta-final-source08-01/` actual final只证明该源码快照，真实apply均0、当前源/证据0漂移，不等于四业务通过。旧55正式文件在 `tools/formal-artifacts-before-source08-01/` 留存；本次文档作者没有改patch/SOURCE。

candidate08及更早记录仍为历史；candidate09原80归属/79入patch（54旧/25新）、695328B与185输入验证也保留原UNFROZEN/final=false，不追改为正式final。Go main unchanged排除，最早49旧baseline不覆盖，额外6份sample原bytes只读引用，原生apply/79目标bytes通过，不是HEAD独立构建证明。

三policy旧tested目标与fixture仅configureLiveModel投影/唯一if换行（target `6eca30a6119f1272ae3cfdbcf921ccd04aca2e60c36b1b98798919e742b6006b`）的边界沿用，物理main/f24不改。候选辅助失败及cleanup02修正见上文；源码final与候选历史、真实业务中断分别记录，不覆盖旧ownership或失败日志。本轮本地提交范围限定为patch/SOURCE/main/LOCAL/HANDOFF五个正式文件，不推送；实际提交号见项目进度入口 `.ai-workspace/progress/CURRENT.md`。

Python本地在Windows受控模拟uid/mode/flock、cgroup/unit/mount观察；真实Linux锁竞争及POSIX全进程组syscall/子孙全组退出未由这些用例实测。真实Windows子进程超时/取消回收只支持其实际范围。局部服务器补偿回执只支持该次精确动作，不泛化全部Linux故障。

浏览器成功harness关闭context/browser/自有HTTP executor并实测监听拒绝；任务范围CIM queryCompleted=true/匹配0，隔离build/cache/classes/64MiB下载树经绝对路径及无reparse检查后删除。Go/Node作者也有各自局部进程/缓存/监听清理记录。这些局部检查、07:44线程栈、早期未识别PID或被拒IO查询均不是最终全任务CIM；此次独立最终观察见下文，不用无进程猜远端终态或给中断run补exit0。

早期observer Parser+5case及candidate09 cleanup02局部CIM原边界保留。现在已有独立normal-Windows最终本地CIM：process-observation-user-pause-01.json actual0，11:44:15Z queryCompleted=true、匹配0。它只覆盖可见命令路径/已标记祖先后代范围及该时刻，不是全服务器/永久保证，不从本地缺席推远端结果。CSV准备03原exit不可恢复，只有现进程缺席证明，不猜0。05远端停止有独立产品回执，不靠CIM。

## 暂停时实际用量审计（不计入测试数）

usage-audit-user-pause-01.log actual0，12/12本地已有保护库按READ_ONLY_EXISTING_SHARED_LOCK读取，无模型/SSH派发；usage-user-pause-01.json按本轮cutoff1792699267116ms合并stdout+persisted，generation ID+attempt去重，已识别物理尝试561/561双方匹配、stdoutOnly0/persistedOnly0。已知input5062722、output492393、input+output5555115；cache2740479/cacheMiss2322243属input子集，reasoning421238属output子集，不重复相加。

44条能力探测Token/物理请求未知；额外请求总量、physicalRequestTotal、账单及Codex用量未知/未含，不能把已知数说成总账单。persistentAuditComplete=true、finalTaskProcessCheckVerified=true，unresolvedOriginalTaskIds/runningOrUnconfirmedProcesses/unavailableUsageRecords为空；allFourFullRetestsCompleted=false、readyForFinalAccounting=false因05用户中断保持，不为交接强改true。摘要器单行UNCONFIRMED的格式边界不否定独立真实原恢复证据。

暂停交接已完成：05原任务恢复、产品STOP/禁自启和独立本地进程观察、暂停时用量审计均有实际证据；业务仍3完整PASS/file未全验，用户中断不补JUnit/管理成功导出。源码final不等于业务完成，本轮仅本地提交五个正式文件，不推送；提交标识保存在项目进度入口。

本地证据和DPAPI保护state在证据根及src/app/db/data保留，不复制或打印凭证，不是远端数据/日志备份。需保留的远端内容应在重装前另备份，重装后旧证据无法补查；重新fingerprint/产品helper/工具链核验、新审批/许可，不沿用旧grant或重放旧部署。本次只改文档，没有新测试/编译、产品、DB或SSH。
