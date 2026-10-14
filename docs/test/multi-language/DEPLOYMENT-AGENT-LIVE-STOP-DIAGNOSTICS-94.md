# Main-only live 停止诊断（94）

原92两次容量拒绝的模型反馈含完整 DATA，但 live harness 只打印512字符摘要；native unable 只保留参数长度，无法判断模型声明的实际停止需求。此包仅修改 DeploymentStaticLiveAcceptanceTest，不修改产品工具、分配算法、权限、UNKNOWN、恢复、UI或Skill，也不重取原92题目或reason。

mainOnlyDiagnostic 的两个实际监控点共用纯投影 helper：plan 的原 tool-result 与同 request 中原 assistant call ID/name、实际 TaskContext 绑定；native unable 绑定实际 call/task，经正式 DeploymentAgentProtocol 解析 text reason。其它模式原输出保留，旧91输入桥与 staticLive 字面 true 语义保持。

容量输出 AI_LIVE_STOP_DIAGNOSTIC JSON：schema=1、status、task/call、kind=PLAN_CAPACITY、固定code及原反馈 inputSha256；capacity 仅白名单保留 task、六个 source/config/layout/capacity request/result/inputs SHA、archive/unpacked/available/reserve/required/aggregate bytes、AGENT cost/精确倍率2、组件id和原Request模式/字节、componentCount、新inputs尚未由被拒plan创建的原标记及truncated。严格拒绝重复键、尾部、额外字段、类型错误、错误绑定与倍率溢出。组件最多32项、可选组件ID最多64项，超过时明确truncated，保留身份/aggregate及原总数；完整JSON最多12000字符。仅诊断截断，不改产品组件准入/算法，不输出正文、脚本或猜测实际依赖大小。

unable 以 MODEL_DECLARED_REASON 标注实际模型自述，保留原正式解析reason的SHA/长度，使用91 safeInputData遮蔽英文/中文credential-shaped值；安全文本最多2000字符，标明redacted/truncated。命令或代码形态只输出REDACTED与固定UNSAFE_REASON；格式/绑定不合输出UNKNOWN及固定NO_TASK_BINDING、NO_CALL_BINDING、INVALID_CALL_BINDING或INVALID_DATA，不输出异常message。模型自述不作为外部容量事实、清理证明或下一步骤指令。所有新增投影仅诊断，不改变真实API结果或模型输入/工具选择。

唯一非参数 selector：DeploymentStaticLiveAcceptanceTest#safeCapacityRefusalAndUnableDiagnosticsRetainBindingsWithoutBodyLeakage。仅 managed.runtime.stopDiagnosticsLocal 字面true且 Surefire test 属性精确匹配时启用该本地纯方法；不 enable/open、不Runtime/DPAPI/H2/provider/SSH/原DB。方法同时核完整DATA、严格JSON、绑定、溢出、数组有界、安全文本及UNKNOWN/REDACTED边界，未跑旧91或任何其他方法/类/Codec。

原轮全部保留：baseline01 1FAIL/0ERROR/SKIP，0.065s，确切512摘要目标反例；corrected01 testCompile失败0case（JsonNode链式put），执行前inline Python引用SyntaxError亦保留且未写源；corrected02 1FAIL/0ERROR/SKIP，0.068s（LongNode/IntNode同值断言，非产品故障）；corrected03唯一最终1PASS/0FAIL/ERROR/SKIP，0.110s。每轮sourceFreeze0。single-owner IDEA write/check与skipTests package通过。

before3296==91afinal，after-final3296仅本Test增量。19相关生产class与15Agentresources的source/target/JAR字节与91保持；JAR容器SHA变化不代表生产实现变化。已知6个自有PID+creationTicks均退出，terminate0；未完整采样短子进程，不声称穷尽。原92及全部旧UNKNOWN/DB/资源保持，Git由父按增量本地隔离提交、不推送。

证据：.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-live-stop-diagnostics-94；正式UTF-8/LF增量：patches/AGENT-LIVE-STOP-DIAGNOSTICS-94.patch，仅本Test与本文。此本地诊断验证不证明真实API模型部署成功或原92恢复。
