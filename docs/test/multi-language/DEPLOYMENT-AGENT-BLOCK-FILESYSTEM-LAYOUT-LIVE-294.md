# 只读根设备布局实机观察 294 及容量决策

代码交付9515ef525c63d7b20c1f466e0f938c77ee12faea，本地新增定点1P/test0，未重打生产JAR。随后唯一block-filesystem-layout-01/session97511实际exit0，JUnit1P/36.247s；UTC2026-10-17T17:55:38.2973014Z—2026-10-17T17:56:19.7518708Z。新Task3a780412-a9bb-474e-917f-810456beec7d；独立APP动作1、新精确grant a1f7bcf7-6a29-49a9-866e-1a6efea25439、实际命令1。原293成功归档已在connect前核实；完整公开脚本经原生审批后同一session/request执行，SDK执行0，无磁盘/布局变更。

captured stateRoot为`/var/lib/windowstolinux`，属于ext4、mountpoint `/`、设备253:1、fsid0xa757ef6032008aae，readOnly=false。两点frsize4096、blocks7574288、bfree466199、bavail462103均一致；报告总量31,024,283,648B、free1,909,551,104B、available/min1,892,773,888B（约1.76GiB）。totalBytes来自statvfs，不把分区与该数的差额称作可增长空间。

实际lsblk拓扑返回vda1（253:1，part）32,095,845,888B，祖先vda（253:0，disk）32,212,254,720B（30GiB）。磁盘总量减该根分区尺寸仅116,408,832B（约111MiB）；这只是根分区以外空间的尺寸上界，不证明连续、未分配或安全可用。没有扫描其它磁盘，因此不能宣称全机没有其它容量。

partitionTable保持UNKNOWN。真实inverse工具树末节点仍有parentKname=vda1，与本次可选分区几何入口的保守唯一盘条件不匹配，未调用sfdisk；不据此推断sfdisk缺失、无其它分区或任意resize安全。当前磁盘尺寸上界已不足以支持“现有根盘外还有数GiB可直接扩展”的假设，本阶段不为可选几何分支再发观测轮次。

本轮七次物理APP调用input129894、output1937、已知IO131831、cache hit106368、miss23526；已知IO审批占比100%，输入加权缓存命中率81.8883089288%。七次IO/cache全知、reasoning未知，完整provider账单未知；无额外记账调用。这仅为本次纯诊断指标，不是File Transfer/CSV成功指标，不与17次历史失败FULL及恢复混算。

14项受影响直接源文件和4253951B JAR属性在本轮后仍与本地验证一致。CIM自有Java0/terminated0；按owner/绝对边界/no-reparse检查清b294a/u294b。本地test的c294a/u294t此前已清。Runtime、原始日志、APP事实和metadata均保存在`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-block-filesystem-layout-observation-294/`。正式patch已提交，重复LF导出中间件已清，原始before/source.diff/REPORT保留。

建议优先补足当前目标的部署文件系统存储，将实际available提高到至少8GiB作为下一轮容量规划起点，再由Agent按真实源码需求和每FS准入生成配额并复核隔离；8GiB不是已验证build峰值或成功保证。本轮不购买资源、不执行虚拟盘/分区/文件系统扩容、迁移或格式化。若由用户选择扩容，应先由现有云平台/运维流程完成其范围、费用、快照和扩展步骤的确认；产品仍须对其承担的每个实际动作独立APP和新grant。仅有虚拟盘变大不等于文件系统已可用，继续前还需原生只读确认真实容量。

若维持现资源，也可以明确选择继续研究新scope内的替代构建方式，但完整标准Go发行档无法直接展开进当前192MiB backend；SDK裁剪、其它发行、内存构建或产品原生本地构建均未验证，不能假定成功或绕过配额/隔离。没有修改版本、保留量、既有物理配额、STATIC/ASSISTED原生流程或共享SDK安装规则。

当前等待容量范围选择，不启动新FULL。194/285/原289/新291已结清不恢复，旧238/280/281/282/283/284/287保UNKNOWN。File Transfer完整验收0、CSV未开始；严格串行完成两者BUSINESS v2、真实浏览器及STOP/DISABLE后再暂停和给最终指标的原目标不变。
