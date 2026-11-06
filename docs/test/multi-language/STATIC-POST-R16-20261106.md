# R16 后独立 STATIC 验证记录

本记录截至 S20 TaskBoard 完整原次通过、FileTransfer 原次失败时，静态业务为 **1/8**，另六项未启动。S17 至 S20 是独立 STATIC 版本号，AI 辅助验收的 R17 尚未启动。用户要求的顺序为：完成 R16 原八项，再运行独立 STATIC 验证；静态问题修复并验证正常后，才返回 AI 辅助测试。R16 原八项已封存，首过仍为 **0/8**；其 FAILED、UNKNOWN 和资格 false 不由本阶段改判。实时进展见 [CURRENT](../../../.ai-workspace/progress/CURRENT.md)。

## 已消费原次

| 独立版本 | 服务器准备 | STATIC 业务原次 | 结论边界 |
| --- | --- | --- | --- |
| ROOT16 `92f35176…` | 新 inventory、plan、apply、独立 after 均通过 | TaskBoard 失败；其余七项未启动 | `MIRROR_PRIVACY_CONSENT_REQUIRED`；不算静态通过 |
| S16 `24c0ffba…` | 全新四阶段均通过 | TaskBoard 失败；其余七项未启动 | `DEPLOYMENT_BUILD_FAILED`，Maven 分发固定源连接超时；不重放该原次 |
| S17 `8250f202…` | 新 inventory、plan 通过；apply 未完成；after 未启动 | 八项全部未启动 | apply 等待超时，清理实际结果为 UNKNOWN；四阶段未通过 |
| S18 `275e0650…` | 全新四阶段均通过，完整 after 与 apply.postInventory 相同 | TaskBoard 失败；其余七项未启动 | 业务 QA 通过，最终清理复查失败、CLEAN 缺失；不算完整静态通过 |
| S20 `39e67c94…` | 全新四阶段均通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard 完整原次 PASS；FileTransfer 原次 FAILED，其余六项未启动 | GO_MODULE 来源白名单拒绝，64KiB业务未到达；STATIC 1/8，AI R17 未启动 |

前两次 STATIC 的原件分别保留于 [ROOT16 执行证据](../../../.ai-workspace/evidence/post-r16-static-execution-owner-20261106-01/)和 [S16 执行证据](../../../.ai-workspace/evidence/post-r16-static-repair-execution-owner-20261106-01/)。S17 的原件见 [执行证据](../../../.ai-workspace/evidence/post-r16-static-cdn-execution-owner-20261106-01/)及 [父封存审查](../../../.ai-workspace/evidence/post-r16-static-cdn-execution-inputs-20261106-01/s17-preparation-incomplete.PARENT-REVIEW.json)，后者 SHA256 为 `fdd38e32bf67d1a4984cca4941814f883286004f015220621d291680a26ac356`。这些版本各自独立，不拼接准备阶段、通过项或计划。

## S17 准备异常及本地收尾

本轮 plan 完整原件 SHA256 为 `d2c053418482f68406b1238025828387dfa4c81c2b80db40e6266cb7e450f4be`，内层 canonical plan SHA256 为 `b00a833c4d68f99a4a1137b1a8b7d96308818f9d36360e0a87d2adf73f2b623a`，已独立复算并完整审阅。计划涉及五个现存产品目录、恢复服务文件和 31 个构建开发包；与 148 个必须保留的系统包无交集。该审查证明的是计划范围，不是清理实际完成。

apply 原工具 session `58023` 由 `02cb65` 实际 exit1、无 session 结束；原 Maven CMD 启动句柄也实际 wait/exit1。JUnit 为 **1/0/1/0**，1934 秒后在原 32 分钟 future 等待处出现 `TimeoutException`，并附同一任务 `abbfaa49-527c-4094-bf38-9e2458535c2e` 仍持有 Mod 资源、阻止 `runtime.close` 的异常。没有 apply 完成回执、远端阶段、命令退出或清理摘要。**清理验收为未完成／UNKNOWN；实际 TaskState 未直接观察，不能据此断言远端没有执行、已经完成或具体原因是 APT 锁、网络、审批。**

