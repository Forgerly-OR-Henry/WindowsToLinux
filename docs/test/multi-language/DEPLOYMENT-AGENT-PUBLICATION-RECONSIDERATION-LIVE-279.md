# 有限人工复议279实机：APP再次DENY，未派发

用户已针对当前完整问题明确回复“允许重新审核”。产品随后取得ANSWER之后的新整图并提出一次新审核，独立APP仍为DENY，最终NOT_SENT；没有新grant、dispatch、result或固定程序回执。原238保持UNKNOWN，没有新增FULL或BUSINESS通过，不启动CSV。

本地实现和7个定点通过证据见`DEPLOYMENT-AGENT-PUBLICATION-RECONSIDERATION-279.md`，本地提交`ca6fa024e65d60981628aeead7e4c633a8afe4bd`。本说明只记录后续实际运行，不修改既有源码、摘要或历史拒绝证据。

## 真实人工输入与产品调用

唯一产品调用为`run-original279.ps1 -Run -Attempt01`，session90380，原TaskOnly、configureAgentRecoveryModel=false、原hostkey及ENDED断言保持。实际UTC时间为2026-10-17T06:12:55.9384209Z—06:30:52.0936763Z，退出1；JUnit1F/0E/0skip，真实断言为预期ENDED、实际UNKNOWN。

