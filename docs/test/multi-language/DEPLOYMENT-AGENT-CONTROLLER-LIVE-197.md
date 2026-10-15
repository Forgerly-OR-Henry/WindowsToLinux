# 197：控制器部分真机正例通过，task-board归档核验被独立拒绝

新版task-board FULL产品验收为 **1 FAIL／0 ERROR／0 SKIP，764.702秒**。唯一运行已退出1，源码冻结检查退出0；目标 `985c3325-5dcd-4304-a899-800b36075cd6` 为BLOCKED。终止原因是有效的独立DENY／`DEPLOYMENT_COMMAND_REVIEW_DENIED`，不是审批混批、SSH超时、模型主动unable或预算耗尽。

本轮没有完成发布、前后端业务、真实浏览器或最终STOP／DISABLE。原拒绝、候选、资源占用和调查Runtime保留；本地进程退出不能代表远端资源结清。不从Goal BLOCKED推断底层Task最终outcome，也不重放原任务。

## 冻结与真实入口

2026-10-15 19:29:51—19:42:41 +08，唯一run `task-board-controller-197-01`，使用产品 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，仅task-board FULL AGENT／AUTOMATIC独立审批、remember=false。请求端口48920，未产生可验收业务URL。主部署LOW／审批NONE，普通输出32768／审批8192，必要的四项新Runtime能力检查保留；未因普通用量中断。

HEAD `300f252fcf002c0ac2146c589075460b450e2b36`，实际3307项源码与[196最终实现](DEPLOYMENT-AGENT-CONTROLLER-INSTALL-196.md)一致，deployment.jar SHA256 `36d4d9e72b10065c4f5b4942c7449fb70560b7f396d8c79033cb9d82ec2286e7`。运行前后源码、构件及冻结helper未变。复用已核task-board源码／部署指令／服务器观测和审批证据出站许可；密码、API key不作为模型正文发送。