原四个已记录本地 actor 按 PID 与原出生身份核为消失；异常收尾没有调用成功 closeout。两处自有 TMP 经 owner、绝对边界、祖先及整树 nonreparse 检查后删除，原清理工具 `038a7c` 实际 exit0，helper 退出观察 `03aa41` 实际 exit0。原证据、源代码、compiled 与实际 normal store 保留，未脚本观察或打开失败库。内部 Maven JVM 身份仍 UNKNOWN；本地收尾不等于远端效果或业务 UNKNOWN 已解决。没有重试、独立 after 或业务启动。

## 有界修复与实际本地验证

- S16 仅修测试准备入口：通过原产品 API 保存固定来源、关闭自动选择和隐私同意，再捕获；AGENT 原提前跳过路径不变。
- S17 本地提交 `8250f2027d4bff16b6407b713bd873dc2bcb89c6` 增加可选 `managed.live.mavenDistributionBaseUrl`。本轮明确固定为 `https://dlcdn.apache.org/maven/`，只替换 `MAVEN_DISTRIBUTION`；产品语义为 `custom=true / official=false`，其余来源和默认 catalog、版本、SHA、超时、重试及 fallback 均未改。必要离线打包通过；两个明确筛选的参数化 preparation/capture 方法共 **4/0/0/0**。真实 SDK 独立网络诊断曾观察到该 CDN 的 TLS 验证和 HEAD 200，但不证明 GET、下载摘要或 STATIC 成功，也不证明旧连接超时的唯一原因。
- S18 本地提交 `275e065048552ce7cf2c559079ffe0efa3f3ee65` 只改一个 reset 验收测试文件，增加 callback、CONNECT、REVIEW、EXECUTE 阶段标记。EXECUTE 返回标记在回执写入前；仅输出安全状态，ApiException 的 code/event 用显式白名单或 OTHER，并原样重抛。原超时后只读取同一句柄的缓存 state/outcome 枚举。关闭 phase 只表示进入该阶段，不证明成功关闭。此改动用于下一原次定位，**尚未证明修复未知根因**。

S18 本地编译阶段只运行离线 `-pl src/mod/deployment,src/mod/files -am -DskipTests package`，33.510 秒、原工具 `9092c5` 实际 exit0；该阶段所有测试跳过。原工具对象未另存，补件明确保留为忠实观测投影，不冒充 raw 返回。指定 13 项公开 source/class/resource/config pins 已核对，构建 TMP 已清理；这不是全 compiled 或私密输入冻结资格。见 [S18 实现交接](../../../.ai-workspace/evidence/static-reset-phase-observation-fix-20261106-01/HANDOFF.md)。上述提交仅本地，未推送；后续真实执行另记如下，没有运行全量测试、模型或 AI guard。

## S18 四阶段及首个业务原次

本轮重新冻结输入，完整执行新的 inventory、plan、精确计划父审、apply、独立 after；四项均为原 native/tool exit0、JUnit **1/0/0/0**。新 apply 完成回执 SHA256 为 `a243417d01b223bc3251e285d46541faaed6bce545b26f2c03c2f15d88a6bf4c`，独立 after 回执为 `043786ae7c28baaf3e7d15829b3e8d354fea2f717921d2e5f40b831e619506be`。父审逐字段比较完整 after.inventory 与 apply.postInventory，canonical SHA256 均为 `82e0d077f2a00d57e542e7bce91bd50ab87665b358533e5e04de4f67f90e2afc`，没有排除字段。148 个必留系统包记录不变，655 个剩余包恰为原盘点减去计划的 31 个包，无额外升级或移除。见 [本轮四阶段父审](../../../.ai-workspace/evidence/post-r16-static-phase-execution-inputs-20261106-01/four-phase-preparation.PARENT-REVIEW.json)。这不改判 S17 的远端 UNKNOWN，也不证明其超时根因已修复。

