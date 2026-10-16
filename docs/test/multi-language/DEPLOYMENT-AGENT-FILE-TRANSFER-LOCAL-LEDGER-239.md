# File Transfer原238只读账本观察239

2026-10-17，唯一原Task `072bc074-4bce-4c14-b425-f2ff965c7d40` 的本地只读观察成功。原任务仍为UNKNOWN，问题事件为`DEPLOYMENT_GRAPH_EFFECT_UNCONFIRMED`；发布执行未结清。没有调用模型、SSH或产品恢复，没有新FULL或CSV。两个所选项目仍累计九次实际FULL、零完整业务通过。

## 来源与观察边界

在正常非管理员PS7.6.5上下文重新读取原Runtime `src/app/db/data/agent-live-file-transfer-8pqtfa76o` 的字节：DB119705600字节，SHA256 `6d7d05e4d326cf1c087062aa882b4cb8734608641904e36db1b403d421990a29`；bootstrap358字节，SHA256 `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。原空锁大小、创建时间和SHA前后一致。

实际HEAD为`49238e137af8430966f114a2b00a413dac745ba1`、索引为空；3310源码manifest `5d53bf56e2c9a024ae33a9a2fbee18d1eb3efe44e823db921f518e97d2238f97`、六JAR、54个有效classpath入口/3210文件与237包链逐字节核同，238封存72文件全核同。没有修改生产源码、配置、样例或包。

工具复用235最终只读入口及236有界归档观察方式，仅绑定原238身份并增加发布提案安全投影。33项受影响静态检查及PowerShell AST通过；Java检查中的括号平衡不称AST。父独审后以`-proc:none -implicit:none --release 21`及空sourcepath隔离编译，实际退出0，两class已绑定SHA。唯一`local-ledger-239-read-01`正常退出0、stderr0；没有Maven或业务测试。

读取只使用已有bootstrap、原空锁、H2 `IFEXISTS=TRUE;ACCESS_MODE_DATA=r`，并检查`Connection.isReadOnly()`。没有PlatformRuntime、initializer、写事务、schema切换、伪造TaskContext或私有API反射。bootstrap只在正常产品保护接口内解保护，不输出密码或API密钥；request/recovery/config不整段输出，载荷有界且只投影白名单身份、大小和SHA。持锁及关闭后原pair再次核同。

## 实际账本

平台及Mod Task均UNKNOWN，终态outcome/endedAt为空；Mod语义4、AUTOMATIC、remember=false，纯公开WorkspaceBinding校验合法且无successor。正式identity查找仍需真实ModDatabase，未冒称调用。

全资源7532行，其中7527行存在released时间戳；完整选择明细7行，为未released的SERVER/SCHEMA/MOD/两个FILE，以及两个原controller检查点。另7525条已released资源不展开payload，不能称所有明细完整读取。两个controller原状态分别READINESS/CONFIRMED、PREPARE/FAILED，原RESULT payload SHA与checkpoint字节吻合；正式ProgramState/原创建拒绝验证需要真实TaskContext，仍UNKNOWN。未见选定的publication graph/unit检查点，不据此推远端没有发布效果。

原188个事件完整处于10000行/16MiB界内，公开生产`effectsSettled`返回false。原execution `45e3a9a4-a6b8-403a-89f5-605e11eeeca0` 为EXECUTING/outcomeUNKNOWN，businessCommitted=false、cleanupComplete=false；request4658B、recovery569B、result450B。真实公开ExecutionStore.row及PublicationTemplateCodec校验受支持结构和语义通过，均不是远端效果或资源结清证明。

原提案的两个unit均指向static-live及原captured layout。web release为`3d7b9d72bfad9476a1d121b34734a63a1330e4f83d404bcb27f187a5b87075a1`、owner为`62d79abaf4ce79db838ca40555437652e75c5b5bf299b874eba415a76cd0f3dd`，与238最后被拒命令参数一致；backend release为`39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597`、owner为`3ebea9b3ba5ccef97fb19ffc898b73d43e57c57e02fb8886092f3544ed3059e2`。这证明原提案保存的角色值，不冒称本工具重算了release派生公式或观察到远端owner。

提议App `wtl-agent-file-transfer-8pqtgvbwc` 的本地正式managed_application/component/release均0，原Task正式operationReceipt为0。没有正式App可供补业务验收，但缺行也不证明远端无候选、无发布或可以删除。原Runtime和全部FAILED/UNKNOWN证据继续保留。

## 收尾与下一步

父收尾脚本首次比较空锁时间时因PowerShell默认把JSON ISO字符串转换为DateTime而被守门拒绝；原始字符串与实际创建时间核同，大小仍0。保留失败脚本/证据，仅改`ConvertFrom-Json -DateKind String`后收尾通过，没有重开DB或reader。正常CIM最终独核四组记录PID及创建时间全部退出；javac同步等待退出0，无强杀、后台验证服务、浏览器或业务HTTP。reader执行开关已撤回，消耗前绑定留存。

下一步只补真实发布作用域的APP身份角色和派生关系证据，并按原任务身份由产品正式协调238；仍保持缺失/变化拒绝、每完整动作独立APP和新grant，不替换摘要或自动判准。原194和234不再恢复，旧221不自动恢复，CSV继续排队，不盲开第十次FULL。File Transfer与CSV Inspector真实验证和必要收尾完成后，按用户最新要求暂停、分项目汇报审批占比及缓存命中率，再等待方向决定。

证据根`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-local-ledger-239/`。入口为baseline239、parent-reader-review239、parent-compile239、唯一read01的stdout/closure、parent-final-observation239及parent-final-process-verification239。reader stdout SHA256为`00468aaad4ee5670360fb1082e1c4d749948fa863f449f280deda51fe68ba21d`。
