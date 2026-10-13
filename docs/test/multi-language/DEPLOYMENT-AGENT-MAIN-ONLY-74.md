# task-board 纯主 Agent 真实验收 74：原任务 UNKNOWN

唯一真实轮 `task-board-main-only-74-01` 自然结束失败，产品保留原 UNKNOWN 任务，未完成真实部署验收。2026-10-14 03:30:51.5296097 至 03:50:19.9458050（UTC+8）通过产品入口 `DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic` 运行；Maven 退出 1，原 XML **1 FAIL / 0 ERROR / 0 SKIP**、1160.95 秒。XML 的断言消息没有明确 DEPLOYMENT 故障码，不能推定为 70 的同一健康失败。

运行基于本地提交 `878ce6b865505a551c6e33e6b124b56c0e88492a`、JAR `329ebd36c038ae170b3200076c62c035f018c148c2c207734d221e6998b53e79`。3296 项源码在开始和结束均等于 73 封口原像，源码与 Skill 未中途修改。Root 全程监控，没有接手 SSH 或向模型提供部署步骤。审批 Runtime 关闭后 attempts/providerCalls/tokens 均为 **0**；该诊断不代表独立审批验收通过。

模型自行越过容量及工具输入拒绝，日志出现 BUILD SUCCESS，并完成 SEALED、deliver、publish 后转入 STAGED 命令路线。实际 52 次 LANGUAGE 响应，plan 6、Skill 3、command 13、observation 10、seal 3、contract 10、publish 1、stage 1、continue_deployment 1、unable 3。调用自主继续工具不能代替后继成功；没有取得健康 VERIFIED 或完整业务通过，浏览器入口未出现。原生发布故障码、后续命令效果和正式 App 状态尚未完整取得，不把模型陈述当回执。

75 对原日志和定点源码的只读核查确认：continue 收到“原生 FAILED、完整回滚且无后续修改”的合并守卫拒绝，未进入 worker.prepare，故本次没有检查来源或预算资格。publish 后的实际暂存会按现有源码置修改标记，足以触发拒绝；不能由此确定其他守卫条件的值。两次 unable 命中 reason 的复合文本边界，参数总长度不足以判定是缺失、类型、空值、超长还是 NUL。日志只保留回执 body 的 ID/长度及 encoded request 字节数，没有原发布、命令回执或 nextStep 正文。尚未证实源码缺陷，下一步仅准备同一原任务的最小产品元数据投影。

Runtime 原 TaskSnapshot 为 `7740d741-d453-4f3f-8658-2b9b3c5221e8`、UNKNOWN，保留两个 FILE 引用 `051f06cd-4586-4efa-a005-19862c485d17` 和 `5b03a7de-cb96-4f2e-8d86-9fb18c31f30d`，以及“Business result requires explicit reconciliation”阻塞。没有将其强制改成 FAILED、重放冻结计划、恢复或删除原资源。实际数据库状态根为 `agent-main-only-diagnostic-live-task-board-a2z70k4ys`，请求 App 为 `wtl-agent-main-only-diagnostic-task-board-a2z71w3z0`，公开目标端口 47500。

77 在 2026-10-14 04:33:06.6289246 至 04:33:10.9043495（UTC+8）只读取得原任务元数据：平台与 Agent 均 UNKNOWN，semantics 4；唯一正式 Codec 验证的执行 `af17a9e5-86a2-424c-b2eb-9106f1b6d540` 为 EXECUTING/UNKNOWN，businessCommitted=false、cleanupComplete=false。原生事件 171 至 175 依次为发布执行、发布结果、回滚执行、回滚结果、GRAPH_ROLLBACK_RESULT=RESTORED；没有取得原生发布的故障码。资源共 2588 条，2583 条已释放，5 条未释放（FILE 两条，MOD、SERVER、SCHEMA 各一条）。29 条白名单事件和未释放列表均无截断；6 组 COMMAND 执行/结果配对只证明记录结构，不能证明全部后续命令效果结清。原生回滚成功不代表整个任务恢复完成，正式 App 状态和后续发布命令结果仍未知。

77 实际 DB 读取一次、退出 0，原数据库与访问文件的长度和双 SHA、三文件集合、源码/JAR 均未变，两个已知精确 PID/创建时间均退出。首次工具提交在创建进程前因自动审批超时未执行，同一获准调用重试后成功，两个工具记录都保留。14 份执行证据已封口，父核通过；未启动 Runtime、provider、SSH、恢复、删除或业务测试。此前 76 辅助读取的拒绝原证保留，不把辅助读取通过当作部署或真实恢复验收通过。

78 在 2026-10-14 04:49:25.6052736 至 04:49:29.6974909（UTC+8）只读核查同一原执行，正式 Codec 还原最终 ProblemCode.UNKNOWN / DEPLOYMENT_AGENT_MODEL_UNABLE。精确 `deployment-model-commands` 检查点存在且已释放，revision 12；平台作者封套与命令 Codec 均验证通过，4 个操作全部 ENDED，退出码依次 1、2、0、0，没有 INTENT 或 DISPATCHED。检查点结清不证明整个部署结清，也不证明全部后续命令成功；两个非零退出码的正文原因未读，首个原生发布故障仍未知。读取退出 0，原库双 SHA、文件集合及源码/JAR 前后未变，未执行 Runtime、模型、SSH、恢复或删除。

源码核对确认取证缺口：原生回滚仅把初始 Problem 返回给内存工具反馈，动作记录保存发布摘要和 RESTORED，未持久保存首个发布故障；最终执行结果可保存后来产生的 MODEL_UNABLE，不能还原原生首因。后续修复限定 Agent 发布适配器的有界结果记录，不改变模型工具选择、独立审批或继续部署准入。

52 次已知 provider usage：input 2757801、output 132917、合计 **2890718 Token**；cache hit 1587328、miss 1170473，三字段一致，输入缓存命中率 **57.5577%**。reasoning 105064 是 output 子集。两次能力探针的 usage 与 transport call count 均 UNKNOWN，另列，不按零计。本轮属于失败调试轮，不混入最终冻结八项目的综合用量。

正常用户 CIM 正控制和初始、活动两快照合并的 7 个精确 PID/创建时间均已退出，未人工终止。仅本轮 GUID 受保护配置副本在核定路径清理，原 clean 配置双 SHA 不变；调查数据库删除零。原日志 SHA-256 为 `33baf4e66c8ce3324822dc38d3fc6953dad77b420585ae93a2561accbb58d24f`，XML 为 `5f5ebc40cc1861f634ad92a219dbeff7fcd4b3cb5e8920fb649ea277fd61c9e4`，TXT 为 `4d7727cbd986f8274509926228f8ccca4a15a3e06b7efad391417d3c9540a726`。原报告、时间窗口、冻结原像和用量摘要已核，未新增 DB、provider、SSH 或业务测试。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-74-01/`、同名日志及 `main-only-full-business-live-74/`。下一步只读核查实际工具反馈和阻塞，再决定最小修复及唯一方法测试，不立即重复付费取证。原 58、74 UNKNOWN 和其他失败记录保留；task-board 完整业务、历史真实恢复、非默认目录真机重启、渐进审批及最终八项目验收均未完成。