只放行了 TaskBoard 一个业务原次。应用 `wtl-static-task-board-b8fqrrkho/static-live` 发布成功，HTTP、停止／启动／重启和持久化 QA 通过，出现 `STATIC_LIVE_PASS task-board modelCalls=0`。但该标记在 finally 之前；最后原 STOP 虽观察到 STOPPED／DISABLED，随后独立 inspect 失败，**没有 CLEAN**。原 session `14175` 由工具 `2133e9` 实际 exit1 结束，JUnit **1/1/0/0**、1425.283 秒。因此整项为 **FAILED**，其余七项 **UNLAUNCHED**，不能用业务部分通过代替完整验收。

最后 helper.probe 的原始命令结果为 `FAILED / IO_FAILURE / SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT`，无退出码；日志记录 CHANNEL_OPENING 用时 30001／30000 毫秒、通道未确认打开、输入发送线程未创建、作者载荷未发送。上层却改报 `INCOMPATIBLE / DEPLOYMENT_HELPER_IDENTITY` 并丢失原诊断。源码已确认这一分类缺陷，以及两个安装消费者把任意 FAILED 当作安装条件的问题；**现有证据不能认定 helper 内容损坏，也不能确定通道打开超时的底层原因**。最初冷准备的真实 exit64、原流程内的 tracked 安装及后续成功 probe 单独保留，不与这次无退出码的传输失败混淆。分类修复与精确 SSH 观测已完成如下本地验证，没有调整超时、重试或验收门槛。

原四个已记录本地 actor 按精确 PID 与出生身份确认消失；只清理本任务异常 TMP，清理工具 `45194a` 和 helper 退出观察 `b177b3` 均实际 exit0。原 evidence、compiled 和 normal store 保留，未调用成功 closeout、补查旧应用或重开失败库。Maven 内部 JVM 身份仍 UNKNOWN；本地收尾不证明远端资源归零。原结果及本地收尾的分时记录见 [S18 业务父封存](../../../.ai-workspace/evidence/post-r16-static-phase-execution-inputs-20261106-01/s18-taskboard-failed.PARENT-REVIEW.json)与 [执行证据](../../../.ai-workspace/evidence/post-r16-static-phase-execution-owner-20261106-01/)。

## S19／S20 修复及本地验证

S19 提交 `4193db873dbd8c74ffab73ead71c4aa9357b2da1` 只改四个生产类和三个直接测试。probe 保留原传输失败及 context／diagnosticId；只有完整可信的非零命令证据才分类 exit64 或 platform exit3；成功响应若缺少完整身份验证依据，则返回 UNKNOWN。两个安装入口共用严格准入。包安装后的环境流程仍为 UNKNOWN，原 Problem 与完整检查点保留，不改 Runner、Journal 或持久化格式。新机制七方法首轮通过；原 Bootstrap 三方法先因缺少 Files 编译描述、再因隔离库 H2 只读在前置阶段失败；补齐必要编译后，仅同三方法在原本机权限下通过 **3/0/0/0**。各轮原失败保留，不拼成首轮全绿，H2 原因未单独确证。见 [S19 交付](../../../.ai-workspace/evidence/static-helper-probe-evidence-fix-20261106-01/README.md)及 [进程证据限制补充](../../../.ai-workspace/evidence/static-helper-probe-evidence-fix-20261106-01/CLOSURE-LIMITS.md)。

