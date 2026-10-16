# File Transfer 原234产品恢复236

2026-10-17，产品原任务恢复与独立恢复后核账均完成。原Task `5872e71e-8dcd-4353-9894-b9685e20f69c`已ENDED/FAILED，正式结清并原生归档，后续不再恢复234。原194不再恢复，旧221不自动恢复。本轮没有新FULL、业务验收或CSV部署；两个所选项目仍累计八次实际FULL、零完整业务通过。

## 唯一产品动作及来源

按当前会话真人对源码、样例、必要修正版、部署指令、观测审批证据及产品串行调试收尾的直接授权，唯一执行`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`。原Runtime为`agent-live-file-transfer-6x03gzuwk`，固定`originalTaskOnly=true`、`originalPreparationOnly=false`、`configureAgentRecoveryModel=false`，原JUnit等待30分钟；没有恢复其它任务、重新配置模型或新增能力探测。每个具体动作保持独立APP与新grant，秘密不进入模型正文，Root没有直接SSH。

产品复用原Runtime保存的DeepSeek角色路线。来源绑定234实际角色端点/model与SSH指纹检查、235原Mod target/config/layout投影和未变的post234 pair；它证明保存路线来源，不冒充当前provider/SSH可用性。当前身份及连接仍由产品恢复守门。

启动HEAD `5ba1a105b10a94aed8083853bb5db5405d36942a`、空索引；3310项实际源码、六JAR与234/235相同，源码清单SHA256 `30569190ae41ff690f56ebc901d7fb0397fd3a42d50161a629a8fc3fe3f4cf68`，deployment.jar SHA256 `1299e3225748d0019a60097a610990c4dbee0613e9812a5ea4069066dd050ca2`。父唯一offline实际退出0，核30部署组、3AI族、4平台族、5完整宿主及54个classpath入口、3210文件；234封存78文件、235封存58文件均逐项核同。本轮没有修改产品源码、样例、配置、DB版本或重新打包。

唯一恢复窗口为2026-10-17 00:37:53.3877088至00:39:18.8315093（+08:00）。Maven、before、after均实际退出0，指定JUnit为1通过、0失败、0错误、0跳过；原Task/Runtime及三个范围属性、实际JUnit JVM有效classpath均与冻结54入口吻合，前后source/target/JAR/CP字节一致。这不是全量测试或逐类CodeSource证明。未因等待另开进程或重复恢复。

Workflow原生恢复在非UNKNOWN、实际资源释放、原始身份/动作及持久收据等门禁满足时可调用settle并归档；本轮保留该既有行为。工具没有额外调用clearSettledHistory/archiveRecoveredHistory，没有手工删原历史或远端资源，也未新增DB备份或复制秘密。单凭JUnit通过或ENDED不作为结清依据。

## 独立核账结果

恢复后新数据库为5885952字节、SHA256 `f6bdacb46716418609cb53334949fb56eae999023a2145c438cffd48c6dd89a1`；保护引导文件358字节、SHA256仍为`db50510938e8b043fbdd7a3559eb0a0cec71c7993149f15deaf69cac4d8b71b6`，原空锁及创建时刻不变。库文件大小变化不是远端容量释放的度量。

独立reader沿用existing-only bootstrap、H2只读、既有原锁和前后pair散列模式，没有PlatformRuntime、伪造TaskContext、反射或schema会话切换。取得以下一致证据：

- 原平台活动task及resource均为0，存在唯一、未截断、精确owner/version/task的host归档日志，event为`PLATFORM_TASK_FAILED`，原结果ENDED/FAILED，结束时间与recorded_at一致。host归档按现有生产源码的严格framing有限核对，未调用私有TaskArchiveReader API，未把缺行当作成功。
- 原Mod task、event、execution均为0；存在唯一正式operationReceipt，task与operation均为原UUID，AGENT/static-live，outcome与resultOutcome均为FAILED，event为`DEPLOYMENT_AGENT_PREFORMAL_RECONCILED`。公开`DeploymentOperationReceipts.row/result`身份及payload校验通过，FAILED的公开business校验通过；proof SHA256为`36fe855c61652a5867f76fd1fbc32d6f0d27530dfa20efe9cf134d9b3d786710`。
- 原正式App、component、release均为0。没有发布成功或真实业务通过；原失败结果未转换为SUCCEEDED。独立reader不复读已归档的原controller快照，真实Task上下文校验仍属于已执行的产品恢复路径。

父把实际产品返回、host归档、资源0和公开Mod收据共同核对后确认原234结清；没有仅用effectsSettled或明细消失作结论。两次恢复后只读操作都保持新pair、原锁及源码/CP/JAR字节不变。

## 本地失败与验证边界

产品没有重跑。本地报告核验首次遇到Windows深目录长路径open限制：报告实际存在，保存失败脚本后仅给byte-open/XML读取加扩展路径，实际复核通过。恢复后首个reader只查平台活动表，在`EXACT_ORIGINAL_PLATFORM_TASK`守门安全停止，pair未变；原使用源码、外壳、输入和class均保留。根据现成TaskArchiveReader源码补active行0时的有界host归档读取，保留唯一性、身份、大小、时间、outcome及event约束，不放宽或改写结果。第二个reader实际退出0。

辅助准备检查包括236原32项、保存路线适配28项及PowerShell AST；post-reader原33项、归档分支受影响28项及PowerShell AST。它们是局部pure/源码边界检查；父另做两次隔离Java编译，均退出0，各两class。归档分支首次检查脚本引号错误已保存修正，同组通过；未机械重跑不受影响旧组。实际业务测试仅上述唯一恢复方法，没有全模块或全项目验证。

## 本次恢复用量及收尾

末100条诊断窗口有77条usage：按原Task、开始/记录时间、generation/attempt/purpose分出本轮13条APP，另64条与封存234逐身份/用量核同后排除。父独核全部77条原行含实际换行的SHA及时间归属。恢复已知input120053、output3924、cached input96256、miss23797，已知IO123977；缓存80.1779214180%，APP占已知IO100%，13条reasoning均未知且不另加output。旧234最后AI_TIMEOUT/UNKNOWN保持未知。完整generation、HTTP、账单仍UNKNOWN；这是纯恢复已记录子集，不混入两项目FULL趋势或业务完成后的APP讨论条件。

正常用户CIM最终核26组已观察PID及创建时间均退出，无强杀。只清本轮owner marker、绝对路径及无重解析核定的TMP/短UDS；原Runtime、正式收据和调查证据保留。产品、父授权及reader执行开关均撤回false，消耗前状态另存；没有后台验证服务或浏览器页。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-original234-reconciliation-236/`；主要入口为baseline、tools/offline-binding236、唯一run的result/reports/before/after/closure、post-baseline236、post-ledger两次输出及repair02、usage/REPORT、parent-product-terminal-review236、parent-final-observation236及parent-final-process-verification236。产品result SHA256为`4611a1f8b866151c3e9d7bea765404a0a3411c3409b2baf6e7860dbb9764544c`。

下一步依据已证“backend候选先准备、后续工具链两次容量拒绝”评估最小通用准备顺序修复。不能推测释放字节、保证下一次部署成功或降低隔离/保留/审批条件，也不盲开新FULL或CSV。最终仍为当前两项目逐个完成标准真实业务、浏览器和产品STOP/DISABLE后分别核FULL用量，再按原阈值讨论；最终同一冻结版本、全新服务器八项目验收目标未变。