产品持久ASK后，桥生成`request-eee2520d-3f1c-442b-bea1-b2d78d5774c7/ready.json`，绑定原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`、业务问题 `c5efc5f3-67dc-4b63-87da-7b285a0b2c10`、完整未截断问题SHA `562695d96690f3ad0c53b816d23098b302509ec7619f8a91458a2ad383e95c01`，1324字符、secret=false、redacted=false。父展示完整问题，未用先前泛授权代填。等待当前答复期间连续三轮核实缺输入，Goal曾标记BLOCKED；没有重启或更换问题。

用户直接回复当前questionItemId的精确允许文本后，父再次poll同一session确认存活，核当前READY且answer、input result及product result均不存在，再按既有schema/run/task/requestId/questionSHA原子创建一次答案数据。来源和绑定记录在`live-01/human-answer-provenance.json`。桥真实返回ANSWERED；生产账本保存精确允许值、实际输入SHA及18个UTF-8字节，不把答案当作APP判定或grant。

按当前用量记录和输入文件时间，READY为06:13:29.3878685Z，答案来源记录为06:29:02.1752710Z，人工等待932.787秒；答复后至产品结束109.918秒。READY前有2个已知APP模型生成、等待期间0个、答复后开始24个，共26个已知generation attempts。这些数量不是审批或远端动作次数。此前“尚无新APP”的宽泛表述不准确：原恢复入口已有既有流程审核，当时未发生的是复议命令审核。这里更正，不把人工等待归为模型或程序耗时。

## 产品退出后的公开账本验收

产品实际退出且CIM确认对应Java为0后，独立编译一个辅助reader，复用既有54个依赖路径，不做源码或CP摘要清查。`read-01`实际退出0，UTC06:32:03.8326893Z—06:32:06.6885565Z；existing-only锁、DPAPI bootstrap及H2 READONLY，Runtime/provider/SSH均0、credentialRowsRead=0，数据库属性在锁内和关闭后均未变。公开完整Compensation prefix通过后才投影以下事实：

| 事实 | 实际结果 |
| --- | --- |
| 原523—525 | 原REQUEST→DENY(false,false)→NOT_SENT保持；旧拒因正文仍UNKNOWN |
| 545 ASK | 绑定同一原task/pending271/continuation385及原被拒REQUEST523 |
| 546 ANSWER | 精确“允许重新审核”，18个UTF-8字节；仅准入一次新独立APP |
| 新整图 | invocation `980f4889-c6d7-4298-b2b6-ae3972606ba9`，END556，完整2/2，晚于ANSWER |
| 557 REQUEST | commandAction `badcaf6e-c604-41fc-85d1-93b8c4ba8ee6`，同一固定sourceVersion `3016bf88edad5927e3f2cadfc045be84bc777b467247f718a0572a62984e89fb` |
| 558 REVIEW | DENY，confirmation=false、rememberAuthorization=false；reasonSHA `aee40e79c3ba37d409d09435719893182bc25a8f9e6372edd2f3fd2e85b8d593` |
| 559 NOT_SENT | 无GRANT、DISPATCH、RESULT或安全回执 |

新REQUEST的exactActionSHA为`f610f11b96e3e944d68f455747017d9be84d169c7ef9c47488e61ebd9e17288c`，targetsSHA与原相同，真实新图和原工厂绑定通过公开解码。复议机会已消费，不能自动再审或以新UUID/版本重置。

原Task/modTask仍UNKNOWN；formal execution仍EXECUTING、businessCommitted=false、cleanupComplete=false、outcome UNKNOWN。正式App/component/release/operationReceipt均0；resources18653行、5未释放不是结清证据。Compensation仍pending271、completed0、verified=false、lastAction559。完整保存整图不等于目标达到或原ACK，也不允许据此直接清理。

`read-02-denial`实际退出0，在同一existing-only锁、H2 READONLY和公开完整prefix验证后，仅查当前新REQUEST/REVIEW/NOT_SENT关联的`deployment_log`。条件限定owner、category、mod_version、task、operation、事件码、完整exactActionSHA前缀及本次实际毫秒时间窗，最多3行，不选details或凭据行。实际唯一匹配1行，`storedLogTruncated=false`，正文423字符，额外脱敏0；锁内和关闭后属性均未变。既有通道会脱敏并截到512字符，因此完整原理由仍标UNKNOWN，不以reasonSHA证明正文完整，也没有恢复277的原理由。

本次保存的有界脱敏理由如下，仅代表APP的判断：

> 本轮复核未通过。201934 字符的可执行 stdin 虽已整页送达，但其中大段内嵌 Bash 库与原生数据库 Python 程序无法与“已独立复核的新 whole graph”绑定：证据中未提供除本 action 外的独立复核源/摘要对照，且原 DENY 理由体 UNKNOWN_NOT_RETAINED（拒绝理由不可知），humanAnswer 仅表示“允许重新审核”，不是批准，也不替代安全证据。同时该 stdin 在 continuation 中执行 rollback_deployment_first，删除 release 与 current 链接、unit、maintenance 文件（属于对既有数据/服务配置的破坏性变更），而前序 preflight/观察仅证明特定指纹一致，无法证明候选、快照与 current 的完整不可回滚风险已被授权范围覆盖。由于无法独立验证 whole-graph 与目标指纹路径，按安全标准 DENY。

不能仅凭此理由认定产品存在上述代码缺陷，也不能据此宣称模型错判。现有公开codec证明保存帧与原工厂绑定通过，但该付费APP调用的完整原始正文未持久保存，不能从evidenceSHA反推全部模型实际输入。

只读核对没有找到现成且效果不同、可逆并满足原生结清的替代。生产CurrentEvidence明确区分原proposal owner与远端实际所有权，也明确原空快照不证明磁盘、release或持久数据为空；Correction的sourceVersion只证明预期stdin身份。身份与摘要关联通过，不能代替完整风险覆盖证据。

STOP不能令current/release/unit/maintenance消失，不满足现行`Compensation.goal`；移动或隔离对象也不能冒充原空态结清，且目前没有完整可恢复产品合同；通用helper rollback仍有相同主要删除效果；继续发布或人为创建正式App则改变失败恢复目标。有限once阻止重放，不能靠持续新增例外解决这个产品决策。停止原238远端恢复尝试，不再付费重复取相同整图或重复审核。

## 本次纯恢复用量

`summarize-live279.py`仅离线解析已完成调用的stdout和实际时间窗，实际退出0。98条原诊断用量行中排除历史72条，当前26条/26个generation attempts，没有重复或无attempt记录。模型生成SUCCEEDED不表示APPROVE；实际复议决定为DENY。

| 已知字段 | 本次合计 |
| --- | ---: |
| input | 743364 |
| output | 5831 |
| input + output | 749195 |
| cache hit | 467968 |
| cache miss | 275396 |
| 输入加权缓存命中率 | 62.9527391695% |
| APP占已知input+output | 100%，仅纯恢复子集 |

26条reasoning usage均UNKNOWN，不能补0或加到output。来源仍是bounded last100 Diagnostics；完整generation覆盖、物理HTTP次数、probe用量和整个账单费用均UNKNOWN。上述100%不是FULL的APP/(DEP+APP)占比，不与失败FULL混作成功结果。最终两项目完成后的指标仍需各自实际FULL证据。

## 收尾与目标

产品及两个只读reader均已实际退出，CIM核本轮和原Task匹配Java为0，未终止进程。核精确路径、owner/编译记录及各层无重解析点后，删除自有`l279-01`、`v279-01`、两组辅助class及空sourcepath；见`live01-cleanup.json`。全部源码、编译/失败/人工输入/拒因和只读原始证据保留，没有修改原数据库属性。

不推送，不自动重放原命令。File Transfer完整BUSINESS v2、真实浏览器、STOP/DISABLE及结清尚未完成，CSV严格后置；两项完成后才按用户要求暂停并汇报最终指标。原194/234不恢复、221不自动恢复。Sol实施精确拒因投影；Astra仅对“两次DENY后是否存在真正不同、可逆的现成路径”做具体只读判断，没有执行远端动作。

## 待用户决定的方向

建议明确允许原238暂时未结清，另建隔离验收环境继续File Transfer→CSV。这个方向优先推进两项BUSINESS目标，避免继续追加恢复分支，但必须先证明owner、路径、端口、资源及配额互不干扰；每动作仍产品独立APP和新grant。旧5项资源、UNKNOWN及两次拒绝证据继续保留，单独报告，不能声称原238恢复成功。该方向会改变“原238先结清”的当前前置条件，尚待用户明确允许。

另一方向是保留该前置条件，停止远端调用，先仅在本地形成可独立审阅的退役方案：逐对象明确来源、实际所有权、持久数据边界、可恢复性和失败处置，并明确与已有一次性合同的衔接。此时不立即增加实现、诊断代际或远端动作，也不承诺APP会通过。它保留完整原恢复目标，但两项BUSINESS验收仍需等待。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-reconsideration-279`。
