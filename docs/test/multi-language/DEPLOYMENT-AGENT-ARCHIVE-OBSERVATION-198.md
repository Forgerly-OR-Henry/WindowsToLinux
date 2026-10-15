# 198：归档观察审批边界澄清，本地定点通过

本阶段仅修改Agent基础审批规则，澄清当前远端观察、已绑定的客户端强制消费与后续项目执行的区别；修正直接测试的旧夹具。最终两个方法、5个case全部通过，唯一显式skipTests打包成功。没有新增付费模型、真实SSH或业务验收，不能证明审批占比／缓存收益。

## 已确认原因和实现

[197真实验收](DEPLOYMENT-AGENT-CONTROLLER-LIVE-197.md)最后对frontend归档只读核验作出有效DENY：模型将远端脚本未自行比较预期值视为缺少比较，并要求此观察先证明后续项目执行身份。实际CandidateChannel已对原能力绑定的完整成功stdout逐字比较bytes、完整SHA及OWNER=0；这些事实已给固定首USER，既有Skill也已有说明。完整出站wire未记录，不能声称恢复了全部请求字节；原DENY、BLOCKED及资源证据不改写。

审批资源 `service/agent/review/approval-agent.md` 只增加两段：

- 按当前可达效果审阅授权、路径、披露、写入与执行；区分远端observer和真实请求／能力绑定的client consumer，计入其实际强制比较。标签、预期值和捕获spec不代表远端观察或比较已成功，只读仍需独立审批。
- 分清数据管理身份、文件系统可写性与项目执行身份。root管理归档不能证明root项目执行，也不能证明隔离；后续修改／解包／执行有各自前置条件、独立审批和新grant。只有当前具体效果或依赖需要时，才将后续条件用于当前观察；不要求APPROVE。

没有新增自动放行contract、强制Skill顺序或替模型裁决。生产Java、实际consumer、连接身份比较、输入覆盖、静态门禁、配额和超时均未改；STATIC／ASSISTED编排不变。规则本身略增固定文本，不能据此估算净token收益。Astra仅就这一具体矛盾作只读判断，选择最小规则澄清；没有必需的Astra终审。

## 定点验证与原失败

仅选 `DeploymentAgentPrepareReviewTest#uploadedArchiveConsumerEvidenceReachesNativeReviewBeforeVerifyAndOpen+uploadedArchiveConsumerEvidenceRejectsUnboundRequests`，前者4个参数，后者1例。使用受控原生模型／transport、本地临时H2Runtime夹具，不是外部provider或真实SSH。

| 轮次 | 实际结果 | 原因／后续范围 |
|---|---|---|
| validation | 5 FAIL，0 ERROR／SKIP，21.943秒 | 原假指纹不满足117严格格式，尚未触达审批；只把夹具改为43个A的合法合成SHA256格式，不改真实pin |
| validation-02 | 3 PASS／2 FAIL，0 ERROR／SKIP，29.114秒 | 空stdin本来无descriptor；unbound夹具用原peer造spec而非实际diagnostic candidateSession；均为旧夹具假设 |
| validation-03 | 5 PASS，0 ERROR／SKIP，30.992秒 | 只重两个失败方法，未扩大类或模块 |

总实际15次＝8 PASS＋原7 FAIL，最终有效5 PASS。原失败XML／TXT／日志和每轮源码清单均保留，不把历史失败改成通过。参数化失败按具体方法4参数重跑，未重其它方法。

夹具按193固定首USER＋尾部动态metadata取证；显式重读是受控模型的选择。旧INCOMPLETE_BODY假定短脚本尚未预置，已不成立，替换为验证实际完整inline脚本SHA、EMPTY输入与独立grant的正例；缺正文门禁复用195／196既有有效负例，生产守门没有放宽。其它参数验证DENY、配置变化；unbound从真实candidates造原spec，并保留foreign peer spec、BODY／GUARD／STDIN／OUTPUT／timeout／TARGET／OPERATION／REVISION／TASK、归档大小／SHA／workspace变化的拒绝断言。

单测试文件IDEA最终check退出0；首次沙箱本地锁／IPC错误保留，正常用户检查成功。父辅助脚本一次抄错初稿manifest SHA，在复制／修改前断言停止，核实际SHA后纠正；不属于产品失败或业务重试。测试期间源码冻结，未运行全量测试。

## 构件、隔离与收尾

输入HEAD为 `cdbb09decd54f0f3f5908a12e0af152f4cebc37f`。源码清单3307项，只有上述policy与直接测试两项相对本轮dirty baseline变化；原迁移及其它任务改动全保。初稿和第二轮manifest／patch在各自before子目录保留。

唯一执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、16.358秒，14处skip、0测试执行；源码前后冻结检查均exit0。最终deployment.jar SHA256：

`7aaa66bf132409ed8e341905f267ae6353d8e4eddde9f7d145d0077f91f0abbe`

新policy source／target／JAR逐字相同，SHA `ee40e43b3a0707fdea64a4121414d22b26555568f647e3b551dcf370f9e99589`。父另核196全部17组生产构件共36个成员／资源，以及Approval／InputCoverage／NativeHistory／SourceBrowser、宿主AI／HTTP和真实验收fixture原字节不变。

正常用户CIM确认三轮各15组、打包12组PID／creation ticks全部退出，自有marker绑定TMP／短UDS清，原产品数据删除0。没有启动browser或凭据副本。父在自有scratch实际Git apply正向check／应用、反向check／应用，确认最终／原dirty bytes逐字一致后清scratch。

本地交付只提交此文档、[精确源码增量补丁](patches/AGENT-ARCHIVE-OBSERVATION-198.patch)及其单路径-text属性。补丁相对本轮实际dirty bytes，不能假定干净HEAD有完整迁移源码；不整体暂存混合源码，不推送。

原件根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/archive-observation-review-198/`：

- final manifest：`b16cc1e959527054e916e1f4baa5cae7768c219b8796f29d0cca8d8855a5c196`；patch：`ececed2fda8e9dcf13edd7bc2f237dd77187738dcf80a1d7c59b51efd5c6e614`。
- `parent-unit-verification198.json`：`092d37e042051d911502d7ad9f12188b01a3de4ecc458569077498ab21436ca3`。
- `parent-patch-verification198.json`：`60b4e0f10da96545cde5ea1881f3f44567b6bf4814872e0f31396259f4e1d1c9`。
- `parent-delivery-verification198.json`：`981109a345f19f6905a2ff9d12116ac0d30ca8a9c6fcadf6be5e49d44aa46fed`。
- 153份本地原件seal：`d9179cc28f5e125c13d164a783ce01306386f268c10cae9c67234845eedce0c1`。

[186完整历史通过](DEPLOYMENT-FRESH-FULL-ACCEPTANCE-186.md)的审批82.9103%／缓存68.6386%仍是原完整口径；197的60.5263%／73.4427%只是失败截面，不能等覆盖比较。本阶段不立即再付费，也不保证规则会改变模型裁决。完整目标保持进行中：新版task-board完整业务与逐角色实际收益、串行其余七项目、冻结后全新机八项目、非默认目录真实部署、整机重启恢复，以及原197／194等未结资源正式结清仍待。
