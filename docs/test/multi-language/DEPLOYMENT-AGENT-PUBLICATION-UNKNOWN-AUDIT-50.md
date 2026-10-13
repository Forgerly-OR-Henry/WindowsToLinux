# 原 48 命令不确定性只读核对 50

对[48 的失败](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-48.md)完成精确只读核对。原 task `5e03e9a6-8441-4320-bd8f-33e64a048836`、operation `c3e94878-3892-4ef8-814c-70595e2023dc`、body SHA256 `f51f2522c929edbab43471114a13604ccbe0c95a303ba646423b353d674c0e9b` 未重放。三次原 MDB 读取均退出 0，前后 SHA256 均为 `8ce8a0d1c23353be9670c7283c9d5194917e1ba407bd159310f84fb684b51a04`；使用原锁、IFEXISTS、只读数据模式，不初始化真实 Runtime、调用 provider 或 SSH。

原命令的传输实际返回 SUCCEEDED、退出码 0、truncated=true。共用 `DeploymentModelCommandRunner.execute` 在检查 truncated 时直接抛 UNKNOWN，尚未执行下一步独立持久 receipt 查询；`DeploymentOriginalPublishCommit` 在 effectsStarted 后捕获异常并返回 COMMIT_UNCONFIRMED。原 checkpoint revision 2 的命令仍 DISPATCHED，原 draft execution 为 EXECUTING/UNKNOWN、businessCommitted=false、cleanup=false。这是已闭合的本地结果处理原因，不是“SSH 未发送”的推测。

该 runner 的直接调用者包括 AGENT 的 RemoteTools/PublishRunner，以及 ASSISTED 发布。后续最小修复应让已观察退出码的截断输出仍查询原 operation 的完整匹配回执，并在反馈中保留 partial 状态；截断正文不能作为发布、健康或成功评估证据。缺失、截断或身份不匹配的回执，以及未知退出状态仍须保留 UNKNOWN。STATIC 不经过该模型命令执行器，不应改变其编排或审批。

| 原 48 精确数据库事实 | 结果 |
| --- | --- |
| 指定 App 正式 managed_application 行 | 0 |
| 正式 release 行 | 0 |
| 原 task 完成业务 receipt | 0 |
| 预分配完成 archive | 0 |
| Mod deployment_log 中原 task usage 事件 | 41 |
| 规范 template 原身份校验 | PASS |

失败信息没有进入正式已完成 App 记录。平台 runtime_log 对应查询为 0 只表示存储位置不同，不能说用量丢失。正式图仍声明 18101，backend 配置仅 DATA_DIR/WEB_ORIGIN；18199 是先前候选测试端口。GRAPH_ROLLBACK_RESULT 为 RESTORED，但健康超时底层原因尚未核实，不能把这份只读记录当远端运行日志。

原脚本全文未留存，只有长度、body 摘要、reason、checkpoint 和输出。本包只核摘要身份一致，不能重算原正文 SHA，也不能由 reason 中“只读”推断没有修改效果。`deployment.model.command.v1` 是通用主机脚本语义，不带 READ_ONLY 效果分类。后续 stage/custom command 在 native rollback 之后，旧回滚不能替它们证明结清。

已有 product reconcile 会先查询原命令 receipt，不重放；随后 AgentRecovery 遇到 AI_PUBLICATION 存在、AI_PUBLICATION_FINISHED 缺失，仍返回 UNKNOWN/FINAL_ASSESSMENT_MISSING。没有重启已结束原模型会话的入口。发布子循环也不允许 replan/reseal，且同一 platform_task_id 只允许一个 execution；不能删旧记录或简单回退 Session 来绕过原冻结身份。

因此截断回执修复不等于原 48 自动恢复完成。还需产品内原范围观察与真正模型评估，全部效果和资源有确证后才可结清；证据不足继续保留 UNKNOWN。原 App、配置、持久数据、其他任务及调查证据均未操作。

本包 3282 个源码/Skill/样例 before/after/current 原字节一致，HEAD/index 不变，正常用户 CIM 正控制 1、自有读取器进程 0。父离线复核通过，父初版审计文件名假设错误的辅助失败保留；没有重复真实调用或业务测试。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-unknown-audit-50/`。后续限定修复与真实验收另记，DB 版本仍为 1。
