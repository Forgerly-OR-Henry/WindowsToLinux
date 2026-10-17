# Agent 根设备与文件系统的只读布局观察 294

293实机已证明四个标准Go路径均不存在、五个真实部署目录同属253:1文件系统、当时可用约1.76GiB。现行隔离要求禁止共享SDK安装，现有配额也容纳不下完整标准Go发行档。为先形成具体容量方案，本次只补充文件系统、根块设备及其祖先/分区几何的只读事实，不启动FULL或磁盘变更。

仅新增三个测试路径共425行，生产源、293公开resolver和已通过方法未改。Live fixture新Task先核293观察SUCCEEDED归档，再显式capture当前task layout并读回stateRoot。公开snapshot同时包含293 resolver定义和294 observer；实际脚本不运行293原main。原生SourceBrowser、AgentApproval、ReviewRouter绑定完整脚本、空stdin、真实hostkey/layout/task，经一次独立APP及新精确grant在同一request/session执行一次命令。确定同task的APP拒绝保完整脱敏理由并返回FAILED；不把execute未知改成未发送。

stateRoot fstatvfs保存两点total/free/available、FS身份和只读状态。根设备限定为已观察的253:1；固定lsblk/findmnt读取安全字段，唯一完整祖先链才可选physical disk；仅现有可信sfdisk对该设备执行一次--json。工具缺失、关系含糊、缺sectorSize等保持UNKNOWN，不安装工具、不猜512、不输出UUID/serial/options或原始工具正文。root:disk0660块节点只用O_PATH读取身份元数据，不套SDK普通文件权限规则；固定工具和路径前后核身份。信任现有SSH/OS启动基线，不声称验证了OS内容完整性。

各工具10秒、stdout+stderr合计最多65536B，拓扑32节点/depth8；超时/超量仅终止本次仍存活的进程组并确认退出，已回收leader不会因JSON解析失败再次kill，清理不确定则停止观察。容量和几何严格拒负值/溢出/重复或尾随JSON；未知不伪零。uncoveredByListedPartitionBytes包含保留区和元数据间隙，不等于可分配空间，也不证明任何grow操作安全。

唯一local selector `DeploymentSdkMetadataObservationTest#blockFilesystemObserverBoundsVendorMetadataAndPreservesUnknown`，test01/session97944 actualexit0；1P/0F/0E/0skip，JUnit1.449s、Maven13.645s，UTC2026-10-17T17:52:07.5293974Z—2026-10-17T17:52:22.0436624Z。只test及必要testCompile，不package/全量、不重跑旧组。覆盖有界JSON/拓扑/几何/隐私投影、动态FS容量、root:disk元数据及UNKNOWN；Windows合成环境没有运行真实Linux工具。14项直接源文件和4253951B生产JAR在测试窗口属性不变。自有Java0/terminated0，c294a/u294t已按owner/绝对边界/no-reparse清理。

before、原始source.diff、REPORT、runner和结果在`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-block-filesystem-layout-observation-294/`。原辅助diff统一CRLF导致与LF测试资源不匹配；正式patch按原始before生成并规范LF，reverse check已过，未改受测源码。仅提交本说明、窄patch和限定sha256清单，提交阶段对3source+patch+本说明共5项计算摘要；保留其它迁移和AGENTS，不推送。

实机尚未执行。唯一live selector `DeploymentStaticLiveAcceptanceTest#observesBlockFilesystemLayoutThroughNativeApproval` 仍需独立APP/新grant；没有SDK执行、PROBE、安装、resize、format、迁移、购买或layout变更授权。194/285/原289/新291已结清不恢复，旧七UNKNOWN保留。File Transfer完整验收仍0、CSV未开始；两个完整BUSINESS v2/真实浏览器/STOP-DISABLE后暂停报告的目标不变。