实际SSH连接成功，指纹与用户重装后授权的 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc` 一致。四项模型能力检查成功，其未公开用量保持UNKNOWN。部署与审批模型自主识别来源、读取前后端/API/数据库配置、规划和取证；Root没有SSH、逐步提供部署命令或替模型裁决，没有并行其它项目。

## 196已经得到的部分真实Linux证据

失败发生在后续frontend候选归档核验，不能据此认定控制器未执行。原 `maven.log` 有以下实际序列：

| 阶段 | 独立审批和实际回执 |
|---|---|
| readiness | 第1345行APPROVE，随后原许可和SDK SUCCEEDED／exit0，第1348行完整 `AGENT_CONTROLLER_STORE` 为MISSING |
| install | 第1502行APPROVE完整116247单位stdin，随后许可／SDK0，第1505行完整READY，保留本task和原installOperation |
| JAVA prepare | 第1662行APPROVE完整8108字符脚本及typed binding，随后许可／SDK0，第1665行真实JAVA工具链回执 |
| bind | 第1745行APPROVE完整7988字符脚本，随后许可／SDK0，第1748行原binding摘要回执 |

版本为 `461e5c413deac61aa2d7a50237ad3638084775cb2a4bb37c05b4ebb58efe1d1c`，源码SHA仍 `2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19`／101904字节，安装operation `fd42fbc876154a119ce07f86618b4d31`。实际JAVA选择为21.0.12.1+1，归档SHA `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`；bind回执为 `1d0b0cef676f44af9a485e696414950dbf00e980b7bc7eddfff00724c8fc878c`。

Commands必须严格消费原MISSING／READY后才进入prepare；原Setup必须校验JAVA选择回执与bind摘要后才返回。因此这里既有实际SDK输出，也有后续严格消费证据，不只是APPROVE。它证明这一正常真机安装／调用路径，**不证明内核负例、并发／跨task复用、全部工具链或FULL业务通过**。

## 最后拒绝与待核边界

最后裁决callId `call_00_acotHQO5sTl8nFZ3QZwd7651` 为DENY。审批模型认为只读脚本对 `source.tar.gz` 只采集bytes／SHA／owner，没有在远端脚本中强制对比预期归档，也没有证明owner是受限项目执行身份；阶段为UPLOADED_ARCHIVE_BEFORE_AGENT_OPEN，先前准备效果尚未结清。

当前实际代码另有客户端消费约束：`DeploymentCandidateChannel.verify` 仅接受原Verification对应的SUCCEEDED、有value、exit0、未截断结果，并将**完整stdout**与原captured archive生成的expectedOutput逐字比较，包括owned、BUILD_ACTIVE=no、字节数、完整SHA及OWNER=0。不同即UNKNOWN。RemoteTools的UploadedArchiveEvidence绑定原能力、完整当前请求与目标，提供capturedArchive、consumer.expectedStdout、exactAction SHA及阶段事实。

这些当前事实已随固定首USER展现，短expectedStdout和SHA没有被折叠；不能把本次归因为“完全没有提供事实”。原Skill已有observer／client consumer区别，但本次最终审批没有自主激活它。归档的root管理身份与后续项目非root执行也需按真实调用边界区分。上述源码事实不撤销原DENY，不等于本轮已经证明所有归档安全，也不要求模型必须APPROVE。完整原wire未记录，不能冒称已逐字恢复全部出站请求。

198只读调查将核对是否需要澄清当前观察、已证明的客户端强制与后续执行边界；在此记录阶段尚未修改源码／Skill或重试付费验收。

## 实际用量与限制

按原平台generation id:number去重，90次已观测生成：部署16、审批74。

| 角色 | Input | Output | Cached input | Cache miss |
|---|---:|---:|---:|---:|
| 部署 | 736079 | 31707 | 584192 | 151887 |
| 审批 | 1143068 | 34201 | 795904 | 347164 |
| 合计 | 1879147 | 65908 | 1380096 | 499051 |

已知input＋output共1,945,055，审批1,177,269，**审批占60.5263%，input加权缓存73.4427%**。部署reasoning23376已包含于output；审批74次reasoning字段未知，不补零或重复加总。四项必要能力检查、完整物理HTTP和账单仍UNKNOWN，不付费补证。

本串行审批流按最终native submission callId归组，22组正好覆盖74个APP平台generation；其中21 APPROVE／1 DENY。permission summary另报告reviewedActions=21，保留其独立口径，不将两者混为相同计数。四个已识别控制器审阅组的已知平台用量为：

| 阶段审批组 | 生成数 | Input | Output | Cached input |
|---|---:|---:|---:|---:|
| readiness | 2 | 28178 | 1686 | 13184 |
| install | 10 | 305776 | 3497 | 145152 |
| JAVA prepare | 6 | 121525 | 6330 | 97792 |
| bind | 5 | 102420 | 2491 | 78720 |

此为当前串行日志中按裁决边界归因的**阶段审批组**，不包括主部署、其它共用动作及未知能力检查，不是完整安装成本或供应商传输账单。首次完整安装／readiness增加实际成本；不能将后续短脚本字节缩减直接换算为token收益。

本轮是发布前失败截面；[186完整历史PASS](DEPLOYMENT-FRESH-FULL-ACCEPTANCE-186.md)的82.9103%／68.6386%以及[194失败窗口](DEPLOYMENT-AGENT-EFFICIENCY-LIVE-194.md)覆盖不同，暖机和自主动作数也不同。不能据60.53%宣称196收益、完整业务或八项目最低／理想指标达标。

## 本地收尾与保留

唯一exec10423已终态exit1，不再poll或重开。正常用户CIM确认10组本轮PID／creation ticks全部退出；只清marker绑定的自有TMP／短UDS与本GUID私有加密凭据副本，176原pair字节不变。没有打开新browser页签。保留 `agent-live-task-board-sjncmxwjc` 调查Runtime、目标 `wtl-agent-task-board-sjncolob8` 及候选／占用，原数据删除0，没有通过整表删除或强制结清掩盖失败。

父独核真实XML／TXT、90个原generation、当前3307源码、冻结JAR及物理收尾，未重跑测试或付费补证。辅助计数曾把18个总解析类误当18个deployment类、把21个permission reviewedActions误当全部native提交；原错误保留，按实际15 deployment＋2 HTTP＋fixture，以及22提交／21 summary分别核正，均非产品失败原因。

原件根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-controller-live-197/`：

- 离线seal：`9c40cf4c7dee657f00c4c88b4f8b5307cd299990a1212c7093077dce64e7725d`；父final review：`b7580b2e63b50f3a7567e653b4111f42c47725e4d288075204a18975c569b4ab`。
- 实际run/sealed-evidence：`e447e9f78e6f16efc8defd53b0ba78a16c05634d2a933aa55a50bfead334a62b`。
- `parent-failure-closure197.json`：`e1dc22f82920814cd79cd7b11a65ae2ea0512eaa99f451402855be8585219aae`，独核18份实际run原件。
- `parent-controller-phase-evidence197.json`：`10174e7fed0195972d141fa2480a4a5f3587266c1a6e578d9826d2d44f0bcf65`，保实际MISSING／READY与四阶段审批组的原generation绑定。

本阶段只提交此失败及部分正例记录，不推送；保留既有混合源码修改。完整目标仍未完成：新版task-board完整业务、其余七项目、冻结后全新服务器八项目、非默认目录真实部署、整机重启恢复及原未结资源正式对账仍待。
