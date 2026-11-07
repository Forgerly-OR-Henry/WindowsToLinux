# R16 后独立 STATIC 验证记录

本记录截至 S24 全新四阶段准备通过后，首项 TaskBoard 原次失败封存时；本轮为 **PASS0／FAILED1／后七项未启动**，业务 START 任务保持 UNKNOWN，AI 尚未启动。S23 为 **PASS2／FAILED1／后五项未启动**，完整业务2/8；后续修复须新冻结，不拼入两项PASS。S22 保留 **PASS2／FAILED1／后五项未启动**，不能将两项通过拼入新轮。S23 修复已本地提交，28个定点检查及必要打包通过，全新四阶段准备通过。S21 保留首项失败且部署 UNKNOWN／后七项未启动，S20 保留 PASS1／FAILED1／后六项未启动。S17 之后的 S 编号为独立 STATIC 版本，AI 辅助验收的 R17 尚未启动。用户要求的顺序为：完成 R16 原八项，再运行独立 STATIC 验证；静态问题修复并验证正常后，才返回 AI 辅助测试。R16 原八项已封存，首过仍为 **0/8**；其 FAILED、UNKNOWN 和资格 false 不由本阶段改判。实时进展见 [CURRENT](../../../.ai-workspace/progress/CURRENT.md)。

## 已消费原次

| 独立版本 | 服务器准备 | STATIC 业务原次 | 结论边界 |
| --- | --- | --- | --- |
| ROOT16 `92f35176…` | 新 inventory、plan、apply、独立 after 均通过 | TaskBoard 失败；其余七项未启动 | `MIRROR_PRIVACY_CONSENT_REQUIRED`；不算静态通过 |
| S16 `24c0ffba…` | 全新四阶段均通过 | TaskBoard 失败；其余七项未启动 | `DEPLOYMENT_BUILD_FAILED`，Maven 分发固定源连接超时；不重放该原次 |
| S17 `8250f202…` | 新 inventory、plan 通过；apply 未完成；after 未启动 | 八项全部未启动 | apply 等待超时，清理实际结果为 UNKNOWN；四阶段未通过 |
| S18 `275e0650…` | 全新四阶段均通过，完整 after 与 apply.postInventory 相同 | TaskBoard 失败；其余七项未启动 | 业务 QA 通过，最终清理复查失败、CLEAN 缺失；不算完整静态通过 |
| S20 `39e67c94…` | 全新四阶段均通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard 完整原次 PASS；FileTransfer 原次 FAILED，其余六项未启动 | GO_MODULE 来源白名单拒绝，64KiB业务未到达；STATIC 1/8，AI R17 未启动 |
| S21 `29965d65…` | 全新四阶段通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard 原次 FAILED，部署 root 为 UNKNOWN；其余七项未启动 | 完整业务0/8，异常自有 TMP 已闭合；AI R17 未启动 |
| S22 `b7d00a7c…` | 全新四阶段均通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard、FileTransfer 完整原次 PASS；AssetLending FAILED，后五项未启动 | NuGet 仓库签名请求502；本轮封存2/8，AI R17 未启动 |
| S23 `f028e006…` | 全新四阶段均通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard、FileTransfer 完整原次 PASS；AssetLending FAILED，后五项未启动 | nupkg digest-mismatch→502；本轮封存2/8，AI R17 未启动 |
| S24 `933b81bc…` | 全新四阶段均通过，完整 after 与本轮 apply.postInventory 相同 | TaskBoard 原次 FAILED，START root UNKNOWN；后七项未启动 | 仅发布、业务与CLEAN未达；本轮封存0/8，AI R17 未启动 |

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

原 native 与外层 wait 均实际 exit1，无 bookkeeping 失败；后续精确观察确认四个已记录出生身份消失，内部 JVM 身份仍 UNKNOWN。异常自有 TMP 经 owner、绝对边界、祖先、整树 nonreparse 和锁预检后限定清理，原工具 `c13c40` 实际 exit0；仅删除两个自有叶目录及各自空任务根，清理进程自身出生身份也由 `a18660` 实际 exit0 确认消失。normal store 和证据保留，未执行 normal closeout 或远端复查，见[本地异常收尾父审](../../../.ai-workspace/evidence/post-r16-static-channel-execution-inputs-20261106-01/static-file-transfer-abnormal-tmp-closed.PARENT-REVIEW.json)。原公开日志的四个 typed 任务为3成功、1失败、0 UNKNOWN；owner 初始汇总字段误写为空 outcome/count4，逐条记录正确，父审独立计数并保留原错误记录。后六项和 AI 未启动，来源规则修复后须全新冻结及完整四阶段，不重放或拼接 S20。

## S21 有界分发跳转修复与本地验证

S21 本地提交 `29965d658e65eb6863bf651ee5db1fc24abe0631` 仅改六个 deployment 生产／配置文件及四个直接相关测试文件。目录源与冻结 Endpoint 增加默认空的 `redirectRules`，随选择、JSON 往返和公开 guidance 传递；旧快照缺省为空，自定义源携带非空规则在构造、反序列化和运行时均拒绝。仅 `official-go_module/GO_MODULE` 配置 `proxy.golang.org` 到 HTTPS／默认443的 `storage.googleapis.com/proxy-golang-org-prod/` 跳转，普通初始主机白名单仍只有 `proxy.golang.org`。