S20 提交 `7d030e334a91b09e038e0d281abac5da3a3953bf`、`d6167a88a08e9a9d7bdf5b8ff73165d61425922d` 增加每连接、单通道的有限元数据：具体 open 包的写 future、91／92 回复、OpenFuture 和最多四个所属线程的状态，时间统一为原命令单调时钟。记录不包含载荷、认证内容或原始异常正文；观察失败保留原传输行为，采样跨截止时不增加等待。WRITTEN 不证明对端收到，PENDING／FAILED 也不证明零字节发出。两个 STATIC 日志出口使用有限枚举／数字过滤。九个唯一 App 场景和一个投影方法分别定点通过；仅因相关修改重跑对应方法，错误的 synthetic fixture 与早期启动失败原件均保留，未重跑既有超时组或全量测试。详见 [分轮验证记录](../../../.ai-workspace/evidence/ssh-channel-open-metadata-20261106-01/verification-record.json)。早期 Surefire 覆盖部分 TMP 属性的偏差、未捕获 Java 子进程出生身份等限制也在该记录中保留。

最终 S20 HEAD `39e67c9441b9bf54ed424e16505d2a7cc0e4d3a7` 无冲突合入 S19 修复，相对 S18 共 14 个相关源码／测试文件，工作树干净、仅本地提交。父代理只补必要离线 `-o -pl src/mod/deployment,src/mod/files -am -DskipTests package`，原工具 `0f03ae` 实际 exit0，31.163 秒、BUILD SUCCESS，所有测试跳过。专用构建 TMP 已安全清除，source／targets 保留；内部 JVM 身份仍 UNKNOWN。见 [整合与编译证据](../../../.ai-workspace/evidence/static-s20-integration-20261106-01/README.md)。该编译本身不证明真实服务器或底层超时已解决，后续真实执行另记如下。

## S20 全新准备完成，首个业务已完整通过

本轮 inventory、plan、exact apply 和独立 after 的四个原 native／tool 均 exit0，各新 JUnit **1/0/0/0**。新计划完整回执 SHA256 为 `046affb1b7eaf534a3b3638b9ea0adfabbc7089c1c9c812b2ebe80fbd0ddb717`，内层 canonical plan 为 `6e4dd2d61b82629d5ce59eed8689334596b29288fc932859cbf02b3719d1729a`，已独立复算并完整审查。实际清理五个产品目录、三个服务、两个产品账号及 31 个构建开发包；服务文件实际删除计数保留为3，原流程先 disable 再逐文件移除，不将该计数强改成计划文件数4。完整 148 个必留包记录不变，655 个剩余包记录恰为原 686 减计划 31，无额外升级或移除。

apply 完成回执 SHA256 为 `90d3fceb0535a84ac2ad68ba12c63ac849b0a1ffd00d0ce73f2b8aec4ba49261`。独立新 after 的完整 inventory 与本轮 apply.postInventory 的 **59382 个 UTF-8 字节完全相同**，SHA256 为 `82e0d077f2a00d57e542e7bce91bd50ab87665b358533e5e04de4f67f90e2afc`，无排除字段或数字转换。相同旧摘要不表示复用旧原次；本轮各有独立的新任务、JUnit、native 和实际工具终态。16 个已记录 actor 均按精确出生身份确认消失，各自 TMP 已清、normal store 和证据保留，内部 JVM 身份仍 UNKNOWN。见 [本轮四阶段父审](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/four-phase-preparation.PARENT-REVIEW.json)。

apply 曾在派发前被自动审批拒绝，没有程序、native、session 或 JUnit。父随后在原对话的实际 userMessage 中核到一次性服务器的必要 CRUD／环境清理明确授权；补充该原始来源后，同一精确请求的正常审批复核获准，才首次启动 native。原拒绝及复核历史保留，不记作部署重试，也不改写先前记录。见 [直接用户授权来源](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/apply-direct-user-authority.PARENT-REVIEW.json)。

四阶段 owner 结束后，另由独立 owner 完成 TaskBoard 原次。应用 `wtl-static-task-board-ekeha0sqg/static-live` 的 HTTP、停止／启动／重启和持久化 QA、modelCalls=0 及同应用 CLEAN STOPPED／DISABLED 全部满足；新 JUnit **1/0/0/0**、1447.048 秒，原 session `17929` 由 `7ed185` 实际 exit0 结束。`ce0864` 实际 exit0 完成四原出生身份和自有 TMP 闭合，normal store 与证据保留。见 [首项父审](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/static-task-board-first-01.PARENT-RESULT-REVIEW.json)。

