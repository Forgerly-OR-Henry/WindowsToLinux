# Agent 隔离取消诊断与保护集边界（282）

用户恢复后，先核当前规则、281暂停记录、Git及产品进程，再进行固定281的事后只读审计。原194不恢复，238/280/281均保留UNKNOWN；File Transfer完成前不启动CSV，两项目完整验收后暂停汇报最终指标的目标不变。

## 281真实状态与诊断

固定reader实际javac/read均0：existing-only bootstrap、H2 READONLY、既有空锁、public42事件逐字匹配及身份codec，锁内与关闭后DB/bootstrap/lock属性不变；没有Runtime、TaskContext、凭据行、模型、SSH或DB文件hash。平台和Mod仍UNKNOWN，780资源中775已释放、5未释放；正式App、component、release、execution、receipt均0。ISOLATION_PREFLIGHT1、PREPARE_EXECUTING1，无RESULT/CANDIDATE记录；不能据零索引抹去stdout已成功创建候选的事实。

Astra仅就未决绑定关系作只读判断：停止正文时间10:35:02.3592113Z，错误记录10:35:03.167Z，相差约0.808秒。候选create后的内置query重算scope会重新验证真实source snapshot及取消令牌；取消可被Snapshot包装为IllegalStateException，再包装成ISOLATION_UNCONFIRMED。未找到必须放宽scope/task绑定的证据。历史cause已丢失，不能追认281唯一根因。

Sol补真实H2、原TaskHandle、Go backend/Node web双组件的本地对照。无取消时prepare完成create→query→upload→verify→open；在create确认成功边界触发真实requestCancel后，READY flag仍true、query APP/send与upload/verify/open均0，异常保留Snapshot unavailable cause，journal UNKNOWN、PREPARE_EXECUTING1且无RESULT/CANDIDATE/NOT_SENT、effectsSettled=false。这证明取消足以产生原错误形状，不证明原281已经结清或整个prepare未派发。

## 最小改动

- Agent IsolationEvidence只为原ApiException保留cause；不更改准入、scope比较、REFUSED反馈、APP或grant。
- Java/Python保护metadata行上限显式同步48→64，字节上限仍16384。实际原40行回执8766B；加入281两unit及已创建backend候选的19行保守本地投影为59行12520B。排序、namespace、完整mandatory、预算、字节、截断及未知守门均保留。
- Agent实机fixture的事后保护集核对：stop已存在时保持未验证失败、不派发核对；已提交的核对QUERY_TASK单独允许产品cancel。默认完成等待及STATIC/ASSISTED原生路径不变，不把取消当作核对通过。
- 下一次可信fixture额外保护281真实Task/units/backend候选与49282/49283，要求保留238/280保护集；新任务采用49284/49285。新实机proof仍必须来自完整独立APP及全新grant。

## 实际验证

最终当前6个相关方法分别通过，未重跑已过组、未运行全量：保护集2例在test02；停止2例在test03；双组件正向和取消2例在test07。最后package退出0，deployment.jar为4246754B。

原始尝试完整保留：test01/06是testCompile失败、0用例；test02有单组件READY1P及保护集2P，停止2skip不计通过；test04/05各1F，均为新fixture的read limit或loopback URL前置拒绝，修fixture后只重跑相关组。全部物理JUnit共11例次=7P/2F/0E/2skip；不是11个不同方法。测试受控模型与传输，不是新实机部署或真实provider统计。

核匹配Java0后按owner/边界/no-reparse清理本轮reader classes/空sourcepath/tmp及c282a/u282t；未终止进程，原始证据和UNKNOWN运行库均保留。5源码窄增量及限定摘要只在本次Git提交时统一生成，既有迁移、个人AGENTS与辅助记录不暂存，不推送。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-lifecycle-282/`，含reader、local-validation、local-cleanup、各test结果及两份Sol报告。当前未启动新FULL；后续冻结源码后只启动一次新的File Transfer完整验收，不能重放281或复用其grant。BUSINESS v2、真实浏览器、STOP/DISABLE及最终两项目APP/缓存指标仍待实机完成。