运行时逐跳校验精确主机和路径段边界，拒绝其他 bucket、相似前缀、点段与编码逃逸、额外目标的二跳、userinfo、fragment及非443端口。签名 query 原样传输但不记录，原凭据头移除后仍由既有 scoped adapter 控制。该候选依据 Go 维护者历史一手资料所述分发设计；bucket 属于上游实现细节，不能保证永久稳定，也不能据此确认 S20 实际被拒绝的目标。原 Go 校验链、direct／sumdb、回退、重试、超时、版本和业务断言均未改。

定点验证分轮保留：首轮 JUnit **9/0/0/0**；补充自定义冻结端点边界后，仅相关三方法 **3/0/0/0**；最后收紧 Python ASCII 主机格式匹配后，仅 runtime 方法 **1/0/0/0**，其独立 Python 合成场景 **8/8**。最后一轮同时 package，原工具 `67d030` 实际 exit0、29.471秒，生成最终 deployment JAR；此前39.571秒的中间包不冒充最终包。没有全量测试或真实公共下载，合成结果不证明 Linux、TLS、原生 Go 构建或部署成功。见[完整分轮证据与精确 selectors](../../../.ai-workspace/evidence/static-go-module-redirect-fix-20261107-01/README.md)。

为新工作树原 STATIC classpath 补齐 Files 模块，父另执行离线 `-o -pl src/mod/files -am -DskipTests package`，原工具 `ae0ef9` 实际 exit0、BUILD SUCCESS 5.878秒，测试全部跳过，未复制旧 target。相关自有 wrapper／已记录测试 JVM 已窄查消失，两个编译任务 TMP 各经 owner、绝对边界、祖先及整树 nonreparse 检查清除；未采集的内部进程身份仍为 UNKNOWN，不冒充正式 live 四 actor 证明。见[Files 必要编译与收尾](../../../.ai-workspace/evidence/static-s21-integration-20261107-01/README.md)。源码及正式记录提交均仅在本地，后续真实执行另记如下。

## S21 全新准备通过，首项原次失败并封存

本轮重新冻结公开输入与原控制，完整执行新 inventory、plan、精确 apply 和独立 after；四个原 native／tool 均 exit0，各新 JUnit **1/0/0/0**，原最终 VERIFIED 标记齐全。新计划完整回执 SHA256 为 `9b66cc2b63290037e0a3f6c3a121105fcf4e7e3387faf209b6f3963865b6cdbd`，独立重序列化的 canonical plan 为 `56ae7c6792c20010b7e8dcab36078525373a419f720dd7deddaa362aa80e193a`，17280个UTF-8字节；完整目标字段均与本轮 inventory 一致，31个非核心构建包与148个必留包无交集。

apply 回执 SHA256 为 `bde5f1bdc9e150ec5f1e1c9ccea50aff00923070be1d4bd99b223687a45ce95f`，实际清理五个产品目录、三个服务、两个产品账号和31个包；服务文件删除计数原值为3。148个必留系统包完整记录未变，655个剩余包恰为原686减计划31，无升级或额外增删；八个固定产品目录均不存在，账号、组、subid、服务、服务文件及目标包清空，APT缓存为0。

独立 after 的完整 inventory 与本轮 apply.postInventory **全部59382个UTF-8字节相同**，SHA256 为 `82e0d077f2a00d57e542e7bce91bd50ab87665b358533e5e04de4f67f90e2afc`，无排除字段或数值转换。同旧摘要不表示复用旧原次；本轮各有新的任务、JUnit、native和实际工具终态。16个已记录原进程身份均已精确观察消失，自有TMP闭合，normal与证据保留，内部JVM身份仍UNKNOWN。见[本轮完整四阶段父审](../../../.ai-workspace/evidence/post-r16-static-go-redirect-execution-inputs-20261107-01/four-phase-preparation.PARENT-REVIEW.json)，SHA256 `22b0688c15a459b997002798a9e0e7309f13c5cc16b04951ce0db398e0fdb400`。

准备职责结束后，首个 TaskBoard 由独立职责仅执行一次原 STATIC selector。原工具 `616f02` 实际 exit1，新 JUnit **1/1/0/0**、30.797秒；部署 root `aee0522c-d740-49c4-adf8-0acc332cc656` 返回 `DEPLOYMENT_REFERENCE_UNKNOWN`，处于“归档已审阅冻结组件”阶段，outcome／endedAt为空，futureDoneObserved=false，Mod.close 因仍有资源被阻止。未到达 PUBLISHED、BUSINESS、PASS、CLEAN 或最终 modelCalls 断言，不从日志缺失推断远端无副作用。见[原次失败父审](../../../.ai-workspace/evidence/post-r16-static-go-redirect-execution-inputs-20261107-01/s21-task-board-failed.PARENT-REVIEW.json)。本轮完整业务0/8，后七项及AI未启动，不重放此原次。

