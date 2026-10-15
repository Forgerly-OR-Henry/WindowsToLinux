# 194：审批优化后的 task-board 真实运行失败，原用量与UNKNOWN保留

本轮只通过正式产品入口执行一次 task-board FULL Agent 验收：**1 FAIL、0 ERROR、0 SKIP，893.422秒**；Maven exit1/source freeze0。独立审批模型把 `skill` 与 `submit_result` 放入同一原生批次，触发既有 `VERDICT_BATCH_MIXED` 校验，任务保持 UNKNOWN、目标BLOCKED。没有发布成功、真实业务或浏览器通过证据，没有产品 STOP/DISABLE 回执；不将本轮计为验收通过或优化达标。

## 绑定与实际经过

入口为 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，run `task-board-efficiency-194-01`，HEAD `df03cded4e0b48fc9fabc91b451f7e18cb1a6ca4`。复用193最终3299项源码与JAR，执行前后逐项相同；没有在运行中改源码、构件、索引或七个验收helper。

最终 `deployment.jar` SHA256 `d7c8c5cfa4b17779814878cfdd31c242be4aade783f80c1cc99633486c1a5de3`。实际39项宿主classpath/1041个文件均封定；辅助noinit核六个关键类来源，原JUnit记录的实际classpath也解析到相同六个冻结类。它们是文件与实际classpath解析证据，不冒称在产品JVM中新增了CodeSource探针。

沿用真人已确认的新主机指纹及task-board 21文件/91,874字节发送至当前DeepSeek的具体许可，执行前再次核原证SHA；密码/API key不作模型正文。正常用户私有ACL副本仅从176新正式受保护配置取得。主部署LOW、独立审批NONE，普通输出32768/审批8192、AUTOMATIC/remember=false；未因普通累计用量中断，没有自动重试或手工SSH部署。

SSH任务 `805cca44-d840-4526-a1fe-50a8052ac125` 成功，实际指纹匹配 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc`。本轮新Runtime四个必要能力检查分别为部署TEXT/TOOL_CALLING、审批TEXT/TOOL_CALLING，全部成功；原检查不能替代它们，其用量仍未公开取得。

Agent任务 `a67e3b89-d04e-49a7-8b5e-20ed93803d53` 自主准备工具链及构建；最后停在“核验封存制品”。末次generation `3a4758c3-39b6-4093-b6eb-e7ef1a5b63ba` 返回两个调用：

- `call_00_rIfnlzB3Pyx2H9r2l4uC8893`：`skill`。
- `call_01_fV961EJwHk9vANd8I0qz3196`：`submit_result`，模型提出APPROVE。

`DeploymentAgentApproval` 在任何批内取证处理及裁决返回之前，发现包含裁决的批次大小不为1，抛出 `VERDICT_BATCH_MIXED`。因此该批中的Skill未激活，APPROVE文本未转为有效授权，最后动作没有因这份无效裁决取得grant或执行。它不是有效DENY、SSH超时或预算耗尽；不以放宽裁决门槛解决，也不改写本轮UNKNOWN。

原公开结果 `DEPLOYMENT_AGENT_STOPPED` 表示需要原任务核查；任务保留source snapshot与“Business result requires explicit reconciliation”占用。Runtime关闭还记录了任务仍持有Mod资源，作为未结证据保留，不能因本地JVM已退出而声称业务资源全部结清。

## 本轮真实用量与可比性

按唯一generation ID/attempt去重，**113条平台generation attempt：主部署19、审批94**。这不是113次部署、113个独立动作审批或完整物理HTTP请求数。原记录没有绑定冲突，已观测预算违规为0。

| 范围 | input | output | input+output | cached input |
|---|---:|---:|---:|---:|
| 主部署 | 916,417 | 33,952 | 950,369 | 707,072 |
| 独立审批 | 1,603,455 | 37,356 | 1,640,811 | 1,041,536 |
| 合计 | 2,519,872 | 71,308 | 2,591,180 | 1,748,608 |

本轮失败窗口审批占比 **63.3229%**，输入加权缓存命中率 **69.3927%**；主部署缓存77.1561%、审批缓存64.9557%。113条缓存字段均完整且满足 input=hit+miss。cached已包含在input中；主部署reasoning 26,189包含在output中，不能重复相加；审批94条reasoning未知，不填0。四个必要能力检查用量、未观测物理HTTP及完整费用仍未知，不付费补证。

186完整成功轮为主部署30/审批417、总8,182,737 token，审批82.9103%、缓存68.6386%。194在发布前失败，服务器已暖、自主实际动作及业务覆盖不同，不能用63.32%对82.91%的差值证明193效果，不能将总token减少归因代码。没有达到完整八项目、审批≤20%或理想缓存≥80%的结论。

本轮大stdin确实继续使用8,000码元分页，存在原生多工具批量和主动复读。固定前缀、短正文预置只覆盖部分开销；后续先按新授权处理批量提示、Skill正文重复和源码/回执导航，不把机械四页批量当作充分答案，不扩大窗口或新增完整正文工具。

## 工具核查、保全与收尾

194离线工具准备发现仅按ParentProcessId递归会因Windows PID复用误认19个更早的浏览器/扩展进程。它们均未终止或触碰。仅本轮sampler补上root精确PID/creation及子进程不早于root/当前parent的时间链，四个公开正反例及直接AST/preflight通过。原错误归属与首preflight保留；原源码/JAR/noinit有效证据复用，没有重新付费或重复打包。

原exec17107已exit1，不可再poll或重开。正常用户CIM正控制核本轮10组已观测PID/creation UTC ticks全部退出；自有marker与边界绑定的TMP/短UDS及私有GUID凭据副本已清，176源pair未变。保留Runtime `src/app/db/data/agent-live-task-board-q3k1ohhos`、目标身份 `wtl-agent-task-board-q3k1qalz8`、原日志/XML及UNKNOWN/远端候选和占用证据；没有根据名称或路径删除业务资源。本轮未打开浏览器页签，未声称STOP/DISABLE完成。

原件根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-efficiency-live-194/`：

- 最终离线seal `sealed-final-offline194.json` SHA256 `81f707a454885d98a0771307872813c696c0859637799dde489ed155aec27d62`；父核91个原件、源码与构件，`parent-final-review194.json` 为 `f65dce0372e0a1529df72c3b6925f427ef10c386139fcf4d6c0691a38e31fefe`。
- 原run的 `maven.log` 为 `706ac57655e2414c0c8a7ec8d2488ae249c91c351ecda94cfcfc4beefa2c95c7`，JUnit XML为 `7978423b558d56e449ead9ef51ff3c7601a814b95b62f08d54a70ea50960ec19`。
- 原run的 `sealed-evidence.json` 为 `cd322321cb5a4c749e35fd254aabe9892bce86d0c3f2a1b8a02e8fdfc4623205`；父另按原113条记录核用量、六类实际classpath及失败/收尾，`parent-failure-closure194.json` 为 `ac79e9412bfd7399167979723931e21704f79e42940a0be2610786ad28109239`。

下一阶段195仅实施已授权三项并做定点交付，不自行追加付费部署；固定工具链控制器版本化安装与短调用作为后续方向另行核实安装、完整性、布局、资源生命周期与恢复路径。186原完整成功证据仍有效，但整个目标、最终冻结新机八项目、非默认目录与整机重启恢复均未完成。
