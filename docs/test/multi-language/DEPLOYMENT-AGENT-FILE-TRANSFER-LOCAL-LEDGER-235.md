# File Transfer 原234本地账本观察235

2026-10-17，完成原Task `5872e71e-8dcd-4353-9894-b9685e20f69c`的有限本地字节只读观察。原任务仍为UNKNOWN，没有终态、execution或正式App记录。本轮没有模型调用、SSH、恢复、远端清理或新FULL；两个所选项目仍为八次实际FULL、零完整业务通过。原194不再恢复，旧221不自动恢复，CSV Inspector继续排队。

## 观察方式和实际验证

既有`inspectsOriginalTaskEvidenceWithoutServerDispatch`虽不派发服务器操作，仍会调用`PlatformDatabase.open`并执行锁/initializer写入；PageService本地查询也会创建查询task。为保持原DB字节不变，本轮仅在辅助目录准备有限reader，复用232的existing-only bootstrap、原既有空锁、`IFEXISTS=TRUE;ACCESS_MODE_DATA=r`及`c.isReadOnly()`模式，不启动PlatformRuntime或initializer，不伪造TaskContext、反射或同包绕过私有API。

父重新捕获当前HEAD `7d9f34874f0e9fa9d40b38f34a515cad63920b17`及空索引；3310项实际源码、六JAR、54个classpath入口及3210文件与234逐字节核同，沿用已验证的30部署组、3AI族、4平台族、5完整宿主链。234封存78文件逐项核同，seal SHA256为`ff16732b6964e90460ada287bd7d8d85f3cef079965cbc76b75b348cf13e5996`，未改写旧证据。

唯一Runtime为`src/app/db/data/agent-live-file-transfer-6x03gzuwk`。观察前后数据库均为66420736字节、SHA256 `40fd4201cb4122411b0e25035eefb293ac4559481a95a93743ce859e582f62a9`；保护引导文件为358字节、SHA256 `db50510938e8b043fbdd7a3559eb0a0cec71c7993149f15deaf69cac4d8b71b6`。既有空锁的路径、创建Instant、零长度和散列均绑定。解保护仅在正常用户本地existing-only bootstrap内部进行，凭据不输出、不进入模型正文。

Sol High准备工具、Sol XHigh独核SQL/API和checkpoint规则，父独读源码及外壳。初版31项有限源码/词法检查及PowerShell AST通过；这不是Java AST或运行证明。父以`-proc:none -implicit:none --release 21`独立局部编译，退出0，精确绑定主class和匿名class两个产物。未运行Maven或旧业务测试组。

首次`local-ledger-235-read-01`实际退出1：读取第1025条原资源明细时触发1024上限，保留1024条安全局部输出及`BOUNDED_ORIGINAL_RESOURCES`原失败phase；锁内、关闭后及外壳复核pair字节均未变。未把局部输出称作完整观察。原使用的源码、外壳、模板、输入和两class原字节全部保留。

最小修正保留原上限，先对全部原资源按kind/access/released聚合数量，再仅投影未释放资源或原owner、CUSTOM、精确controller checkpoint前缀的明细。所有选择参数绑定，不读大量已释放payload；明确未投影数量及选择范围，不伪称全部资源明细完整。23项受影响局部检查及PowerShell AST通过；其它方法逐字核同，没有机械重跑原31项。父重新局部编译退出0并绑定新class，唯一`local-ledger-235-read-02`实际退出0，前后pair、源码、完整CP和JAR均核同。

## 取得的账本事实

- 平台原任务UNKNOWN，outcome、endedAt均为空；安全问题码为`TASK_EFFECT_UNCERTAIN`。Mod原任务也为UNKNOWN，semantics为4、AUTOMATIC、remember=false，纯公开WorkspaceBinding验证有效且没有续接绑定；完整身份find仍需真实ModDatabase上下文。
- 全部原资源4078条，其中4073条有释放时间、5条没有。所选明细9条，包含未释放的SERVER/MOD/SCHEMA各1及FILE 2，以及4个controller checkpoint；另4069条已释放资源只计数、不投影明细。该数量不能推算物理HTTP、模型费用或远端空间。
- 原Mod事件124条，完整落在本次有限边界内；公开生产纯函数给出`effectsSettled=true`。这只证明相应动作事件有结果配对，不能替代资源释放、远端不存在或归档授权。
- 四个controller动作均有单一原RESULT，payload SHA与对应checkpoint原字节相符，原lease均有释放时间；两次READINESS原state为CONFIRMED，两次PREPARE原state为FAILED且precreation proof存在。完整ProgramState与precreation生产校验需要真实原TaskService/TaskContext或非公开validator，本reader保持UNKNOWN，正式released判定为null；未把结构、时间戳和摘要相符冒充正式校验通过。
- 三次plan只有一条实际PREPARE链，属于backend。其候选配额为2281701376字节、保留政策1073741824字节；后续backend配额未变。web计划从1207959552降至134217728字节，但没有web候选准备，不能把计划差额当作空间释放。
- 原Task的execution为0，预期App `wtl-agent-file-transfer-6x03iogb4`的正式App、component、release均为0。这不能证明远端backend候选已消失，也不证明业务成功。

配额字段是按当前生产事件结构的有限RAW投影，未自造quota validator或Selection。捕获布局通过公开纯decoder、摘要相符；历史Task上下文校验仍留给产品恢复。未刷新远端容量或推测可回收字节，234终态的直接原因仍是已核实的AI_TIMEOUT/UNKNOWN。

## 收尾与接续

两次reader、两个launcher及fresh capture共5组已记录PID/创建时间经正常用户CIM确认退出，无强杀；局部javac均同步实际退出。本地reader执行开关已撤回false，消耗前binding另存。没有删除Runtime、原数据库、源快照、远端资源或未结清证据；编译产物与失败输出作为观察证据保留，无后台验证服务。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-local-ledger-235/`。主要入口为`baseline235.json`、`parent-compile235*.json`、两次run的stdout/closure/process-observed、`tools/repair02/REPORT.md`、`parent-observation235.json`及`parent-process-closure235.json`。本轮未修改产品源码、样例、配置或重新打包。

下一步限定236产品原234 reconcile：在真实原Task身份下正式验证四个controller checkpoint、原结果、捕获布局及所有权；门禁满足后再调查并补偿精确backend候选与相关资源。每个具体动作继续独立APP和新grant，任一冲突保留UNKNOWN；不重放工具链准备，不操作194或旧221，不盲开FULL/CSV。现有证据支持先做该产品恢复调查，尚不足选定容量行为修复或保证下一次部署成功。最终八项目、业务标准及缓存/APP阈值保持原目标。