四个已记录原出生身份均精确观察为 PID_ABSENT。异常自有 TMP 另经 owner、绝对边界、祖先／整树 nonreparse、硬链接及独占删除锁预检后，仅清理两个已审叶目录及空任务根；原工具 `977bec` 实际 exit0，清理进程自身由 `28bf7f` 实际 exit0 确认消失。normal store 与证据保留，内部 JVM 身份及远端副作用仍 UNKNOWN，未执行 normal closeout，见[异常本地收尾父审](../../../.ai-workspace/evidence/post-r16-static-go-redirect-execution-inputs-20261107-01/static-task-board-abnormal-tmp-closed.PARENT-REVIEW.json)。准备中的路径拼接检查错误、纯存证的 MSIX 启动器错误及收尾元数据脚本解析错误均保留，不算服务器原次重试。

## S22 helper 固定摘要同步与定点验证

S21 的 runtime 变化会更新生成 helper 中两处嵌入摘要，而 helper 生产者及四处独立消费者仍固定旧身份。新工作树先用原 `assemblesTheAllowlistedProtocolByteForByte` 复现 `IllegalStateException`，新 JUnit **1/0/1/0**。独立比较确认两个 helper 均为570631字节，仅两处 runtime SHA 不同；替换这两处后全字节一致。原 S21 具体异常栈未导出，因此该确定的本地缺陷不改判其原 UNKNOWN，也不宣称已经证明原次唯一根因。

本地提交 `b7d00a7cd22eb00bcf2c8988b412eedcb301c4d4` 只改五个生产文件、四个直接测试文件中的12处固定摘要字面量，将 helper 身份同步为 `734ad85f336c41aeaab6034b5bd15a03642434e4ff4458b3b6ba06faed530bac`；各消费者仍独立校验固定身份，审批、协议、拒绝断言、UNKNOWN 和预算均保持。首次同步后的24例中8例通过、16例因遗漏的独立合同摘要报错；补齐后仅重跑失败审批组及三个直接合同方法，共 **20/0/0/0**，原工具 `9ee01d` 实际 exit0，未重复审批组以外已通过的7例。必要离线 deployment／Files 打包使用 `-DskipTests`，原工具 `c6b14e` 实际 exit0。全部失败轮保留，见[修复与分轮验证交接](../../../.ai-workspace/evidence/static-helper-runtime-pin-fix-20261107-01/HANDOFF.md)。这属于定点验证，不是全量测试或真实服务器验收；S22 须新冻结并完成全新四阶段后，才从 TaskBoard 开始固定八项。

## S22 全新四阶段通过，前两项完整 PASS

本轮新 inventory、plan、精确 apply 和独立 after 各仅执行一个原次，原 native／Python／controller／tool 均真实退出0，新 JUnit 各 **1/0/0/0**，原最终 VERIFIED 齐全。新计划完整回执 SHA256 为 `574c7b5d0fe23b5b8a70ec104ae2ba2f315b0cc3bcf8cb7d5b789f8e444cab9b`；父独立递归排序并保留数组顺序和数值原词元，canonical plan 为 `7af47508b95e036af11160f01bed2d83a6f2d464a66c772cb9c2f76b74780de0`、11748字节。12个共同字段与本次新盘点原值全部一致，完整655条包清单摘要匹配，清包列表为空。

新 apply 回执 SHA256 为 `55f2af5cd93a5454349e23286e59182e971e76947eb139c6a8a357f17e773526`，绑定上述精确新计划；原 summary 的五项产品清理计数均为0，APT缓存清理为true。随后另起独立 after，完整 inventory 与本轮 apply.postInventory **全部59382个UTF-8字节相同**，SHA256 为 `82e0d077f2a00d57e542e7bce91bd50ab87665b358533e5e04de4f67f90e2afc`，无排除字段或数字转换。148个必留核心包和全部655条包记录保持，八固定目录均不存在，产品账号、组、subid、服务、服务文件、目标包为空，APT缓存为0。相同旧摘要不代替本轮独立执行，各阶段均有自己的新任务、JUnit、原工具终态及收尾证据。

16个已记录原进程身份均精确观察消失，四组自有TMP闭合，normal与证据保留，内部JVM身份仍UNKNOWN。见[本轮完整四阶段父审](../../../.ai-workspace/evidence/post-r16-static-helper-pin-execution-inputs-20261107-01/four-phase-preparation.PARENT-REVIEW.json)，SHA256 `697f10164309e2ea984b90013e3a5c550bc8c149dc0676df6173a36dabc6611e`。输入静态校验、父审字节比较和审查文件名读取中的本地错误均保留；修正未改变冻结输入或重试服务器原次。

准备职责结束后，独立职责完成首TaskBoard唯一原次，原session13960由 `2faf37` 实际 exit0 结束，原native／Python／controller等待均为0，新JUnit **1/0/0/0**、1212.347秒。同应用 `wtl-static-task-board-j9qth82k0/static-live` 的PUBLISHED、真实HTTP／跨语言流程／重启／持久化BUSINESS、PASS modelCalls=0、CLEAN stopped/disabled原标记齐全。`c0e8e4` 实际exit0完成四个精确出生身份和自有TMP闭合，normal与证据保留；内部JVM身份仍UNKNOWN，原冷helper探针失败、dpkg警告和optional-empty记录均保留，不声称观测异常全零。见[本项完整父审](../../../.ai-workspace/evidence/post-r16-static-helper-pin-execution-inputs-20261107-01/static-task-board-first-01.PARENT-RESULT-REVIEW.json)，SHA256 `4b75fe268a9cce2ff7a1ee226e8b1f8aabfaa07008eeb77f2b6ec590375efd0d`。

