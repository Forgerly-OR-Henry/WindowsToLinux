# 完整 AGENT 安全诊断 scope（107）

完整 AGENT 的主模型监控现在保留原安全容量拒绝和 `unable` 诊断。105真实日志只保留容量 DATA 的512字符前缀和 unable 参数长度；确定缺口位于 `DeploymentStaticLiveAcceptanceTest.monitored(...).ai()` 的两个 `mainOnlyDiagnostic` gate。本次各补 `agentMode && purpose == LANGUAGE`，原mainOnly条件保留；不将扩展scope应用于APPROVAL、ASSISTED或STATIC。不改变主/审批工具选择、授权、配额算法、通用floor反馈或模型步骤。

原 `diagnosticJson`、`liveToolDiagnosticProjection` 及其校验helper全文不变，SHA256 `8eedee2b061a0cf9152c9dc7228789ae0ce39d4c02d81f6bf0b75abc2372b79f`；原输入桥、secret mask亦不变。容量仍要求原同callId的plan、error结果、task绑定、严格JSON与字段白名单；unable仍仅处理该工具，并保留reason SHA/长度、敏感值脱敏、危险正文REDACTED。其它容量工具缺plan绑定时保留原UNKNOWN元数据，不能输出capacity/reason；没有新增matched-plan gate改变原语义。

新增唯一非参数 selector `DeploymentStaticLiveAcceptanceTest#fullAgentMonitorRetainsSafeCapacityAndUnableDiagnosticsWithoutWideningScope`，字面 `managed.runtime.stopDiagnosticsLocal=true` 与该精确selector共同启用。既有isolatedStopDiagnostics仅增加该精确方法，不支持类级、通配或组合selector；旧94/91/104未重跑。

该方法使用纯内存Fake ModContext（仅ai）、TaskContext（仅id）和AiService（仅call），直接调用真实 `monitored(...).ai().call`。request含原plan call和完整拒绝result，response含unable native call；fixture断言完整messages/tools/task转发、requiredToolName不变和原response原样返回。捕获实际wrapper输出后总是恢复stdout，不直接调用静态投影方法来代替dispatch。没有执行native tool、Runtime、数据库、DPAPI、provider或SSH，也没有新增依赖。

| 轮次 | 唯一方法原结果 | 实际证据 |
| --- | --- | --- |
| baseline01 | 1 failure / 0 errors / 0 skipped；class0.129s、case0.114s | STATIC/ASSISTED/APPROVAL范围检查通过；完整AGENT实际dispatch预期两投影、实际零，确证旧scope遗漏 |
| corrected01 | 1 failure / 0 errors / 0 skipped；class0.251s、case0.237s | 只修两gate后已输出正确两投影；fixture的LongNode与解析IntNode比较失败，完整JSON文本一致；失败原证保留 |
| corrected02 | 1 pass / 0 failures / 0 errors / 0 skipped；class0.183s、case0.170s | 仅将fixture断言改为完整JSON文本比较；七场景全部通过 |
| format-01 | single-owner IDEA write/check exit0，freeze0 | 一轮通过，格式后源码字节仍等corrected02已测源码 |
| package-01 | deployment reactor明确skipTests，exit0，freeze0 | 未附带业务测试 |

最终七场景：STATIC禁止AI且delegate零调用；ASSISTED与APPROVAL无安全停止投影；完整AGENT/LANGUAGE完整capacity及安全reason均OBSERVED，组件声明超512字符仍可核；其它工具仅原UNKNOWN无正文；非法白名单/参数原UNKNOWN无正文；危险reason原REDACTED无正文。原plan arguments、credential-shaped reason及非法/危险脚本canary均未出现在当轮日志。真实 wrapper 的call/task/SHA绑定、安全字段及转发行为均由新方法断言。

所有测试显式 `-pl src/mod/deployment -am -Dtest=DeploymentStaticLiveAcceptanceTest#fullAgentMonitorRetainsSafeCapacityAndUnableDiagnosticsWithoutWideningScope -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged.runtime.stopDiagnosticsLocal=true`，仅同一失败方法重跑，未跑整类/模块/全量。各轮原日志/XML/TXT时间与SHA、源冻结均保留；两失败轮没有覆盖或改写为通过。

before3297精确等104after，冻结HEAD `77a2be13b36708a4ca1c58800fcc1fa127eed175`、index empty；after3297仅指定Test owner变化。旧JAR `4e28dd5418778c8ade7ad85dd142197783b1b7215c85533c3ffcccedf120ecac`，新JAR `97c69cfb003673b5653bc2db477980d445c8ec479563f1dc67327ad701c27bde`；1044生产class和29Agent资源逐项SHA不变。生产源、Skill、配置及105/历史失败UNKNOWN证据未改动。

最终非管理员普通用户CIM正枚举360进程并核自身，30组已记录本次PID/创建ticks全部退出，任务命令残留0。baseline方法执行过快，仅记录launcher、外部快照为空；corrected两轮通过runner自身CIM线程采样保存实际Maven/Surefire后代，采样线程正常取消/结束/移除；package实际后代亦已记录。没有停止既有或其它任务进程。

本次只验证本地内存wrapper诊断闭环，没有付费fullAPP重跑或DB调查。105原unable正文及未完整保留的实际组件声明仍UNKNOWN，不能由新fixture倒推；102原PASS和105原FAIL均保留。

证据：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-full-diagnostic-scope-107`；正式增量 `patches/AGENT-FULL-DIAGNOSTIC-SCOPE-107.patch`（本次Test+本文），单Test增量 `107-test-increment.patch`。Git由父任务精确隔离本地提交，不推送。
