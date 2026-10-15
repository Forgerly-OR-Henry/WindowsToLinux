# 184：新服务器完整 task-board 验收失败，保留独立审批拒绝与 UNKNOWN

本轮通过正式 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，仅选择 `task-board`。结果为 **1 FAIL、0 ERROR、0 SKIP，1104.861秒，Maven exit1**；Agent `95e595d7-5bee-49d0-ba5a-7de2c3fb9763` 为UNKNOWN，目标BLOCKED。尚未发布业务，未执行真实浏览器验收或产品STOP/DISABLE，不能表述为完整部署通过。

本轮实际SSH连接及DEPLOYMENT、APPROVAL各自TEXT/TOOL四项必要能力检查全部成功。183独立TEXT结果没有导入本轮新Runtime的可用状态。本轮FULL AGENT/AUTOMATIC、main LOW、APP NONE、普通32768/APP实际请求不超过8192，原需求、输入桥接、浏览器检查点及独立新grant接线保留；没有接手服务器部署、重放旧计划或并行其它项目。

## 实际停止原因

部署模型完成源码识别与初始计划，进入工具链准备；期间多条受审批命令和产品传输成功。最后停止于**独立审批模型拒绝NODE 24工具链prepare动作**，不是SSH超时、审批输出预算耗尽或模型主动unable。原记录事件为 `DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED`，诊断ID `bf60e0af-dd90-4066-981a-a4520b0f7f7d`；随后记录 `DEPLOYMENT_AGENT_FAILURE`，公开结果 `DEPLOYMENT_AGENT_STOPPED`，原任务保留UNKNOWN。

审批理由认为：固定 `/usr/bin/python3 -I -S -c` 引导直接执行101904字节stdin控制程序，未经过managed-helper/build-entry协议16的强制完整性与隔离边界；该root路径会创建8GiB工具链镜像、格式化/挂载、下载解包Node并调用systemd-run。审批承认内部校验详尽及resourceTargets无漂移，仍因调用层边界而拒绝，无需用户确认。

这是**原模型给出的拒因**，不是已确认的安全漏洞结论。此前JAVA 21同类控制程序曾获APPROVE，也不能据此否定本次拒绝。平台自有工具链控制面与业务构建helper的契约适用范围、实际可达完整性/隔离检查，以及拒绝后原目标状态的传播仍须只读审查。不得改写原拒绝/UNKNOWN、自动重放，或通过要求模型批准来绕过独立审批。

## 运行代码与原始证据

入口HEAD `e3b3d7cfde085c4d4ab9d0f449db77d39eb5b785`；3299项源码前后均与182 after相同，manifest SHA `464a949dfa5e276e5544e18127ea575524452ec3e76724a7449cdbb3ccb193d3`，source freeze exit0。`deployment.jar` SHA `a5c72b9eac6735962b57ad6608309f08f94c089438c85e7862181d74c268bf65`，宿主AI JAR SHA `a7d283d6bd8fa23ad415d9ef8729e7e6269ee60cf8845f65b87a478760119353`，验收fixture class SHA `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421`。

39项宿主classpath的1041个实际文件前后冻结。原Surefire XML SHA `c3c85bf5dc4c895910458fda551a38c8907bdb244bcf481fccd8648f919e720d`，其中55项实际记录classpath按顺序解析到本轮两个新HTTP类。该证明属于记录的classpath解析与冻结字节，不冒称在FULL JVM内新增了Class.getCodeSource观测。

原日志、报告、授权、逐轮usage及关闭证据保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/fresh-full-business-live-184/`；本轮 `task-board-fresh-184-01/sealed-evidence.json` SHA `84b1ab2c935422a6fd3fe972a93c085a7ac9f78e1e8a16fbbed22191dcb6ec8d`，原失败与closure=true同时封存，不将后续调查写回原记录。

## 用量与收尾

观察到159个唯一平台generation attempt绑定：部署10、审批149；这不是全部底层物理HTTP请求数。已知input **3,581,177**、output **74,631**，合计 **3,655,808 Token**；reasoning为output子集，不重复加总。已知cached input **2,030,592**，已知行缓存命中 **56.7018%**；审批已知input+output **3,299,245**，占已知总量 **90.2467%**。审批reasoning字段未公开，不填0；四项能力探测的完整usage、底层物理次数及整轮账单仍UNKNOWN，不付费补证。这是失败调试轮，不是最终八项目指标，也没有因中间用量而中断运行。

正式收尾正常CIM核本轮10组自有PID与creation UTC ticks全部退出；精确marker绑定的TMP/短UDS及本轮新GUID加密凭据副本已清，原176源pair未变。只读sealer通过，closure=true。Runtime根 `src/app/db/data/agent-live-task-board-kkipjhpl0`、源码、预分配应用标识 `wtl-agent-task-board-kkipl586c`、UNKNOWN及远端资源调查证据保留；预分配标识不证明已完成App。没有按目录名删除远端资源，也未删除原178或其它旧调查。

后续先审本次具体拒绝及可执行证据链，再决定最小修复和定点验证；完整task-board、其它七项目、冻结新机八项目、非默认目录及整机重启恢复仍待验。