FileTransfer另由独立职责完成唯一原次，原session91070由 `bbab84` 实际exit0结束，新JUnit **1/0/0/0**、547.462秒。Go后端原构建退出0、官方模块源阶段complete；随后原HTTP65536随机字节上传、逐字节下载相等、重启后再次下载相等均通过，BUSINESS、PASS modelCalls=0及同应用 `wtl-static-file-transfer-jvuychaj8/static-live` CLEAN stopped/disabled齐全。`7f5112` 实际exit0完成四精确身份及TMP闭合，normal与原证据保留。内部JVM身份、原列表state／autostart元数据UNKNOWN及警告保留，不混成typed-root失败，也不声称观测异常全零。见[本项完整父审](../../../.ai-workspace/evidence/post-r16-static-helper-pin-execution-inputs-20261107-01/static-file-transfer-first-01.PARENT-RESULT-REVIEW.json)，SHA256 `1781555603ff98ce5d4c09d4f0a3b36d5216dd09f03358dab64b18c3b4c9c492`。本轮通过不改判S20失败或反推当时未观察到的重定向目标。

AssetLending由独立新职责执行唯一原次，原session22785由 `7dca50` 实际exit1结束，新JUnit **1/1/0/0**、187.543秒。部署root `e675052a-b1fd-4095-a326-c7742f13f2d7` 明确为FAILED，code `IO_FAILURE`／event `DEPLOYMENT_BUILD_FAILED`；.NET SDK10.0.300的DOTNET_LOCKED restore报告NU1301，仓库签名请求 `https://api.nuget.org/v3-index/repository-signatures/5.0.0/index.json` 返回502 `selected repository transport failed`，原镜像日志先complete、后locked-route-unavailable。未到PUBLISHED、BUSINESS、PASS/modelCalls0或同应用CLEAN；空应用列表不证明远端无副作用。见[本项失败父审](../../../.ai-workspace/evidence/post-r16-static-helper-pin-execution-inputs-20261107-01/s22-asset-lending-failed.PARENT-REVIEW.json)，SHA256 `f75b68a843bff7b5269fe19ef55d86d5700b9ef986335d146e118b63364b0a77`。

首次精确观察原工具 `51a766` 实际exit0，四个已记录原出生身份均PID_ABSENT。另经父审的限定janitor核验owner、绝对边界、祖先／整树nonreparse、硬链接及独占删除锁后，仅删除两处自有TMP叶目录和各自空任务根；原工具 `7e300b` 实际exit0，清理进程自身由 `0797e5` 实际exit0确认消失。见[异常本地收尾父审](../../../.ai-workspace/evidence/post-r16-static-helper-pin-execution-inputs-20261107-01/static-asset-lending-abnormal-tmp-closed.PARENT-REVIEW.json)，SHA256 `2dd35be851372bdc33fe0c5aa8831a93d35767e0f345e68c701e3f09541831e2`。normal及原证据保留，未执行normal closeout或远端复查，内部JVM身份及远端副作用仍UNKNOWN。本轮封存PASS2／FAILED1／UNLAUNCHED5，不重试或拼接原次。

## S23 NuGet 签名路径修复及新冻结

