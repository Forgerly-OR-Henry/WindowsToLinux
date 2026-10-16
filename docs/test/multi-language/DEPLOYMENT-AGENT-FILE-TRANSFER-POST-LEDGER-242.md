# 原238协调后只读账本242

2026-10-17。242以existing-only bootstrap、原空锁、H2 `IFEXISTS`及`ACCESS_MODE_DATA=r`、连接`isReadOnly`和限定原Task的qualified SELECT读取本地账本。没有启动产品Runtime、initializer、模型、SSH或恢复，没有读取凭据表、打印秘密正文、修改schema或伪造TaskContext。

## 来源和实际验证

原239核账器只替换四处类名及固定242路径，SQL、公开decoder、归档fallback、资源上限及正式回执校验不变。外壳增加241实际result、after-metadata、正常收尾和closer的精确身份守门；即使241测试失败也允许只读调查，不预设原Task终态。9项局部静态检查及PS AST通过，不能当Java编译或数据库观察。

父代理核实际源码差异并使用JDK21、关闭注解处理和隐式编译、空sourcepath局部编译，退出0，隔离输出两个class。正常用户fresh捕获3311源码、六JAR、33部署组、3AI族、4平台族、5完整宿主及54CP/3213文件；238/239/240封存核同，不依赖尚待交付的241封存。baseline SHA256为`e8dd547c3fffc770583ac3760c6f2b5840b96f46497a7ce50aaa6344c26cb29e`。

首次外壳命令误传占位参数，在PowerShell参数校验阶段被拒，未创建reader或打开DB；记录保留。改用已审实际binding SHA后，唯一reader `local-ledger-242-read-01`实际退出0，stderr为0字节，stdout SHA256为`3a0a018c2d599598a72856c449ccbbd39783bffc50a22d645a2b0d86a208c65c`。未重开产品或Maven。

## 实际账本

| 项目 | 观察结果 |
| --- | --- |
| 原平台Task | UNKNOWN；outcome/endedAt为空；`DEPLOYMENT_AGENT_FINAL_ASSESSMENT_MISSING` |
| Mod Task | UNKNOWN、semantics 4、AUTOMATIC、remember=false；公开WorkspaceBinding校验合法，无successor |
| 原事件 | 188；相对239无新事件；公开`effectsSettled=false` |
| 全资源 | 7617行，其中7612有released时间；较239均增加85行，不能据此推远端清理完成 |
| 选择资源明细 | 7行：5行未released（SERVER/SCHEMA/MOD/两个FILE），两个controller明细有released时间 |
| 原execution | `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，EXECUTING/UNKNOWN、businessCommitted=false、cleanupComplete=false；公开row/template合法 |
| 正式App/component/release/receipt | 本地均0；不证明远端不存在或资源已结清 |

两个controller及原execution的request/recovery/result摘要与239相同。原publication事件166进入发布过程；181/182/183的action摘要精确匹配公开组件名backend，分别为PUBLISH_EXECUTING、PUBLISH_RESULT、HEALTH_RESULT；184匹配web的PUBLISH_EXECUTING。没有AI_PUBLICATION_FINISHED。这些本地原始事件不能代替当前远端owner、CURRENT和健康验证；本轮没有调用需要真实TaskContext的完整ProgramState或恢复验证器。

## 数据和进程收尾

241实际后置保护数据对与242 fresh字节捕获一致，reader持原锁前后均核同：

- DB：119705600 bytes，SHA256 `b61974d60274260b0b66c85a1d1307fe2d14c7b3206a742794ffc5b165c95a60`。
- bootstrap：358 bytes，SHA256 `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。
- 原空锁创建时间：`2026-10-16T17:03:17.5271606Z`，未改变。

父代理再核3311源码、六JAR及CP成员SHA前后不变；正常CIM最终核5组精确PID/创建时间（含reader、launcher、capture、compiler launcher及finalizer）全部退出，javac同步退出0。仅移除本轮空sourcepath目录，保留class及证据；reader开关关闭，消耗前binding保留，没有强杀进程。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-local-ledger-242/`。当前结论是原238仍未结清，下一步针对最终评估缺失及部分发布的产品恢复合同调查，不能据effectsSettled、缺行或时间戳删除历史。

用户另已直接批准内部可恢复单组件STOP/START与版本化审批、身份和恢复事实，保持完整BUSINESS v2；不改数据库表、公开API或UI。该实现属于后续阶段，不混入242。仍9次实际FULL、0完整业务通过；CSV排队，194/234不再恢复、221不自动恢复。两项目验证完成后按用户要求暂停并分别汇报APP占比和cache，再等待用户决定。
