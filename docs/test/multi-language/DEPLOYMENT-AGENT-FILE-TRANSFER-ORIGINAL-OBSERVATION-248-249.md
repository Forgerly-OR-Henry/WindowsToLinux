# File Transfer 原238发布观察与事后只读核账：248–249

日期：2026-10-17。原发布的两个组件状态均已通过产品观察并保存有效回执：backend 的 CURRENT 等于原计划 release，maintenance 仍在；web 的 CURRENT 为空、无运行单元。两个候选目录仍在，均无活动构建。**原任务仍为 UNKNOWN，未完成补偿、发布结清或 BUSINESS 验收。**

本地实现来源为提交 `ac956fca12b00e45d6e8c9908156e81e7489b5d0` 的247精确差异补丁。243、246、247分别封存15、10、153个文件，摘要为 `5deb84a9548d5681a25e0716bced95886ab6b8dd1825eec0e2e610cd8ee883a2`、`bd12704ff734302c514b9596df5df0ae19bf8096cf2ece373f40a3618326e65f`、`4d499d54ebd1af916214faba37b5f850b78fd888a9fc1dabd8789afcd3335b89`；之后未写三根。

## 唯一产品观察248

原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`，execution `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，App `wtl-agent-file-transfer-8pqtgvbwc`，Runtime `src/app/db/data/agent-live-file-transfer-8pqtfa76o`。

fresh字节捕获与独立offline均为0。source3312、39 deployment owner groups、3 AI、4 platform families、5完整host模块、6 JAR、CP54/3223文件与封存247产物一致；原pair与封存242相同后才释放产品。保存路线来自实际238日志、239原身份和242事后pair，不冒充新provider/SSH可用性探测。

唯一run为 `original238-observe-248-01`；仅执行 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true、preparationOnly=false、configureAgentRecoveryModel=false。session91546实际ENDED1，窗口 `2026-10-16T19:34:59.2549363Z`—`2026-10-16T19:36:09.7270831Z`；before/afterSource均0，JUnit **1案例、1F、0E、0skip**。原ENDED断言未改；UNKNOWN造成的失败照实保留，不能记成结清成功。

通过原Task真实恢复上下文，对每个组件最多一次原status，独立APP及新grant；没有继续发布、rollback、cleanup、新FULL或自动重试。完整观察仍返回 `DEPLOYMENT_AGENT_PUBLICATION_OBSERVED_UNKNOWN`，无正式App提交或删除权限。

| 终态输入 | SHA256 |
| --- | --- |
| result.json | `e07a93843b4a04da6548361c452068d9b9d66a29de9c7c0104426bc19f7fdb74` |
| maven.stdout.log | `ed506660c548ec73b4a21f94158765aa76531151307afe6167c148c0226b59fe` |
| after-metadata.json | `9ed7c00aa13b6f198449450c4bb4baad9c11807cb82fe7d42280e799ba0917f9` |
| parent-final-process-verification248.json | `fe20b02cb8bcb5061d206640ddee5d1ba225a88b88097f8df29f5b8acd36a3e1` |

normal close核16组精确PID/time退出，仅清本轮两个有所有权标记的TMP/UDS。父最终normal CIM另核18组（含字节捕获父子）全退出；finalizer工具0，授权位false，保留原Runtime。首父XML检查普通路径触发MAX_PATH，在读取前失败；改extended绝对路径后核原XML，错误如实记录。

## 纯观察用量，不能混入FULL或BUSINESS指标

实际本地parser0，父逐核97条原用量行SHA/CRLF引用、8个当前generation身份和token加总。97条中 **8条属248本窗、87条属238历史、2条属241历史**；历史均经封存summary身份、purpose、双时间窗及context核同后排除。

| 248已知子集 | 值 |
| --- | --- |
| recorded generation attempts | 8，均APPROVAL；有效输出上限8192 |
| input / output / IO | 122809 / 4538 / 127347 |
| APP IO / 已知DEP+APP IO | 127347 / 127347 = **100%** |
| cache hit / input | 90496 / 122809 = **73.6884104585%** |
| cache miss | 32313 |
| reasoning | 8条未知；属于output子集，不再相加 |
| 完整generation、physical HTTP、callback覆盖与账单 | **UNKNOWN**，不把缺失用量视作0 |

