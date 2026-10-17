# Agent 标准 Go 及已抓取路径容量的纯观察 293

292实机证明受管versions目录没有Go slot，只有Node24.22.0声明记录；不证明系统完全无Go。本次继续用一次纯读取补充标准系统Go及当前实际FS容量事实，不开FULL、不先实现没有可选Go记录的复用控制器，不另起maintenance绕过原隔离要求对共享安装的禁止。

只新增三个测试路径：Live独选入口、公开system-go-capacity-observation.py、MetadataTest一个新方法；不改生产代码、STATIC/ASSISTED原生流程或291/292已过方法。新Task先核292观察SUCCEEDED归档，用当前配置显式capture并读回原task layout，取得helper/apps/state/config/data五root。公开observer真实snapshot/SourceBrowser绑定准确路径、layout、hostkey和task，经原生AgentApproval/ReviewRouter，以新精确grant对同一request/session执行唯一命令。完整script、空stdin、固定60秒/65536B；确定同task的APP DENY返回FAILED并保完整脱敏理由，执行未知不改写成已知。

Python只观察四标准Go候选路径，fd-relative/root属主/可信nonwritable parent/目录及文件，有限16链接、循环/17hop/身份漂移拒绝；symlink0777不当作ACL授权。只读关联home/VERSION的最多1024B单链接普通文件，nofollow/非阻塞/前后身份绑定，投影goX.Y[.Z]首行，隐藏其余正文；不读或执行Go二进制。未知不当作缺失。

容量只来自五个真实captured roots的可信目录fd，缺root明确记录最近实存ancestor，不把其容量当未来mount证明。device/fsid为unsigned64 hex；frsize/bavail/乘法结果严格非负signed-long。保存两点及min，核FS/物理身份、不要求动态free恒定，min不是预留或未来保证。结果唯一严格SYSTEM_GO_CAPACITY_METADATA帧、exit0/空stderr/未截断，METADATA_ONLY不证明SDK执行版本、完整性、兼容或READY，不授权改layout/门槛/共享安装。

唯一local selector `DeploymentSdkMetadataObservationTest#systemGoAndCapacityObserverBoundsLinksVersionAndFilesystemEvidence`，test01/session32055实际exit0，1P/0F/0E/0skip/1.539s，Maven13.836s，UTC 2026-10-17T17:34:20.0577238Z—2026-10-17T17:34:34.7469063Z。FakeOS覆盖标准alias/absence、16/17边界、循环、文件/链接漂移、VERSION singlelink及私密正文排除、缺root祖先、unsigned fsid、动态容量、溢出及未知。未访问Windows真实SDK，不声称Linuxsyscall已验证。仅test及必要testCompile，不重复旧组、不package/全量；13直接source窗口内属性不变，既有JAR属性未变。

父另独立流式统计[官方Go1.24.0 Linux amd64归档](https://go.dev/dl/go1.24.0.linux-amd64.tar.gz)，核[官方JSON摘要和压缩尺寸](https://go.dev/dl/?mode=json&include=all)：78,382,844B；14,150普通文件/1,555目录，普通逻辑252,216,477B、每文件4KiB上取整292,532,224B，不含目录/FS/模块/cache/tmp。只读流，不解包或执行、无目标SSH。这只排除上轮192MiB配额内直接展开完整标准SDK的方案，不证明其它发行/裁剪方式下界或实际build峰值。raw仅保存小JSON，未落归档或源码到磁盘。

三路径窄patch 454增0删/reverse check通过；完整before及REPORT在file-transfer-system-go-capacity-observation-293，父结果/disabled runner及归档尺寸在file-transfer-system-go-capacity-293。仅Git阶段计算3source+patch+本说明5项限定摘要，不提交迁移或AGENTS、不推送。实际APP与Linux观察尚未进行，后续另记。旧七UNKNOWN保，194/285/原289不恢复；FT→CSV各完整BUSINESSv2/浏览器/STOPDISABLE后暂停指标目标不变。
