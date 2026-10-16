# 原238固定只读诊断修正与本地验证：260

260新增一个固定、版本化的产品只读诊断动作，供原GUARDED_ROLLBACK已FAILED/UNKNOWN且完整后观测仍未达到goal时使用。本轮完成本地定点验证和打包，没有运行真实provider、SSH、原数据库或远端诊断。原238仍UNKNOWN，不能据此认定File Transfer部署验收或收尾完成。

同名patch只包含捕获preimage到最终源码的11个路径增量：7个既有文件、4个新文件；8个生产路径、3个测试路径。迁移工作树的其它改动不在范围内，未推送。patch SHA256为`749ce1c7a1cfd6b56a69755fae349edfe2810f7b414231884edea8c4194d4306`，128283字节；全部hunk已对捕获preimage独立重建，Git numstat解析为11个路径。Git文本使用LF，物理源码字节另由manifest绑定。

## 产品合同

新动作`deployment.agent.publication-recovery-diagnostic.v1`使用实际Python源码作为stdin，通过`/usr/bin/python3 -I -S`执行。完整stdin经`commandEvidence.completeExecutableInput`交付独立APP；program facts仅引用该正文，避免重复发送。请求严格重建server、原task/execution、pending action/event及原帧SHA、plan、unit、owner、candidate、release、layout、新action、sourceVersion、resourceTargets和exactAction。每次仍须独立APP、当前revision、fresh单次host grant及持久DISPATCH，不接受调用方任意脚本或重用旧grant。

诊断只读取既有对象：只读FD和非阻塞flock检查原锁，检查候选归属、build/PID/cgroup、snapshot、maintenance、CURRENT/release及unit等当前事实；不调用会创建或截断锁的旧`application_maintenance`，不运行旧guard的前半段，不执行生命周期、回滚、cleanup、安装或文件写入。固定systemctl只读查询有单次及总时限、输出上限，超时仅结束本次自身查询子进程。对象读取和目录遍历有界，检查期间出现对象或名称漂移即UNKNOWN；第二轮unit/cgroup查询后再复核FD及名称稳定性。

回执采用封闭字段及固定27项检查，仅输出PASS/FAIL/UNKNOWN、固定reason、有限计数/布尔和摘要，无原文件秘密正文。`FULL_PARAMETER_VALIDATION`、`UNIT_RENDER_MATCH`、`MANAGED_DATA_LINKS`明确保持UNKNOWN。诊断不能恢复256当时已丢失的inner错误正文，也不产生“可安全重试”结论。`sourceVersion`是产品计算的实际请求stdin摘要、由请求参数绑定回执，不冒称独立测量远端已执行程序字节。

追加式`PUBLICATION_RECOVERY_DIAGNOSTIC` journal绑定当前原记录及完整前缀：REQUEST、REVIEW、GRANT、DISPATCH、RESULT、分段安全RECEIPT、END，或NOT_SENT/INTERRUPTED。不完整、拒绝、超时或无效回执继续UNKNOWN；旧FAILED/UNKNOWN ACK、completed、lastAction和安装事实不改。同一pending intent最多一次诊断尝试。本次选中诊断后立即UNKNOWN返回，不继续cleanup或结清。

已有诊断尝试不构成永久阻塞：以后显式协调仍保留原观察、goal证明和OBSERVED_ONLY路径。通用`runtime.tasks().reconcile`本身是有界协调入口，不能宣称是强制“只诊断”入口；若后续原状态改变或已有诊断，原守门路径可能执行新的观察、cleanup和最终核验，每个完整远端动作仍需独立APP及fresh grant。未知旧回滚不得重发，READY安装不得重装。

旧`DeploymentAgentPublicationRecoveryProgram`、loader、guard、global helper和installed version均未修改。旧program Java SHA256仍为`961328ecf83a0df607610edb8d188d7cb20ed41145c965d46c3d38e47dd3dbf9`；其默认产物及原OPEN身份沿用255已验证锚，本轮metadata核旧source/target/JAR完整归属族逐字一致，没有声称重新执行旧ProgramTest或远端安装验证。

## 实际验证