summary SHA256 `a3022feee7ebf4cb21717dd249abe17f389a02692004798417514d1db79c1f38`；parser授权位撤false，消费绑定原字节保留。没有额外provider调用来补统计。

## 249有限本地只读事实

先fresh捕获事后pair，再隔离javac0（只编reader、禁隐式编译及annotation processor），唯一readonly reader0、stderr0。原空锁下existing-only DPAPI bootstrap、H2 IFEXISTS/ACCESS_MODE_DATA=r及isReadOnly、限定原Task的SELECT全部沿用242；新增投影仅对同次已读取的原EVENTS调用公开纯codec，无额外SQL。未启动Runtime、恢复任务、模型或SSH。

首次capture命令误传散列参数，在生成基线及打开DB前被 `PARENT_BINDING_BYTES_CHANGED` 拒绝；使用实际binding `f3485d3bac18eff9261121dd99de24ebe6a9eaef8a6c2a62695519440b05594a` 后capture0，原错误保留。fresh baseline SHA256 `c82a3711d779b1416dd6c9039aa125485aae393252c1f4ae8807dc785a35e37f`。

stdout SHA256 **`7eeaef0b3bbc04930ede977700792a2ca31a9688e736617cd879538e2bb3bb0d`**。pair在锁内、关闭后和父finalizer均不变：DB138440704 bytes，SHA256 `d8ba0aed195f7586c7af21c519f98d772a649f34da4c7cf85a387334de15ecfd`；bootstrap358 bytes，原SHA256 `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。旧空锁创建时间保持 `2026-10-16T17:03:17.5271606Z`。

新观察BEGIN193、COMPONENT200/201、END202，invocation `4f9456fd-8559-433f-83a1-dca144ba405d`，原prefix到188；公开codec核帧、prefix、请求和APPROVED/result引用完整，原execution/request摘要核同。collectionComplete=true、2个有效receipt；只表示收集完整，仍UNKNOWN/authority NONE，不能代替真实TaskContext、业务或资源结清验证。

| 248历史status receipt | backend | web |
| --- | --- | --- |
| unit | `wtl-agent-file-transfer-8pqtgvbwc-backend` | `wtl-agent-file-transfer-8pqtgvbwc-web` |
| CURRENT | `39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597`，等于原计划 | none |
| current kind / mode | deployment / DAEMON | none / none |
| snapshot / previous digest,kind,running | false / none,none,none | false / none,none,none |
| maintenance | true | false |
| candidate / active build | true / false | true / false |
| unit present / retained releases | true / 1 | false / 0 |

这是248时刻的有效历史回执，249没有重新探测远端。原SNAPSHOT_RESULT178/180各16字节，摘要相同 `c53ab4f1196121e96feed4c8395b63b57453d3126f263d736591eb61b869ca97`；本次投影未输出原detail，不能仅凭长度猜原前态。

平台/Mod原Task仍UNKNOWN、无outcome；原execution仍EXECUTING/UNKNOWN、businessCommitted=false、cleanupComplete=false。198行原events、effectsSettled=false；资源8130行/8125有released时间、7条选择明细，仍有5条未released。正式App/component/release及operation receipt各0，**这些本地计数不表示远端无效果**。

249 reader及launcher由父normal CIM核精确PID/time均退出；finalizer工具0、pair不变、reader/capture位false，自有空sourcepath已移除；两份编译class作为证据保留。准备检查器最初误把插入的空行当旧源码变化，修正比较器后原非空行核同，原失败保留；未修改reader来掩盖结果。

下一步仅据原身份和完整观察审查具体产品内补偿合同，不能伪造FINISHED、STANDARD checkpoint、FAILED或清理证明。当前仍9次actual FULL/0次完整BUSINESS，CSV Inspector未启动；194/234不恢复、221不自动恢复。File Transfer及CSV严格逐个真实验证和必要收尾后暂停，按项目分列FULL审批占比/cache及已知未知、失败和纯恢复，再等真人方向。
