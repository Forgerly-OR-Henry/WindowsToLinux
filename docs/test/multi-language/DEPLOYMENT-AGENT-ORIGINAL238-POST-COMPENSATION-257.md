# 原238补偿后本地核账257：安装READY，回滚未确认

257完成一次真实本地只读核账：256新INSTALL已有独立APP/grant/dispatch及ACKNOWLEDGED/READY；backend回滚RESULT为observedOutcome=FAILED、resolution=UNKNOWN、fixedReceiptVerified=false。原Task仍UNKNOWN，不能写成安装未发送、APP拒绝或远端回滚成功。具体FAILED stdout/stderr持久化位置及原因仍待后续只读定位（PENDING），本记录不提供重发或补偿方案。

## 范围与实际验证

固定原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`、execution `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`、Runtime `agent-live-file-transfer-8pqtfa76o`、期望App身份`wtl-agent-file-transfer-8pqtgvbwc`。观察承接[256单次产品协调](DEPLOYMENT-AGENT-ORIGINAL238-COMPENSATION-256.md)，没有新产品恢复、FULL或远端调用。

fresh capture0，baseline SHA `85a723c6837daa02c9166f990da705183fb48eff3efa44089e76d517d9b459b7`；isolated javac0/2class、唯一reader0/stderr0、finalizer0。reader沿existing-only bootstrap/原空锁、H2 IFEXISTS/ACCESS_MODE_DATA=r/isReadOnly及qualified有限SELECT；无Runtime.open/initializer/fakeTaskContext。仅白名单身份、状态及payload长度/SHA，不输出secret/原模型正文。

源码3319/manifest `8bdcb32a96e2a22db672608aa36b7de3a43677c47e19a8767aa98d05f9f29824`、六JAR/54CP-3249保持；DB193339392B/SHA `444856f628fa2c41e45a27216251059a78b2f7422729246c06c37c7ee9c25253`，bootstrap358B/SHA `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`，原空锁0B/birth `2026-10-16T17:03:17.5271606Z`，锁内及关闭后字节均不变。

父预检仅修本地壳FileInfo祖先遍历为Directory再Parent，保no-reparse；原壳preimage保留。隔离编译禁processor/implicit compilation，自有空sourcepath最终非递归删除。父正常非admin独核5精确PID+创建时间pair全退，positive control371，执行flags全false；没有终止他人进程、产品恢复、provider或SSH。

## 新账本证据

| 原事件 | 有限投影 |
| --- | --- |
| 257–261 | 新readiness经APP/grant/dispatch，261 ACKNOWLEDGED/MISSING |
| 262–266 | install action `729c05dc-fae5-4879-86a5-fe4f57042ab5`；263APPROVE、264fresh grant、265dispatch、266 ACKNOWLEDGED/READY，绑定原Task |
| 267–270 | 补偿PLAN267，backend/web组件268/269，READY270 |
| 271 | backend GUARDED_ROLLBACK INTENT，action `d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24`，独立APPROVE+fresh grant、remember=false |
| 272 | RESULT：observedOutcome=FAILED、resolution=UNKNOWN、fixedReceiptVerified=false；receipt SHA `92761ff0115950db2573ea37121e9973369bce4e81048f73d4f8dcf7f6ebfda5` |

typed安装状态ready=true，但补偿pending=true/completed=0/verified=false。公开codec只证明有限typed-state合法，不是正式TaskContext授权/结清验证。平台UNKNOWN、outcome/endedAt空，problem event为`DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATION_UNCONFIRMED`；Mod UNKNOWN/semantics4、223events、public effectsSettled=false。

资源11740条，11735released、5未released；有界选择明细7条，不能据released单字段宣称资源正式结清。正式App/component/release/operationReceipt本地计数均0，不能推远端空、未发布或已补偿。

当前256窗口currentDiagnostics为空，safeReason=NOT_SELECTED（问题并非精确review denial）。没有本轮APP DENY证据；不把旧252拒因当本轮原因，也不把回滚RESULT observedOutcome=FAILED直接变成Task FAILED/ENDED。记录已证明安装READY及补偿结果未确认，尚未证明实际远端效果或固定receipt为何未通过；禁止自动reinstall/rollback resend。

## 证据与后续边界

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-compensation-ledger-257/`。原stdout为`tools/local-ledger-257-read-01/stdout.json`，SHA `f49758e3cf257a1dd6d2812f1b3ba25833b28d8ef5be1889f35fb56757fe259f`；父终态审计`parent-terminal-audit257.json`，独立进程核对`parent-independent-process-audit257.json`（SHA `b6e38e8eb209a1148c0e94723975da84b6e51e13aaa1467ae27c0ec732092616`）。准备历史、壳preimage和实际编译/reader/收尾记录均保留。255旧seal的长路径XML间接anchor限制沿256已审合同保留，不重写旧seal。

本阶段无production source改动、不造patch；本记录只读现成JSON，没有编译、打开DB、CIM或网络执行。257正式commit/seal由父后置，FAILED原结果正文的后续只读定位仍PENDING，不在此展开新方案。

历史9 FULL=FT8+CSV Inspector208一次，完整BUSINESS仍0。目标仍为FT完整BUSINESSv2、真实浏览器和正规收尾后串行CSV；两项真实完成及必要收尾后立即暂停，分项目报FULL审批IO/cache已知未知，失败与纯恢复独列，等待真人方向，不自动第三。194/234不恢复，221不自动恢复。

## 后续只读源码定位

按生产resultDigest的字段顺序和UTF8规则，固定公开候选FAILED、stdout=`PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n`、stderr空、truncated=false、exit70、Problem IO_FAILURE/SSH_COMMAND_NONZERO复算，283B摘要对象SHA精确匹配事件272的原receipt。详见`parent-fixed-failure-tuple-audit257.json`；这是固定协议候选的摘要匹配，不是独立读到原始body或反解任意原文，不能定位内部Bash失败阶段/副作用。

Producer只向acknowledge传actual，Compensation仅存总结果摘要/outcome/parser分类；平台SSH的FAILED直接返回，只有UNKNOWN调用recordFailure；固定控制器捕获内部Bash stdout/stderr后把非零、非空stderr或非预期输出统一折叠成上述失败形状并exit70。原JUnit system-out也没有协议body，当前未找到可供新本地reader恢复的内部错误正文；不再为此重复打开DB。

旧248入口实际仍为普通tasks.reconcile，已有Compensation Plan时pending观察达标后可能继续cleanup，不能直接复用为纯观察。现只读审定真实原Task回调中的单次wholegraph纯观察入口；尚未实施或放行。原安装READY与FAILED/UNKNOWN帧保持，禁止重装、重发回滚、伪造ACK或结清。源码路径及实际SHA见`source-trace257.json`。
