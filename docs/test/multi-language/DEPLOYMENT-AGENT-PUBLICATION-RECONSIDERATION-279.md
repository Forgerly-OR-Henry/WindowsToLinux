# 原发布纠错的有限人工复议279

277的独立APP拒绝原纠错，523 REQUEST→524 DENY→525 NOT_SENT完整且未产生grant、dispatch或result。原一次性机会已消耗，拒因正文未留存。279增加一次明确人工准入的重新独立审核，旧DENY、NOT_SENT及一次性键均保留；不能用于已派发的UNKNOWN。

用户已允许自主比较或尝试其他方案，要求最终汇报采用的方案。该泛授权不被写成当前具体问题的答案。真正原任务恢复必须先产生持久ASK，展示当前完整问题并取得真人明确回复；只有精确答案“允许重新审核”才能准入一次新APP。人工答案不是APPROVE，也不是grant。

## 选择与实现

已比较三条恢复路线：现成组件管理要求正式App，原238尚无正式App；通用helper生命周期可能清理其他遗留对象，不能声称仅停止目标；复用同一固定纠错程序、加入明确人工准入的有限复议可以保留原审核证据和执行边界。因此实施第三条，同时278已补齐未来拒因的有界脱敏记录。前两条未用于远端操作，也没有通过更换UUID、版本或helper重置原机会。

新增`DeploymentAgentPublicationReconsideration`，沿用原CorrectionProgram、OPERATION及sourceVersion `3016bf88edad5927e3f2cadfc045be84bc777b467247f718a0572a62984e89fb`，不增加程序代际或安装。复议键绑定原task、pending271、continuation385和被拒REQUEST523。持久ASK即消费机会；取消、其他答案、中断或重启不会自动再问或重送。

ASK绑定完整原身份、旧拒绝三帧、原source/request及问题UUID和SHA，明确旧拒因正文未知。生产回调持有真实TaskContext，在宿主`requestText`前后完整重新验证同一ASK与历史，并检查取消。任意其他文本只保留有限分类及实际UTF-8长度和SHA，不保存任意原文。准确允许值才记录准入；取消和非准确值均不允许新APP或grant。

人工允许后，先通过独立APP和新grant取得ANSWER之后的完整新整图。若目标已满足，沿原观测、独立清理和验证路径结清，不产生新纠错REQUEST。否则仍以原固定程序重新独立审核；完整APP可见人工问题和答案含义、原DENY/NOT_SENT及拒因正文未知。APP仍可拒绝；通过后才申请新的精确、一次性grant。新纠错命令的任何回执在本次调用仍保留UNKNOWN；后续必须取得新整图目标证明并独立清理，才能原生结清为FAILED。旧272、391及525不改写。

## 输入桥与校验

验收桥只转发当前真实原任务回调的问题和真人答案。活跃task/root绑定仅存在于真实reconcile的try/finally生命周期；专用标题不进入自动回答。canonical问题必须完整、未截断、未脱敏且不超过2000字符。ready/answer继续绑定run、task、requestId及questionSha256，附业务questionUUID；旧答案、错任务、错问题及错生命周期均拒绝。桥不伪造TaskContext或自行打开Mod DB，持久事实由生产回调核验。

首轮测试出现同次读取反复解码旧链。最小修正只涉及新类和Compensation：单次严格`Comp.read`验证原Correction块后，局部复用该次读取的typed旧DENY证明；新REQUEST仍用真实ANSWER后整图和原工厂重建、比较source/request/targets/exactAction及回执。`verifyAdmission`保留fresh完整读取、同一原身份/ASK/问题、取消及事件前后一致校验，去掉同边界第二次旧链解析。没有跨read或回调缓存，没有修改旧codec、Program、sourceVersion、生产timeout或测试90秒上限。

## 实际本地验证

离线Maven显式限定方法、`-pl src/mod/deployment -am`及`-Dsurefire.failIfNoSpecifiedTests=false`。Bridge阶段test，其余阶段package。没有运行整个模块或全项目测试，也没有真实模型、SSH或原238数据库访问。

| 证据 | 实际结果 |
| --- | --- |
| test-01-bridge | 1P/0F/0E/0skip，JUnit0.092秒；任务/问题绑定及取消，无自动答案或grant |
| test-02-history | 退出1，6F/0E/0skip，566.142秒；全部是原工作未在90秒内结束，原失败及源码版本保留 |
| 两文件修正 | 仅去同次读取冗余；首轮原字节保存在first-validation，无逐步hash |
| test-03-history | 单独完整APP通过路径1P/0F/0E/0skip，JUnit80.916秒，package成功，原90秒不变 |
| test-04-history | 退出0，其余5个原失败相关用例5P/0F/0E/0skip，JUnit共291.636秒，单例46.963—67.410秒，package成功 |

首轮Fixture超时只抛出等待失败，后台TaskWork仍运行并与下例重叠；后例耗时受干扰，不能视为独立正常预算或性能测量。修正后先单例验证，再运行其余失败组，不重复已通过的Bridge或通过路径。未调整生产时限，也不将局部通过称为全量通过。

最终7个相关定点全部通过；本轮实际执行共13例次，其中首轮6次失败保留，不改判。`local-validation.json`只从实际通过报告汇总7个精确方法及package退出0，不用预期值代替结果。最终JAR为4184436字节。

本轮所有Maven及测试Java已退出。CIM核本轮专有Java为0，未终止进程；精确工作区路径、owner及各层无重解析点核验后删除自有`c279a`、`u279a`，见`279/cleanup.json`。只在提交时统一计算6源码、JAR、patch和本说明9项摘要，没有全源或CP清查。实机入口仍默认禁用，显式运行还须上述真实验证汇总；尚无当前真人ASK答复、新APP或远端复议。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-reconsideration-279`。最终仅提交本轮6路径增量patch、说明及提交时统一摘要，保留原工作区迁移，不推送。Sol实施与验证；Astra仅判断有限人工授权及真实宿主输入桥边界，没有执行远端动作。

File Transfer完整BUSINESS v2、真实浏览器及收尾仍未完成，CSV后置。原194/234不恢复、221不自动恢复。两项完成后暂停，汇报FULL审批占比和输入加权缓存命中率；失败FULL与纯恢复另列，缺失用量保持UNKNOWN。
