# Agent 原238只读核账259：backend回滚目标未达成

259只读核账已完成。258的新wholegraph观测完整，但backend仍有CURRENT、unit、maintenance及一份release，不能满足原pending回滚的生产goal。产品因此保留UNKNOWN，没有追加OBSERVED_ONLY或继续候选清理。当前阻塞不是缺少观测；首次回滚内部失败原因仍未知，不再重复普通reconcile。

## 实测与安全边界

fresh字节capture0，baseline SHA `d5358a5a9ed4348a463008794642a6138a4611c6057de987b6601607ea097277`；隔离javac0、2class，唯一reader0/stderr0，normal finalizer0。父独立普通用户CIM核编译器、reader、launcher和finalizer共5个精确PID/创建时间全部退出；本次空sourcepath已非递归删除，全部执行开关false。

原FileChannel空锁、H2 IFEXISTS/ACCESS_MODE_DATA=r和连接isReadOnly均通过；数据库在打开前、锁内及关闭后字节一致：193339392B/SHA `2f469cc5806a2aa784c60f2eb7afbe7c217d4aee47721d991aaf8e1aa68a4aaf`，bootstrap358B/SHA `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。原空锁birth仍为2026-10-16T17:03:17.5271606Z。

源3319/manifest `8bdcb32a96e2a22db672608aa36b7de3a43677c47e19a8767aa98d05f9f29824`、deployment.jar `8a3a3db409d5f66e29ffa98fa787a665b3961103dfc086210ba269de3efa0c6c`、54CP/3249files及255的48+3+4+5身份链均fresh核同。本阶段无生产源码改动，不生成patch；没有Runtime.open、真实Task API调用、模型、SSH或恢复动作。

## 本轮原任务账本

平台Task `072bc074-4bce-4c14-b425-f2ff965c7d40`仍UNKNOWN，terminalOutcome/endedAt为空，问题为UNKNOWN/DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATION_UNCONFIRMED。Mod同为UNKNOWN、executionSemantics4，233个原事件，public effectsSettled=false。12194资源中12189 released，剩5项为FILE×2、MOD、SCHEMA、SERVER；3个原server references仍RESERVED。正式App/component/release/operationReceipt本地计数各0，不能据此推远端为空或任务已结清。

最新观测BEGIN289、END298，4个完整帧，2个组件均有可验证原receipt；公共生产codec验证原Task、execution/request digest及帧引用，未回退旧观测。观测属于258实际窗口2026-10-16T22:40:29.1299200Z—22:41:33.9983997Z；259没有新远端probe。

| 原组件 | backend | web |
| --- | --- | --- |
| CURRENT | 原release `39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597`，deployment/DAEMON | none |
| unitPresent / maintenance | true / true | false / false |
| retainedCount / candidate | 1 / true | 0 / true |
| buildActive / snapshot | false / false | false / false |
| previousDigest / previousRunning | none / none | none / none |

原Compensation PLAN267/READY270、operation `e1b83417-dbc1-427f-96e6-9d0b9b3d6760`不变；completed0、verified=false，pending仍为backend GUARDED_ROLLBACK action `d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24`，intent271及FAILED/UNKNOWN result272保留。272后新增Compensation帧为0。原install action `729c05dc-fae5-4879-86a5-fe4f57042ab5`仍READY，mayInstall=false，不能重新安装。

唯一当前诊断为SQLException、53字符、SHA `6d3556dd41010da80b2e1414f1f5088bbea4c66332ca47a933c86697fb0c99c4`。父以固定生产异常候选`SQLException: Unknown action goal remains unconfirmed`复算，长度和SHA精确匹配；这是固定候选核同，不是读取或反解任意日志原文。与生产observedOnly的goal门禁及真实结构化状态一致。

## 证据与后续

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-compensation-ledger-259/`。只读stdout SHA `acf8bb2b9b66b65f50f2649a953453d284c86748e397f045f3455936f1f0944a`；`parent-terminal-audit259.json` SHA `350419cf55ead50903b33e0c89bd1471793b418e76c30c5726383dcde10f93cc`；独立5进程收尾记录SHA `a3bf76604745b2be1c23f0439cb391f23fe44c6b7d1717cd8991492b1e7d5120`。准备阶段误切方法的文本问题在执行前已从sealed257准确边界重建，原正式工具及记录未写，最终编译和真实只读查询均成功。

下一步仅读源码核查产品内独立APP/fresh grant的有界只读诊断入口，定位首次guard失败；不为已丢失的inner Bash正文再次打开本地DB，不重装、重发原UNKNOWN回滚、修改旧ACK或伪造结清，不扩大为通用恢复平台或全局pending策略。

9历史FULL＝File Transfer8＋CSV Inspector阶段208一次，完整BUSINESS仍0。194/234不恢复，221不自动恢复。File Transfer完整BUSINESS v2、真实浏览器和必要正规收尾完成后再串行CSV；两项完成后暂停，分项目报告FULL审批IO占比和cache已知/未知，失败FULL与纯恢复独列，等待真人方向，不自动第三项目。