| 轮次 | 明确范围 | 原始结果 | 打包 |
| --- | --- | --- | --- |
| attempt01 | 7方法、3类 | 15例：7P / 8F / 0E / 0skip | 未执行 |
| attempt02 | 两失败方法及直接共用补偿记录读取路径的pending回归 | 11例：11P / 0F / 0E / 0skip | `-DskipTests package` exit0 |

首轮两个原因分别为：新诊断codec将相同JSON事件号的IntNode与LongNode按节点类型比较，错误拒绝相同绑定；本地FAILED结果夹具缺少OperationResult要求的Problem。仅修为规范JSON字符串相等及补齐本地`IO_FAILURE/SSH_COMMAND_NONZERO` Problem，保留全部来源、身份、请求、目标、审批及grant校验。首轮完整XML、日志、result及源码manifest均保留，immutable首JUnit锚SHA256为`0eba2a64012fabb88dd9b1ada82b3f3983480163c8032a82fea98f655f461375`。

最终7方法15例全部通过，原始两轮26例为18P/8F/0E/0skip；不能把raw失败改报为一次全绿。两轮sourceFreeze均0，未运行全量测试。方法包括Archive中的`readonlyDiagnosticRejectsChangedOriginalBindingAndReusedGrant`、`failedRollbackDiagnosticEndsUnknownWithoutMutationOrAutomaticReplay`及`pendingPublicationRecoveryNeverResendsUnknownMutation`，Evidence中的`readonlyPublicationDiagnosticDeliversCompleteSourceAndRequiresFreshHostGrant`，DiagnosticProgram中的`fixedReadonlyRequestReconstructsEveryBindingAndRejectsChangedExecutable`、`boundedDiagnosticReceiptRejectsReplayAndNeverCompletesUnknownChecks`、`originalPythonDiagnosisStopsOnBusyAndMarksCurrentDriftWithoutMutationCalls`。

Archive使用真实原TaskWork/journal/H2夹具，APP模型、host和RemoteSession是本地能力替身；Evidence是本地native模型协议夹具。Python测试调用真实diagnose函数，但OSProbe、systemctl、flock边界为替身，结合AST检查写入调用。以上不证明Linux syscall、真实APP审阅、host registry/SDK grant消费或远端故障状态。

最终source inventory为3323项，manifest SHA256 `5eb223372f680068188312ca6ee8c7b7b1c71bc9b87bf06308bd53a279ac037f`。真实metadata工具exit0：51个部署归属组、3个AI族、4个平台族、5个完整宿主模块；54个实际JUnit classpath入口、3258文件。核对的是source/target/JAR与实际JUnit路径字节，不是JVM CodeSource。

deployment.jar为3954103字节，SHA256 `f6fba9e3669dcdff4b9039212fe7bb6dd761c15e76c3c31b9fc4c932b24c69d5`；五个宿主JAR未变。metadata SHA256 `e96baa97ce29b35b3b84e1e29828b515b5f20d53c171974407829d461cd4c213`，classpath SHA256 `5cc1a34ec7557019414708895b92dc540dd5cac1218bf16ca8a0b26aeac6597f`。实际live fixture仅编译，没有执行业务。

两轮normal close均exit0，分别核13/19个精确PID及创建时间pair退出，删除各自2个有标记TMP/UDS目录；父独立normal CIM分别核14/20个pair全退。metadata运行亦已正常结束。最终合并进程核验、开关消费、提交与封存标识由本轮delivery及CURRENT承接。

## 后续边界

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-readonly-diagnostic-260/`。真实后续协调必须另做fresh字节及原Task状态核对，不因本地通过自动恢复其它任务。194和234已结清不恢复，221不自动恢复；原238的pending、FAILED/UNKNOWN ACK及五项保留资源仍需按真实证据处理。

当前历史FULL仍为File Transfer八次、CSV Inspector历史208一次，完整BUSINESS为零。本轮本地模拟usage不进入真实provider统计。按原目标先完成File Transfer BUSINESS v2、真实浏览器和必要产品收尾，再串行完成CSV Inspector。二者完成后暂停，分项目汇报FULL审批I/O占比`APP IO/(DEP IO+APP IO)`及缓存命中率`hit/input`、known/unknown；失败FULL和纯恢复分别列出，等待真人决定方向，不自动启动第三项目。
