# 标准 Go 与当前部署文件系统的实机只读观察 293

代码交付为466a6b1401e5d0dd7c24c2144748ad0301edf782，本地定点1P，未重新打包生产JAR。实机system-go-capacity-01/session14555实际exit0，JUnit1P/28.96s，Maven33.363s；UTC 2026-10-17T17:36:36.5502395Z—2026-10-17T17:37:10.7472885Z。

新Task 05b481d0-fe1b-42bc-b4a6-4ca855f43b05先核292成功归档，读取当前Task真实captured layout。独立原生APP动作1、新精确grant 8932acaf-2281-4c0f-a3bf-1b4d842b396f、实际命令1；完整脚本和空stdin经过SourceBrowser、AgentApproval和ReviewRouter，由同一session执行。零exit、空stderr、未截断、唯一严格结果帧。没有SDK执行、PROBE、安装、ModelCommandRunner envelope、远端receipt查询或布局/服务写入。

四个标准Go候选路径`/usr/local/bin/go`、`/usr/bin/go`、`/bin/go`、`/usr/local/go/bin/go`均为ABSENT_AT_RESOLVED_PATH。结合292受管versions无Go slot，只能排除已查标准路径和受管记录；没有证明所有自定义位置均无Go，也没有建立任何SDK READY。

当前五个真实目录均存在：helper `/usr/local/lib/windowstolinux`、applications `/opt/windowstolinux/apps`、state `/var/lib/windowstolinux`、configuration `/etc/opt/windowstolinux/apps`、data `/var/opt/windowstolinux/apps`。它们同属device 0x000000000000fd01（253:1）、fsid 0xa757ef6032008aae；frsize4096、两次bavail均462182、两次可用量均1,893,097,472B，约1.76GiB。这是两点观察，不是预留或未来容量保证。

按当前1GiB保留、64MiB附加量及双份候选计费，候选quota总和在64MiB粒度内最多320MiB；web源码下限128MiB时backend最多192MiB。[官方Go1.24.0 Linux amd64完整归档](https://go.dev/dl/go1.24.0.linux-amd64.tar.gz)普通文件逻辑252,216,477B、4KiB按文件上取整292,532,224B，尚不含目录、依赖、缓存和临时文件。该结果排除在当前192MiB backend内直接展开完整标准SDK的方案，不证明所有裁剪、其它发行或外部构建方法均不可行，也不等于完整构建峰值。归档统计及[官方摘要来源](https://go.dev/dl/?mode=json&include=all)详见代码交付说明与official-go-archive-size.json。

六次物理APP调用input87512、output1699、已知IO89211、cache hit73216、miss14296；已知IO审批占比100%，输入加权缓存命中率83.6639546576%。全部IO/cache已知，六次reasoning未知，完整provider账单未知；没有额外记账调用。这些是只读诊断指标，不能当作File Transfer/CSV成功验收指标，也不与失败FULL或恢复混算。

CIM自有Java0、terminated0；检查owner、绝对边界和no-reparse后清s293a/u293s。Runtime、原始日志和元数据保留于`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-system-go-capacity-293/`。此前代码before和REPORT另在file-transfer-system-go-capacity-observation-293，不混用。

下一步仅补充当前根设备、文件系统和分区布局的只读证据，先形成具体容量方案；没有授权扩大分区/文件系统、迁移布局、格式化、购买资源或清理旧七UNKNOWN。共享SDK安装仍受原隔离要求禁止。不启动新FULL，不降低版本或验收门槛。194/285/原289及新291已结清不恢复；旧238/280/281/282/283/284/287保UNKNOWN。File Transfer仍未完整通过、CSV未开始；严格串行完成两个BUSINESS v2、真实浏览器及STOP/DISABLE后暂停汇报的目标不变。
