# R16 后独立 STATIC 验证记录

本记录截至 S18 本地编译完成、尚未启动新服务器准备时。用户要求的顺序为：完成 R16 原八项，再运行独立 STATIC 验证；静态问题修复并验证正常后，才返回 AI 辅助测试。R16 原八项已封存，首过仍为 **0/8**；其 FAILED、UNKNOWN 和资格 false 不由本阶段改判。实时进展见 [CURRENT](../../../.ai-workspace/progress/CURRENT.md)。

## 已消费原次

| 独立版本 | 服务器准备 | STATIC 业务原次 | 结论边界 |
| --- | --- | --- | --- |
| ROOT16 `92f35176…` | 新 inventory、plan、apply、独立 after 均通过 | TaskBoard 失败；其余七项未启动 | `MIRROR_PRIVACY_CONSENT_REQUIRED`；不算静态通过 |
| S16 `24c0ffba…` | 全新四阶段均通过 | TaskBoard 失败；其余七项未启动 | `DEPLOYMENT_BUILD_FAILED`，Maven 分发固定源连接超时；不重放该原次 |
| S17 `8250f202…` | 新 inventory、plan 通过；apply 未完成；after 未启动 | 八项全部未启动 | apply 等待超时，清理实际结果为 UNKNOWN；四阶段未通过 |

前两次 STATIC 的原件分别保留于 [ROOT16 执行证据](../../../.ai-workspace/evidence/post-r16-static-execution-owner-20261106-01/)和 [S16 执行证据](../../../.ai-workspace/evidence/post-r16-static-repair-execution-owner-20261106-01/)。S17 的原件见 [执行证据](../../../.ai-workspace/evidence/post-r16-static-cdn-execution-owner-20261106-01/)及 [父封存审查](../../../.ai-workspace/evidence/post-r16-static-cdn-execution-inputs-20261106-01/s17-preparation-incomplete.PARENT-REVIEW.json)，后者 SHA256 为 `fdd38e32bf67d1a4984cca4941814f883286004f015220621d291680a26ac356`。这些版本各自独立，不拼接准备阶段、通过项或计划。

## S17 准备异常及本地收尾

本轮 plan 完整原件 SHA256 为 `d2c053418482f68406b1238025828387dfa4c81c2b80db40e6266cb7e450f4be`，内层 canonical plan SHA256 为 `b00a833c4d68f99a4a1137b1a8b7d96308818f9d36360e0a87d2adf73f2b623a`，已独立复算并完整审阅。计划涉及五个现存产品目录、恢复服务文件和 31 个构建开发包；与 148 个必须保留的系统包无交集。该审查证明的是计划范围，不是清理实际完成。

apply 原工具 session `58023` 由 `02cb65` 实际 exit1、无 session 结束；原 Maven CMD 启动句柄也实际 wait/exit1。JUnit 为 **1/0/1/0**，1934 秒后在原 32 分钟 future 等待处出现 `TimeoutException`，并附同一任务 `abbfaa49-527c-4094-bf38-9e2458535c2e` 仍持有 Mod 资源、阻止 `runtime.close` 的异常。没有 apply 完成回执、远端阶段、命令退出或清理摘要。**清理验收为未完成／UNKNOWN；实际 TaskState 未直接观察，不能据此断言远端没有执行、已经完成或具体原因是 APT 锁、网络、审批。**

原四个已记录本地 actor 按 PID 与原出生身份核为消失；异常收尾没有调用成功 closeout。两处自有 TMP 经 owner、绝对边界、祖先及整树 nonreparse 检查后删除，原清理工具 `038a7c` 实际 exit0，helper 退出观察 `03aa41` 实际 exit0。原证据、源代码、compiled 与实际 normal store 保留，未脚本观察或打开失败库。内部 Maven JVM 身份仍 UNKNOWN；本地收尾不等于远端效果或业务 UNKNOWN 已解决。没有重试、独立 after 或业务启动。

## 有界修复与实际本地验证

- S16 仅修测试准备入口：通过原产品 API 保存固定来源、关闭自动选择和隐私同意，再捕获；AGENT 原提前跳过路径不变。
- S17 本地提交 `8250f2027d4bff16b6407b713bd873dc2bcb89c6` 增加可选 `managed.live.mavenDistributionBaseUrl`。本轮明确固定为 `https://dlcdn.apache.org/maven/`，只替换 `MAVEN_DISTRIBUTION`；产品语义为 `custom=true / official=false`，其余来源和默认 catalog、版本、SHA、超时、重试及 fallback 均未改。必要离线打包通过；两个明确筛选的参数化 preparation/capture 方法共 **4/0/0/0**。真实 SDK 独立网络诊断曾观察到该 CDN 的 TLS 验证和 HEAD 200，但不证明 GET、下载摘要或 STATIC 成功，也不证明旧连接超时的唯一原因。
- S18 本地提交 `275e065048552ce7cf2c559079ffe0efa3f3ee65` 只改一个 reset 验收测试文件，增加 callback、CONNECT、REVIEW、EXECUTE 阶段标记。EXECUTE 返回标记在回执写入前；仅输出安全状态，ApiException 的 code/event 用显式白名单或 OTHER，并原样重抛。原超时后只读取同一句柄的缓存 state/outcome 枚举。关闭 phase 只表示进入该阶段，不证明成功关闭。此改动用于下一原次定位，**尚未证明修复未知根因**。

S18 实际运行的验证只有离线 `-pl src/mod/deployment,src/mod/files -am -DskipTests package`，33.510 秒、原工具 `9092c5` 实际 exit0；所有测试跳过。原工具对象未另存，补件明确保留为忠实观测投影，不冒充 raw 返回。没有重跑已通过组、全量测试、真实 reset、SDK、凭据迁移、模型或 guard。指定 13 项公开 source/class/resource/config pins 已核对，构建 TMP 已清理；这不是全 compiled 或私密输入冻结资格。见 [S18 实现交接](../../../.ai-workspace/evidence/static-reset-phase-observation-fix-20261106-01/HANDOFF.md)。上述提交仅本地，未推送。

## 后续验收条件

S18 新轮仍须另行固定真实版本与输入，完整执行新的 inventory → plan → 父审精确计划 → apply → 独立 after；独立 after 须与 apply 的完整 postInventory 对照。不得消费 S17 计划或沿用其前两项 PASS。通过后才按 TaskBoard、FileTransfer、AssetLending、CsvInspector、SurveyScoring、LogAnalyzer、DirectoryDiff、BinaryInspector 固定顺序逐项放行 STATIC，每项均需原业务断言、modelCalls=0、同应用 CLEAN、JUnit 与真实 native/tool 闭合，失败或不确定不放后项。

STATIC FTP 使用原 64 KiB 负载，CLI 使用原静态 health probe；不以 ASSISTED 专用检查器替代。STATIC 不运行 AI guard 或私密 key hash，不修改已批准的 AI 15 秒／MAX25／0.2 秒观测规则。八项静态正常后才回到 AI 辅助测试；MOD 仍通过主 APP 四用途标准 API 通信，厂商适配由主 APP 负责。
