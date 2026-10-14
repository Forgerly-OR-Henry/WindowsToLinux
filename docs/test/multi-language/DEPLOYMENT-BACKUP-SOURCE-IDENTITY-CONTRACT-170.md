# 170：下载审批明确 FileInfo identity 契约

FileInfo 的公开契约将 identity 定义为后端检测对象变化的依据，私有字符串布局不是大小、时间或权限字段的公开格式。原下载审批同时保留 typed source 和原 identity，却未明确这一区分。161 原 DENY 理由与 typed facts 不符，但模型内部原因仍未知，本次不推翻 DENY、不改写该 Task 的 UNKNOWN。

仅 fixture 提取原 batch review 调用并补固定 SYSTEM 契约：sourceIdentity 是 backend-specific opaque change-detection evidence，只保留原值和复查原 SDK 对象相等；不得从其私有布局推导大小、时间或权限。相应观测读取 typed source.bytes/source.modified，sourceSha256 来自实际字节；opaque identity 或元数据本身不证明内容完整。原完整 facts、identity 字符串、batch binding、ask/parse、criteria、预算、独立 APP、新 grant 和 SDK 路径保持原样，不加入本次案例的数字或结论，不强制 APPROVE。

只运行 `protectedBackupBatchesBindExactTransfersAndRetainUnconfirmedPlaintext`：1PASS/0SKIP、5.097 秒，Maven0/freeze0。NativeAiFixture 捕获实际 SYSTEM/完整 USER，核 typed 值、identity 原值及 SHA 全部保留，原 batch SHA 不变；仅身份变化的真实 FileInfo 被既有 metadata 门拒绝，DENY 零 dispatch/零 grant 消费，原假保护/UNKNOWN 明文保留场景仍通过。soleowner 格式 write/check 与显式 `-DskipTests package` 通过，无失败轮；未重跑 139、snapshot、全类或全量。3299 项源码仅 fixture 改变，最终字节等于实测及打包版本；owner SHA `914036bbaf0c5ceea8e46c2938bb57106cb8bb74a15c2f604904ab2d834aa950`，JAR SHA `34f38dfc33f247c717b7afbefc35737e2d87ac1e1daffdf7e286afcc8ef051ea`，1048 个 class/113 份源资源逐字节匹配。原 log/XML/TXT SHA 已核，正常 CIM 核 27 组自有 PID/创建时刻全退，自有 tmp 已清。

[精确增量补丁](patches/BACKUP-SOURCE-IDENTITY-CONTRACT-170.patch)仅含相对 166 最终 owner 原字节的本轮改动，纯 Python 正反应用重建原字节。父另以真实 Git apply 正反应用核对两端原字节，复核原日志、报告及 3299 项最终源码；临时验证库已按精确路径清除。仅该说明及补丁隔离提交，不暂存混合 fixture。原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-source-identity-contract-170/`。本轮无实际 provider、SSH、DPAPI、原库连接或真实下载；后续新任务须重新独立 APP/新 grant，不能据本地通过声明必要保全完成。
