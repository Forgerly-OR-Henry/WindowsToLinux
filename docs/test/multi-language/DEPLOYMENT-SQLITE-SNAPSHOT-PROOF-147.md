# 147：受保护下载只接纳有真实一致性证据的SQLite包

此前下载工具有意拒绝所有BUSINESS_DATA。本次仅为`SQLITE_SNAPSHOT_BUNDLE`增加窄分支：真实原producer plan、完整审批facts及APPROVE、grant、SDK原结果、Task观测六份文件逐SHA冻结，绑定相同plan/task/intent/grant；要求SDK SUCCEEDED、exitObserved、exit0、无截断、stderr空，Task ENDED/SUCCEEDED。核12个实际源dev/inode/nlink=1/bytes映射、原生在线backup方法、逐库integrity及快照SHA/大小、内部manifest及外包SHA/大小、限额及新私有归属，再与真实SFTP对象和盘读原字节SHA对应。旧SDK元数据原件要求、独立完整下载APP、新grant和DPAPI盘读反解后精确清plain均保留；原件在grant和dispatch前重核。

备份APP提示词区分必要载荷进入新自有私有备份与正文泄漏，允许审阅用户已授权的具体原生一致性备份；动作kind不是安全证明。旧资源删除、部署、未证明归属、未审链接及秘密正文输出仍拒绝。原生产API、部署默认三模式和旧UNKNOWN均未改，DENY仍停止。Task观测额外记录真实outcome，不能以ENDED单独认成功。

两个必要exact方法实际各1PASS、0SKIP：`backupSnapshotBundlesRequireOriginalReceiptsAndExactDatabaseMembers` 0.718秒，`protectedBackupBatchesBindExactTransfersAndRetainUnconfirmedPlaintext` 4.906秒。覆盖六原件缺失/伪回执/绑定或身份漂移、UNKNOWN、映射/成员/摘要/大小等反例。首次snapshot仅测试编译类型推断错误，方法未执行、0报告，原证保留；修正后只重失败方法和直接受影响保护方法。soleowner格式与显式`-DskipTests package`通过。

3299项仅fixture变，最终与两方法实测、打包源码同字节；JAR SHA `d9d70a1a8ad25e556fe7a5fc4febc49e86484c7a1fa029520209a6d6aeee1af4`，1048class/113源资源逐字节核对。正常CIM核58组自有PID/时刻全退，tmp清。父核原logs/XML/TXT SHA及[精确增量patch](patches/SQLITE-SNAPSHOT-PROOF-147.patch)正反原字节，patch SHA `57aece1b3bd3f41ff38ef14f3586e9c4d5d0e3d8549151ae755541183bde538c`；原mixed源码不整体stage。原证在`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-snapshot-proof-147/`。

本阶段无实际provider、SSH、原库、快照或下载，只证明本地准入工具。实际Linux在线导出、容量约束、受保护导回和业务恢复校验仍待，备份未完成。
