# 原238纠错实机277：独立APP拒绝，命令未派发

在用户明确“现在继续”后，277只启动一次原File Transfer任务的产品恢复协调。独立APP对纠错动作判DENY，产品保留NOT_SENT，没有grant或纠错派发。原任务仍UNKNOWN，未完成File Transfer或CSV Inspector验收，不启动新FULL或CSV，不重放该动作。

## 实际执行与账本

恢复前核本地HEAD `bc2152df`、空index及12个任务源码未晚于最近定点测试启动时间；源码patch reverse-check通过，不重跑已通过测试或全源hash。授权配置文件只核`base_url`和`model`，仍为`https://api.deepseek.com/chat/completions`、`deepseek-flash`；未输出密钥。原恢复使用保存的独立审批配置，`configureAgentRecoveryModel=false`，未重新配置模型或能力probe。主会话模型身份不在核验范围。

首次源码时间比较由于PowerShell自动把JSON时间转为DateTime后再次Parse丢失UTC而误拒；当时没有产品动作。更正为保留字符串并用DateTimeOffset处理后，12路径均未变。实际记录在`resume-checkpoint.json`，不把前置误拒记为业务失败。

唯一产品session47602，`2026-10-17T05:21:04.1087493Z`至`05:23:12.0155426Z`，实际退出1。只选择`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，原TaskOnly、原ENDED断言及30分钟边界未改；实际1例1F/0E/0skip，因为原状态仍UNKNOWN。原始result、stdout/stderr及JUnit报告保存于`277/live-01`。

产品实际退出后，以已编译public只读工具查询一次原账本，`read-01`实际退出0；此前CIM核无原产品Java。H2 READONLY、existing-only bootstrap/DPAPI、公开完整前缀校验；未开Runtime、provider或SSH，credentialRowsRead=0。仅检查文件属性，锁内及关闭后不变，未重复hash整个数据库。

| 账本事实 | 实际结果 |
| --- | --- |
| 原Task | `072bc074-4bce-4c14-b425-f2ff965c7d40`，UNKNOWN，无outcome或endedAt |
| 原execution | `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，EXECUTING、businessCommitted=false、cleanupComplete=false、outcome=UNKNOWN |
| 正式App、component、release、operationReceipt | 均0 |
| 资源记录 | 17316行、5未释放；数量或释放标志本身不证明结清 |
| 原compensation | pending271，completed0、verified=false、lastActionEvent525 |
| 新完整整图 | invocation `5c1df83b-b660-4a92-b239-ca9158539705`，END522，2/2组件完整；不是目标ACK或结清 |
| 新纠错 | action `0fb18154-c5f7-495e-81a8-1de05d84a79e`，sourceVersion `3016bf88edad5927e3f2cadfc045be84bc777b467247f718a0572a62984e89fb` |
| 523 REQUEST | 完整公开prefix重建typed binding、program、request及9个targets成立 |
| 524 REVIEW | DENY，confirmation=false、rememberAuthorization=false |
| 525 NOT_SENT | 无GRANT、DISPATCH、RESULT或安全纠错回执 |

REQUEST detail SHA256 `970cddbe1c32f24816ca3b703dfdd37b554810c848aadf9849cd3d2ad5ce1766`；reason SHA256 `b7690ac056d964f51c965ec5fadd3441cae3b2821ef05821e4ddaffd7ec15262`；evidence SHA256 `f66c26bd958ba06f4e747a195678a559b38b0b0464260b9d95474d200c3de852`。这些是协议已有摘要，不是本轮全文件核验。

## 拒绝理由的证据缺口

本次能确认DENY，不能确认理由正文。`FixedCommandApproval`把真实`reviewReason`放入异常context；`correctCurrent`随后只保留中断事实并吞异常，Correction日志仅保理由SHA。审批完整tool arguments及讨论历史只在内存，usage日志只含数值和身份元数据，没有已证的正文持久化入口。

普通live监控确有有界脱敏`AI_LIVE_REVIEW_SUBMISSION.reasonExcerpt`，但原TaskOnly恢复直接调用Runtime协调，没有经过`monitored`；实际stdout该前缀为0条，不能套用普通FULL日志入口推测本次理由。SHA不可逆，也不为补全文再次调用APP。

此缺口需以现有有界脱敏诊断通道作最小本地修复，并单独定点验证。修复不能补造277历史理由，不能改写DENY、NOT_SENT、一次性键或旧sourceVersion，也不构成再次发送本次动作的授权。后续本地修复结果另记278。

## 纯恢复用量

简短离线解析`277/summarize-live277.py`实际退出0。按真实起止窗口、task、invocation、generation id＋attempt＋purpose选取并去重：99条usage诊断中，72条属于此前窗口，27条属于本轮，27个generation attempt无重复。generation SUCCEEDED表示模型返回成功，不能解释为27次审批通过或27个远端动作。

| 指标 | 本轮已知子集 |
| --- | ---: |
| input | 785640 |
| output | 4507 |
| input＋output | 790147 |
| cache hit / miss | 503040 / 282600 |
| 输入加权hit/input | 64.02932640904231% |
| APP占已知完整IO | 100%（纯恢复窗口） |
| reasoning usage | 27条UNKNOWN；不额外加到output |

此统计仅来自bounded last100诊断；完整generation覆盖、物理HTTP调用、probe用量和账单成本仍UNKNOWN，不将缺失补0。历史条目按时间排除，本轮没有重新校验其全部历史身份。本次恢复的100%及64.0293%不能当两项目FULL最终审批占比或缓存命中率，未增加完整BUSINESS通过数。

## 收尾与边界

产品、只读工具均实际退出；CIM核匹配本轮Java为0，未终止任何进程。精确路径、owner及无reparse核过后，仅删除`l277-01`、`v277-01`两处自有临时目录，保留原账本和全部失败/拒绝证据，见`live01-cleanup.json`。无全量测试、新FULL、CSV或推送。

纠错的原pending＋原continuation REQUEST一次性键已消费；不得换UUID、版本或诊断代际重送，也不重放旧272/385/449。原238尚未安全结清，因此不能跨到新的File Transfer部署或CSV。194/234不恢复，221不自动恢复；两项目完整BUSINESS v2、真实浏览器和必要收尾后的最终指标仍待完成。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-correction-277`。本轮关键拒因追踪由GPT-6.1-Sol只读完成，未新增GPT-6-Astra调用；之前277生成缺陷判断已使用Astra，见原277阶段记录。
