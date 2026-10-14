# 155：旧快照 proof 的 workflow 元数据 JSON 字段顺序

151 实际下载轮为 1FAIL、0ERROR、0SKIP，21.823 秒，Maven1/freeze0；Task `aa905e4b-4efc-4873-abcd-174a076f95ee` 保持 UNKNOWN。已观察到两次必要能力 probe，未观察到 APP generation、批次审批或 transfer；probe 用量及完整账单 UNKNOWN，不能据此声称零费用。原失败报告、已认领目录的准备元数据和远端快照均保留。151 原异常不能定位首个内部失败 guard，因此不能断言下述离线发现就是该轮首因。

父使用原 148 六份引用单独调用 proof 校验，确认合法旧 proof 会被 JSON 字符串字段顺序拒绝。离线 facts 比较唯一差异为 `/reviewedWorkflow/exactAction`：它是工具生成的 workflow 元数据 JSON 字符串，旧 `Map.of` 的字段迭代顺序跨 JVM 可变；严格解析后的结构和值相同，字符串顺序不同。这项已复现的本地 proof 缺陷不否定原 148 快照、APP 或 grant 的成功回执，也不证明 151 已到达该校验点。

本次仅修改验收 fixture：未来 workflow 元数据改用 `LinkedHashMap` 固定七个字段的写入顺序；读取旧 proof 时，只在比较用的深拷贝中，将上述受控字符串严格解析为 JSON 对象，要求七个固定字段完整，重复键、尾随 JSON、增减字段或值变化均拒绝。其余 facts 仍全等比较，作者 exactIntent、命令 script/stdin 仍保持精确字符串绑定。原 facts 不修改，原 inputDigest/bindingSha256 仍按原存储顺序计算，不改旧原件、hash 或 grant，也不结清 151 UNKNOWN。

仅运行两个直接相关方法：`backupSnapshotBundlesRequireOriginalReceiptsAndExactDatabaseMembers` 为 1PASS/0SKIP、0.996 秒，覆盖旧持久化字段重排正例、原件不变及严格反例；`backupCommandPlansBindFullRequestsAndPreserveUnknownReceipts` 为 1PASS/0SKIP、5.087 秒，覆盖共同 workflow scope/grant 路径。两次 Maven0/freeze0，原 XML/TXT SHA 已核，无测试失败轮、未跑全类或全量。另使用原 148 六份引用及原 151 选择清单执行 source-only 离线 proof 校验，exit0/freeze0，13 份原非秘密元数据 SHA 前后不变；该检查无真实目标 Runtime、provider、SSH、DPAPI 或原库连接，不是 151 实际下载重跑。

soleowner 格式 write/check 与显式 `-DskipTests package` 均通过。3299 项源码仅 fixture 改变，最终字节等于两次定点及打包版本；owner SHA `6a1af06a75e540faf1d3f1ce5d4dd3aa2d88e582b7eaadd8d8181de0b34e478e`，JAR SHA `d41b1dbe1b55fa3260ddf46e648bdb8a527b08ad42aacb9d788a211c6e8c254f`，1048 个 class 与当前编译输出、113 份源资源逐字节匹配。正常 CIM 核 42 组自有 PID/创建时刻全退，自有 tmp 已精确清理。

[精确增量补丁](patches/WORKFLOW-JSON-ORDER-155.patch)以保留的 154 原 owner 字节为基线；纯 Python 正向和反向应用均重建原字节，未执行 Git apply 或修改索引。原验证与补丁校验记录位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/snapshot-workflow-json-order-155/`，151 原失败与父离线定位分别保留在 `selective-snapshot-download-151/sealed-failed-download.json`、`actual-proof-differences-offline.log`。后续真实下载仍需新 Task、完整独立 APP、exactIntent 新 grant、原 SDK 回执、受保护落盘及恢复校验；本阶段未导回真实业务载荷，不能据此声明必要保全完成或可重装。
