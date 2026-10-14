# Agent 精确原任务恢复入口（112）

仅调整 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`：显式 `managed.live.originalTaskOnly=true` 只允许AGENT状态根，并与 `originalPreparationOnly` 互斥。该模式不打印全部TaskSnapshot、不调用原全日志diagnostics、不恢复其它UNKNOWN、不显式global modRecovery.release、不正常重启或遍历全部App执行生命周期操作。原task reconcile仍要求ENDED，随后在try内部return，正常执行Runtime.close；失败/UNKNOWN及关闭阻塞不放宽。默认入口行为及原diagnostics helper原文保留。

精确模式在reconcile的finally中只尝试一次既有 `DeploymentDiagnostics(context).read(original)`，走原opaque deployment target、exact task和100条窗口。只输出eventCode=DEPLOYMENT_AI_USAGE且taskId精确匹配的Record；读取问题/异常仅输出固定UNKNOWN元数据，不输出其他事件或private problem正文，不覆盖原业务异常。成功、UNKNOWN和Exception路径均进入该finally。窗口明确BOUNDED_WINDOW_ONLY，截断/缺失不能推完整用量或零调用；后续运行须按JSON.startedAt时窗排除历史记录并去重invocation/attempt。本次仅静态/编译验证，未实际验证该读口可取得真实数据。

before3297精确等107after，HEAD `cbbda4e038089c7d33e9bbe48fcc5284be0b34ec`、index empty；after3297仅此Test owner变化，其他方法、生产源码、Skill和配置不变。未增加Boolean镜像测试，实际测试0，未重跑107/104/100或任何业务方法。四轮single-owner IDEA write/check均exit0；三轮deployment reactor明确skipTests package均exit0/source freeze0，最后有效版本为format-04/package-03。前轮因父审追加“跳过全日志诊断、补精确usage、移到finally覆盖失败轮”而产生新字节，均保留原metadata，不冒充最终版本验收。

最终JAR SHA `66038d993f6d02f1ee17addbde3b1dd36bff81c8c1363b93398ca4f08f85953b`；旧JAR `97c69cfb003673b5653bc2db477980d445c8ec479563f1dc67327ad701c27bde`。1044生产class及29Agent资源逐项SHA相同。一次证据脚本最初错误要求pre-format字节相等，因IDEA合法缩进四空格而拒绝；原记录保留，仅修证据断言，格式/编译无失败。

最后普通用户CIM非管理员正枚举370进程并核自身，12组本次已知PID/创建ticks均退出，任务命令残留0；未停止用户或其它任务进程。Runtime、DB连接、DPAPI、provider、SSH和实际恢复均0，未读原数据库内容、复制凭据或对原库回写旧hash。业务恢复、真实当前健康、原App和数据收尾均未验收；113未准备执行包或启动。

证据：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original-task-only-entry-112`；最终 `closure.json`、`source-jar-parity.json`、`package-03/result.json`、`own-process-final.json`。单Test增量 `112-test-increment.patch`；正式 `patches/AGENT-EXACT-ORIGINAL-TASK-ENTRY-112.patch` 含本次Test+本文。Git由父精确隔离本地提交，不推送；原迁移、历史FAILED/UNKNOWN及持久数据保留。
