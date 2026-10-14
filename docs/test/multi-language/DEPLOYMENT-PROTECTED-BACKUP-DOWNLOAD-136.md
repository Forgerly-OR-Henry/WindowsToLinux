# 136：必要小件导回的本地保护工具验证

本轮只验证备份工具，没有连接服务器、调用模型、读取调查数据库或导回真实文件。必要备份仍未完成，不能据此重装或暂停。

下载沿用原产品 SDK transfer、明文 SHA 和准确的 staging 目的路径，每件取得新 grant 并核验真实 SDK 回执。独立审批接收冻结小批的全部具体动作、来源元数据、必要性、目的位置和保护计划；任一成员不安全则整批拒绝。每批最多48000序列化字符，不能截断为只有摘要，逐件再次核对批次、来源、配置及目的状态。三种部署入口和共享 SDK 未改。

新导回目录仅当前用户及 SYSTEM 可访问，禁继承并逐祖先检查重解析点。成功回执后的明文使用现成当前用户 DPAPI 加密，以 CREATE_NEW 写入磁盘，再从磁盘读回解密并校验长度和 SHA，最后精确删除本次已接管的明文。UNKNOWN、失败或未确认的 SDK 文件与 `.part` 不会被扫描清除；保护失败保留 incomplete 证据。此工具不声称介质安全擦除，也不抵抗同用户或管理员主动篡改。

当前只接受关键回执、归属 manifest 和不可重建小源。BUSINESS_DATA、SQLite及其日志文件仍拒绝，等待真正一致性导出证明；不能用文件哈希替代一致性。下载最多256件、单件64MiB、明文总计256MiB，密文和临时峰值另受界限约束。必要数据库、配置和覆盖核查仍须继续。

同时为未来 SDK LINK 元数据保留最多4096 UTF-8字节的原目标文本、摘要和完整性状态，始终不跟随链接，不修改135旧证据，也不把 LINK 目标变成授权路径。

实际定点验证共三个方法，分别独立执行，没有运行整类或全套测试：

| 方法 | 结果 | 时间 |
|---|---|---:|
| protectedBackupBatchesBindExactTransfersAndRetainUnconfirmedPlaintext | 1 PASS，0 SKIP | 0.240秒 |
| backupMetadataPlansBindObservedDirectoriesAndPreservePendingCoverage | 1 PASS，0 SKIP | 1.922秒 |
| protectedBackupPublicSentinelUsesCurrentUserDpapiAndInheritedDacl | 1 PASS，0 SKIP | 0.272秒 |

第三项在正常非管理员 Windows 环境实际检查当前 token、受保护 DACL、普通文件继承、DPAPI和磁盘往返，只使用公开测试字节，没有冒充真实 SDK transfer 回执。首次 protected 方法因 Windows JDK 的 `BasicFileAttributes.fileKey()` 为 null 而 ERROR；修正为现成 JNA `GetFileInformationByHandleEx(FileIdInfo)` 的卷号与128位文件ID后，仅重跑失败方法及直接受影响方法。原失败报告保留。

sole-owner格式写入和检查、显式 `-DskipTests package` 均通过。源码3299项仅验收 fixture 改动，最终字节与三个实际通过方法一致。父核原 log/XML/TXT SHA，并验证[精确源码增量补丁](patches/PROTECTED-BACKUP-DOWNLOAD-136.patch)正反应用后恢复原始字节；保留原迁移和其它未提交改动，不暂存整份混合源码。deployment.jar SHA `e319e5b93c650865e7887818c82c41186a655403ad771541a82ebd37dc72c44d`。

正常CIM核验70组本轮进程PID及创建时刻全部退出，未终止其它进程；自有 sentinel、验证临时目录及父补丁临时目录已按精确边界和无重解析点检查清理。原配置、调查数据库和旧UNKNOWN均保留。原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/protected-backup-download-136/`。

后续先通过备份专用产品命令入口进行完整独立审批下的有界只读调查，再依据真实归属调用现有窄 handoff 数据库导出；不默认完整 collect，避免强制生成所有 release/OCI。137逐目录草稿未运行。所有必要文件导回、保护、业务数据恢复检查与收尾完成后，才发可重装交接信号。