对照S22 HEAD，NuGet的 `sourceOrigins` 和 `official-nuget.routes` 缺少 `/v3-index/repository-signatures/`，已有 `/v3/` 前缀不能匹配该路径。locked transport在映射失败后可以直接生成现场同文502，属于足以解释本次失败的确定目录缺口；没有读取私有冻结策略JSON或探测上游健康，不将源码结论冒充现场策略原字节核验。NuGet的签名资源从service-index中的独立HTTPS入口发现，不能假设位于索引目录内，见[官方仓库签名协议](https://learn.microsoft.com/en-us/nuget/api/repository-signatures-resource)。

新工作树 `s23f` 从 `b7d00a7c…` 创建，本地提交 `f028e0066fa9aa7d4d711309f4aeae7ecd68e6b5`，未推送。目录差异仅补签名专用origin与官方identity route，保留allowedHosts、原NuGet配置、签名验证、单源选择和拒绝规则；不扩到整个 `/v3-index/` 或主机根。父独立字节比较确认helper仅增加签名前缀56字节，570631→570687字节，runtime摘要不变，新helper SHA256为 `c5641fd6b5ac721ae534a1e2877ed9a03d6f7602ee7e6e2a746e4ff700603d58`；九处消费者源码只同步12处固定摘要字面量，各独立身份校验保留。

新回归通过真实catalog／selector／policy往返及loopback合成服务，验证签名索引与证书字节、精确路由和拒绝边界、自定义源失败不回落官方源。最终九份JUnit合计 **28/0/0/0**，原工具 `9b1145` 实际exit0；早期三个失败轮及错误断言修正均分别保留，不拼成首轮全绿。原CONNECT reset随fixture路径缩短后消失，但缺少原OpenSSL现场，未确证长路径因果。离线deployment／Files必要打包使用 `-DskipTests`，原工具 `586505` 实际exit0；16个已记录actor精确核退，自有TMP已清，未知内部进程身份仍UNKNOWN。见[修复及分轮验证](../../../.ai-workspace/evidence/static-nuget-signature-route-fix-20261107-01/README.md)。这是定点与合成验证，不是实际NuGet SDK签名验证、全量测试或服务器验收。

新候选的7项launcher、19处POM路径和五control机械差异经父独立逆字节核对复现S22原件，五control AST无错误；54classpath、9metadata、6JSON路径、13原pins、STATIC两pin、17类族49classes及两JAR51精确条目完成有限公开核验，非完整compiled／私有冻结。见[配置父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/configuration-and-public-inputs.PARENT-REVIEW.json)。后续各阶段分别放行，没有复用S22准备或两项PASS。

## S23 全新四阶段及前两个业务通过

新inventory、plan、精确apply和独立after的原工具分别为 `8744ae`、`037faa`、`3d2c58`、`c3abc3`，各实际exit0、新JUnit **1/0/0/0**、原VERIFIED齐全。计划父审独立递归ordinal canonical为18195字节，SHA256 `54cd0c78525ee62bcb061fc3d430a704c8d1738a160ee69665ca84f678736511`；12个共同字段与本轮inventory全部raw相同，686包完整摘要匹配，31个purge精确为已识别的非必需构建依赖，与148个必留包无交集。按用户持续授权，仅原产品API处理健康R15凭据与新加密正常库，不脚本观察私有实体。

新apply回执SHA256为 `201b640b4b3c7ecdf398b4aff0d5a87f4817b39c450eaddd8c5970ed39b48d2b`，原summary保留purge31、accounts4、roots5、unitFiles5、stopped5、APT清理true。剩余655包逐raw恰为本轮盘点减31包，core完整不变；八root均absent，产品账户、组、subIds、units、unitFiles、states及targets为空，APT0。独立新after回执SHA256为 `043786ae7c28baaf3e7d15829b3e8d354fea2f717921d2e5f40b831e619506be`，与本轮apply.postInventory **全部59382个UTF-8字节、14字段raw相同**，没有排除或数值转换；相同旧摘要不表示复用旧原次。见[完整独立比较](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/host-after-static-01.PARENT-COMPLETE-COMPARISON.json)。

四组原closeout分别 `b623ab`、`1523fa`、`26d46e`、`f0ef80`，均实际exit0，16个已记录原出生身份消失，自有TMP闭合，normal和原证据保留；未采集的内部JVM身份仍UNKNOWN。见[本轮四阶段父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/four-phase-preparation.PARENT-REVIEW.json)，SHA256 `29639831e9aa10a7f5633a52461fcf4ffabf5f3e1ef63713b37c7d1827c32b59`。准备职责结束后，另由独立职责完成首个TaskBoard原次如下。

TaskBoard原session58129由 `a9d294` 实际exit0结束，新JUnit **1/0/0/0**、1082.924秒。同应用 `wtl-static-task-board-lugvtm1k0/static-live` 的PUBLISHED、真实HTTP／跨语言流程／重启／持久化BUSINESS、PASS modelCalls=0和finally CLEAN stopped/disabled均齐全。`d7bf9f` 实际exit0完成四原出生身份和自有TMP闭合，normal和证据保留，内部JVM身份仍UNKNOWN；冷helper exit64、Maven／dpkg warnings、Optional.empty及列表元数据UNKNOWN仍保留，不声称观测异常全零。见[本项完整父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/static-task-board-first-01.PARENT-RESULT-REVIEW.json)，SHA256 `f5f32e9b8137edb6dbdf65194b7440f577d734d535aed34f26c183302cc1faaa`。本项通过后仅放行FileTransfer原HTTP65536字节往返／重启后再下载原次，其结果如下；不拼S22旧PASS。

FileTransfer原session89390由 `b0471a` 实际exit0结束，新JUnit **1/0/0/0**、532.831秒。原HTTP65536随机字节上传、逐字节下载相等、重启后再下载相等通过；同应用 `wtl-static-file-transfer-mb0kjbrg4/static-live` 的PUBLISHED、BUSINESS、PASS modelCalls=0和finally CLEAN stopped/disabled齐全。`9a3932` 实际exit0完成四精确出生身份及自有TMP闭合，normal和原证据保留，内部JVM身份仍UNKNOWN；Maven／dpkg warnings及原列表state／autostart UNKNOWN保留，不声称观测异常全零。见[本项完整父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/static-file-transfer-first-01.PARENT-RESULT-REVIEW.json)，SHA256 `cd5ebe4b5e4477e3e90ceb69ae104c6cc20fdc0d7508f4c02c3d2eff72a85d7b`。本项通过后另由独立职责接手新AssetLending唯一原次，结果如下；S22原失败不改判。

## S23 AssetLending 原次失败与 S24 准备

AssetLending原session30085由 `c579bd` 实际exit1结束，新JUnit **1/1/0/0**、203.893秒；native／Python／controller原等待均完成并返回1，bookkeeping无异常。部署root `d5cfceb7-9eaa-4865-896b-85a56c94cee2` 明确FAILED，IO_FAILURE／DEPLOYMENT_BUILD_FAILED；DOTNET_LOCKED10.0.300真实restore下载官方 `Microsoft.Data.Sqlite.10.0.8.nupkg` 时出现attempt1 `digest-mismatch`，随后attempt0 `locked-route-unavailable` 和502。未到PUBLISHED／BUSINESS／PASS model0／同应用CLEAN；远端副作用与原native等待之外future完成状态均UNKNOWN_NOT_RECHECKED，不重开失败库或补查服务器。见[本次失败父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/s23-asset-lending-failed.PARENT-REVIEW.json)，SHA256 `d75ad50f31f420c614e1530af00298b07d4b8add5eb8260e493f3bd6eb8e7f4f`。本轮封存PASS2／FAILED1／UNLAUNCHED5，不重试或拼接。

原工具 `515e36` 实际exit0，仅按四个已记录PID／原出生身份各一次观察，均PID_ABSENT；内部JVM身份仍UNKNOWN。另行父审的限定janitor原工具 `bdccb7` 实际exit0，四fresh精确原身份均消失；owner、绝对边界、祖先／整树nonreparse、硬链接和独占DELETE锁检查通过，仅清两自有TMP叶目录及空任务根。清理进程696的原出生身份在调用前记录，由同一原工具实际exit0证明退出，未新增self PID观察；normal与原证据保留，没有normal closeout或服务器复查。见[本地异常收尾父审](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/static-asset-lending-abnormal-tmp-closed.PARENT-REVIEW.json)，SHA256 `282fca26de32f1a53afa02b43fcd6850c317aec70caaf0634b5c440d381edc2b`。

源码只读调查确认另一独立缺陷：锁文件Base64 `contentHash` 被正确解码后，当作整个HTTP响应nupkg的SHA-512比较；但官方NuGet对已签名包排除签名归档记录并修正ZIP目录后计算内容哈希，不能用原整文件哈希或重新ZIP代替，见[官方实现](https://github.com/NuGet/NuGet.Client/blob/dev/src/NuGet.Core/NuGet.Packaging/PackageArchiveReader.cs#L453)。公开fixture含Sqlite10.0.8锁，实际运行副本、包字节及签名状态未读取，因此不能把源码缺陷冒充本次现场完整因果核验。S24隔离工作树 `s24f` 已本地提交 `933b81bcd71877bffef5c0201906db78f4dc3aac`，13文件含6生产／7测试；严格内容哈希validator保留原包字节，NuGet独立锁摘要登记覆盖冲突、缺锁继承及交错绑定后的冷／缓存校验。原生锁／签名策略、来源与预算不变。最终明确筛选的9份JUnit合计 **28/0/0/0**，内部Python **9 PASS**；38份公开归档与本机官方NuGet内容哈希逐名一致，真实公开包的原生CMS和完整性检查通过，签名篡改副本按预期失败。证书链、时间戳信任、撤销及服务器现场均未测；严格支持的signed classic ZIP子集和ZipFile元数据O(CD+entries)限制保留。必要离线deployment＋files打包通过、tests skipped，新资源source／target／精确JAR entry相同；63已记录actor均消失、自有TMP闭合，内部JVM身份仍UNKNOWN。见[交付证据](../../../.ai-workspace/evidence/static-nuget-content-hash-fix-20261107-01/README.md)及[父关键证据审查](../../../.ai-workspace/evidence/post-r16-static-nuget-signature-execution-inputs-20261107-01/s24-offline-repair-key-evidence.PARENT-REVIEW.json)。各原失败轮保留，未推送；新冻结输入和五份机械控制已独立审查，新四阶段与业务状态另记下节，AI仍未启动。

## S24 全新四阶段通过与首项业务失败

新 inventory、plan、精确 apply、独立 after 各一个原次均实际 native／tool exit0、新 JUnit **1/0/0/0**，全部本地收尾完成。原终态分别为fcfde9、bc9bac、b9af57、786772，原closeout分别为274e20、8d492b、1d335d、2e5464；16个已记录出生身份均消失，自有TMP闭合，normal／原证据保留，内部JVM身份仍UNKNOWN。见[本轮四阶段父审](../../../.ai-workspace/evidence/post-r16-static-nuget-content-hash-execution-inputs-20261107-01/four-phase-preparation.PARENT-REVIEW.json)，SHA256 `064fdebfa59f4b409cbf5db31e498626db37a5587f1dd4cfa92bc369cab54b84`。plan和apply仅原VERIFIED，没有platform marker，按原fixture语义保留。

新完整plan回执SHA256 `7b25bf1f98176cf61c586eb5e48c6011c337b215f1f56bc21f3f317286829955`，独立canonical为 `6b17f5e1243b8447dbe6cf065591e09383112c24d3517ef36f940c92b5ff5fbe`。12共同字段与本新inventory全raw相同，31个非essential移除目标与148个必留包无交集。新apply回执为 `8fefc12d62f63f38f18137eda8e2ff00824eec32ab28564f52c292bcad5d5da6`，原summary保持purge31／account4／root5／unitFiles5／stopped5；服务文件计数5不改成计划6。完整655个剩余包记录恰为本轮686减31，无额外升级或移除，148core原记录不变，8产品根目录不存在，产品账号／组／服务等目标为空，APT缓存为0。

独立新after回执为 `043786ae7c28baaf3e7d15829b3e8d354fea2f717921d2e5f40b831e619506be`，与本轮apply.postInventory全部 **59382个UTF-8字节／14字段完全相同**，无排除或数值转换；相同旧SHA不代表复用旧次。见[完整比较](../../../.ai-workspace/evidence/post-r16-static-nuget-content-hash-execution-inputs-20261107-01/host-after-static-01.PARENT-COMPLETE-COMPARISON.json)。正常冻结closeout实际具有owner、绝对边界、祖先及整树nonreparse检查；另对各outer精确owner.json补做单硬链接、短暂独占读锁前置，锁在cleanup前释放，不宣称清理期间全程持锁。

准备职责结束后，首项TaskBoard另由独立职责执行，原session57233由 `dbf8fb` 实际exit1结束，新JUnit **1/1/0/0**、955.301秒。同应用 `wtl-static-task-board-t6s0g9dok/static-live` 仅有PUBLISHED；业务 `Application START` 任务 `8c13922f-ad3e-46e2-b25c-c74e3c8cd16c` 为UNKNOWN、outcome／endedAt空、futureDoneObserved=false，随后Mod cleanup被该任务阻止。BUSINESS、modelCalls=0终末断言及finally CLEAN均未达，不能将发布算完整通过。见[本轮失败父审](../../../.ai-workspace/evidence/post-r16-static-nuget-content-hash-execution-inputs-20261107-01/s24-task-board-failed.PARENT-REVIEW.json)，SHA256 `8cd9dfd578def77fbcda69d0d83a1381bab72ea2aebd202d5c67fa5d2dfe5352`。本轮封存PASS0／FAILED1／UNLAUNCHED7，不重试或拼S23旧PASS。

原START任务中最终超时操作为 `deployment.publication.inspect.v1`；原SSH结果为SSH_COMMAND_TIMEOUT，通道已确认后，输入线程在写framing长度时等待远端窗口；elapsed20005毫秒，authorPayloadState=MAY_HAVE_BEEN_SENT。START效果与future保持UNKNOWN_NOT_RECHECKED，未补查远端或重开失败库。初始窗口0在[SSH窗口协议](https://www.rfc-editor.org/rfc/rfc4254.html#section-5.2)及[固定OpenSSH实现](https://github.com/openssh/openssh-portable/blob/V_9_9_P1/serverloop.c#L516-L545)中可以正常；这不证明本次服务器的实现或所处阶段。maxSize只记初始大小、callback数也不是包数，非原子采样不能证明从未WINDOW_ADJUST或未发部分header；目前未证明底层根因，不能套用S18未打开通道的诊断。已另开只读源码分析职责，没有因此放宽超时、重试或原断言。

四已记录本地出生身份由原工具748427实际exit0唯一窄查，均PID_ABSENT。另行审定的异常janitor原工具 `6d1d04` 实际exit0，仅删除两指定自有TMP叶及各自空任务根；候选内fresh四原身份均消失，owner／边界／整树nonreparse／单硬链接及短独占DELETE探针通过，探针释放后才删除。self26176调用前身份已记录，由同一原工具终态证明退出，未加self PID扫描。normal／原证据保留，没有normal closeout或远端复查，内部JVM身份仍UNKNOWN；见[本地异常收尾父审](../../../.ai-workspace/evidence/post-r16-static-nuget-content-hash-execution-inputs-20261107-01/static-task-board-abnormal-tmp-closed.PARENT-REVIEW.json)，SHA256 `3b547f430d76ec67a99e4b7feff3fc17727cc2aa6849000887b1b2c8e6d6fbad`。S25的本地实现和有界验证已完成如下，尚无新服务器验收结果，不称原根因已修复。本轮后七项与AI未启动。

## S25 观测补充与本地验证

隔离工作树 `s25f` 已本地提交 `f43f49041e4897a2d850d3d63826cbacbd812dcd`，工作区clean，未推送。11文件包括5个App Linux SSH生产文件及6个直接测试文件；STATIC仅扩两个既有日志出口白名单，业务、负载、预算、wantReply、发送次数、取消和UNKNOWN语义未改。exec固定请求只观察本channel写future和单调时间，peer acceptance始终UNKNOWN；同一现有socket的纯委托记录read尝试、返回及callback进展，不宣称内核已接受或SSH包数，Future/null-handler覆盖保持UNKNOWN；原worker快照增加WAITING_FOR_EXIT。跨层字段不是原子快照。

机制组原10/0/0/0通过。父审发现新fluent返回值可能使链式read绕过观测，先保存真实1/1/0/0反例，再最小修为四方法成功调用一次后返回装饰器；只新方法1/0/0/0复验，原异常对象和Future身份保留。直接回归首13项为12通过、1个旧OpenFuture替身缺协议方法失败，仅补替身方法后只失败方法1/0/0/0；日志投影首2项为1通过、1个新测试契约期望失败，纠正该期望后只失败方法1/0/0/0。各原失败、前置fixture失败、误拷旧XML的fresh=false和历史包均保留，不拼同轮全绿，未跑全量测试。

最终Deployment和必要Files离线 `-DskipTests package` 原工具分别8ec650、2edc50实际exit0，测试跳过。父独立核5个生产family共14个class与JAR精确条目SHA一致；Files三个metadata和JAR已补齐，AppLinux／Deployment原包SHA均未变化。自有TMP经owner／祖先整树nonreparse／单硬链接和短独占读检查闭合，已知原handle全终结；内部JVM PID仍UNKNOWN，未全局扫描。第一次辅助清理脚本布尔值错误及后续正确收尾原件分别保留。见[完整分轮交接](../../../.ai-workspace/evidence/static-ssh-exec-read-observation-fix-20261107-01/HANDOFF.md)及[父关键证据审查](../../../.ai-workspace/evidence/static-ssh-exec-read-observation-fix-20261107-01/s25-offline-observation-key-evidence.PARENT-REVIEW.json)，后者SHA256 `bf6cfc19b0b08976aa6cb8213c06825e513ba7730f63132ae0b8b96be2cba153`。

上述只证明本地观测实现和限定机制验证；S24底层超时原因仍未证。D25公开输入和控制已限定父审，非完整compiled或private freeze：54个classpath项、9个metadata、6个JSON、13原pins；19个生产类族共55个class加3资源，与58个JAR精确条目一致，6个直接测试类族87个class。Launcher 7处及POM 19处路径／摘要替换可逆恢复旧原字节；STATIC仅两个日志白名单增量可逆恢复旧源原字节，九固定pin文件及12字面值、helper/runtime未变。五controls逆原字节及AST0，checker两原身份和TMP已闭合；辅助统计／父字段及路径错误、日期尾零序列化差异均保原，不重判业务。

## S25 全新四阶段准备

本轮 inventory → plan → 精确 apply → 独立 after 各一个原次实际native/tool0、新JUnit均1/0/0/0，16已记录原出生身份均消失、自有TMP闭合，内部JVM仍UNKNOWN。完整计划12个共享字段与THIS库存GetRawText ordinal相同，31项nonessential清理包逐序匹配且与148个受保护包交集0，23项essential均保留；范围为2账号及对应组、3units／4unitFiles、8固定项目根目录（5存在）。计划canonical SHA为`adb46b50822b83e854e8b90a0b863fdbea996f6ab5d2736e27ebf76f5217a546`；按本轮整份receipt单独绑定并只apply一次。

原apply摘要为purge31／accounts2／roots5／unitFiles3／stoppedUnits3／APTclean=true，保留实际计数，不将3改成计划4。完整post为655包恰686减31，全部原记录保序无额外删除或升级；148core原raw相同，8roots全absent，项目资源为空、APT0。独立after与THISapply.post全部14字段／59382个UTF8字节逐byte相同，无排除或数值归一化，raw SHA`82e0d077f2a00d57e542e7bce91bd50ab87665b358533e5e04de4f67f90e2afc`。见[本轮四阶段父审](../../../.ai-workspace/evidence/post-r16-static-ssh-observation-execution-inputs-20261107-01/four-phase-preparation.PARENT-REVIEW.json)，SHA`8b10f28bd487099f89b37e9af0b428861b45e4d1cd39d58da853d271d7bd5dc5`；相同baseline内容摘要不代表借用旧轮准备PASS。

现仅首项TaskBoard获单项原次业务放行，由新独立职责执行，尚无本轮完整业务PASS；后七项及AI未放行。须继续固定八项顺序，不拼旧轮PASS，原失败／UNKNOWN／false eligibility均保留，AI R17未启动。

## 后续验收条件

每个新版本或失败后的新轮均须另行冻结，完整执行 inventory → plan → 父审精确计划 → apply → 独立 after；独立 after 须与 apply 的完整 postInventory 对照，不得拼用旧计划、准备 PASS 或业务部分结果。S20 准备记录保留，业务失败后已停轮。后续新冻结仍按 TaskBoard、FileTransfer、AssetLending、CsvInspector、SurveyScoring、LogAnalyzer、DirectoryDiff、BinaryInspector 固定顺序逐项放行，每项均需原业务断言、modelCalls=0、同应用 CLEAN、JUnit 与真实 native/tool 闭合，失败或不确定不放后项。

STATIC FileTransfer 使用原 HTTP 文件应用的 64 KiB 负载，CLI 使用原静态 health probe；不以 ASSISTED 专用检查器替代。STATIC 不运行 AI guard 或私密 key hash，不修改已批准的 AI 15 秒／MAX25／0.2 秒观测规则。八项静态正常后才回到 AI 辅助测试；MOD 仍通过主 APP 四用途标准 API 通信，厂商适配由主 APP 负责。