该原次有一次冷启动 helper.probe FAILED／exit64，随后原流程 tracked 安装成功，记录保留，不能写成所有子命令都成功。原 trace 的四个失败类型／原因缺省 UNKNOWN，以及首个缓存应用列表中的 state／autostart UNKNOWN 也保留；15 个任务结果和 14 个 AUTHOR RETURN 均为 SUCCEEDED，没有任务 UNKNOWN。字段层次不混用，旧 S18 的通道超时原因仍未知。

FileTransfer 首次审批在派发前拒绝，0 launch、无 native/session/JUnit，不记作业务失败。用户随后明确确认[本目标后续 STATIC 操作范围](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/static-goal-operation-scope.USER-APPROVED.json)，包括健康 R15 凭据经产品 API 保存至新冻结工作树内的新加密 normal store，以及既定八项目的必要安装、发布、上传下载、重启和已审计划清理；原断言、旧失败库、无明文脚本读取、本机系统不改和 STATIC 无 AI 约束保留。同一精确请求经正常审批复核获准，才首次执行 native，原拒绝历史保留。

FileTransfer 原 session `82393` 由 `4742a2` 实际 exit1 结束，新 JUnit **1/1/0/0**、193.685 秒。部署任务 `3d5a879c-c686-4f20-9b7e-27e190d3e8ad` 为 `IO_FAILURE/DEPLOYMENT_BUILD_FAILED`：ordinary／GO_MODULE／go1.24.13 构建中，`official-go_module` 报告 `destination/redirect outside selected source allowlist`，`modernc.org/sqlite@v1.36.3` 和 `modernc.org/libc@v1.61.13` 的 zip 下载返回502。实际被拒绝的重定向目标尚未观察到，不能先认定具体域名。PUBLISHED、BUSINESS、PASS、CLEAN 均未出现，原64KiB上传下载及重启业务未到达；finally 的空应用列表不证明远端无安装、构建、缓存或候选产物。见[本项父审](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/static-file-transfer-failed.PARENT-REVIEW.json)。

原 native 与外层 wait 均实际 exit1，无 bookkeeping 失败；后续精确观察确认四个已记录出生身份消失，内部 JVM 身份仍 UNKNOWN。异常 TMP 保留待限定清理，normal store 和证据保留，未执行 normal closeout。原公开日志的四个 typed 任务为3成功、1失败、0 UNKNOWN；owner 初始汇总字段误写为空 outcome/count4，逐条记录正确，父审独立计数并保留原错误记录。后六项和 AI 未启动，当前只定位来源规则，修复后须全新冻结及完整四阶段，不重放或拼接 S20。

## 后续验收条件

每个新版本或失败后的新轮均须另行冻结，完整执行 inventory → plan → 父审精确计划 → apply → 独立 after；独立 after 须与 apply 的完整 postInventory 对照，不得拼用旧计划、准备 PASS 或业务部分结果。S20 准备记录保留，业务失败后已停轮。后续新冻结仍按 TaskBoard、FileTransfer、AssetLending、CsvInspector、SurveyScoring、LogAnalyzer、DirectoryDiff、BinaryInspector 固定顺序逐项放行，每项均需原业务断言、modelCalls=0、同应用 CLEAN、JUnit 与真实 native/tool 闭合，失败或不确定不放后项。

STATIC FileTransfer 使用原 HTTP 文件应用的 64 KiB 负载，CLI 使用原静态 health probe；不以 ASSISTED 专用检查器替代。STATIC 不运行 AI guard 或私密 key hash，不修改已批准的 AI 15 秒／MAX25／0.2 秒观测规则。八项静态正常后才回到 AI 辅助测试；MOD 仍通过主 APP 四用途标准 API 通信，厂商适配由主 APP 负责。
