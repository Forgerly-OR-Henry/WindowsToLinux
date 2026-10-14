# 166：评审副本先呈现完整动作

165 已确认评审输入含完整内容哈希实现，但 `Map.of` 的跨 JVM 字段顺序可将约 438k 字符的八份来源文档放在动作之前，实际哈希语句位于输入后部；原 policy 也未明确区分执行前与执行后 SHA。这是确定的布局和时序问题，不能据此推断模型 DENY 的唯一原因或保证新审批通过；原 DENY/UNKNOWN 保留。

仅 fixture 的评审副本改用 ObjectNode：顶层依次为 authorActionFacts、mechanicalValidation、完整 observationEvidence；author 内 commandRequest 第一，request 内完整 script 第一、stdin 第二，其余既有字段全部保留。原 author facts、动作 binding 生成、script/stdin 字节和八份文档内容不变，评审请求仍单独取 SHA。policy 一般性说明执行前独立审完整实现与已取得事实；保存载荷时逐文件稳定读取、对进入归档的同一批字节计算 SHA 并写入 manifest；执行后取得真实摘要与回执并核验，缺证不能完成。不预判当前程序安全，不命令 APPROVE，DENY 零 grant/dispatch 和既有来源门禁不变。

只运行原 `backupCommandPlansBindFullRequestsAndPreserveUnknownReceipts`：1PASS/0SKIP、5.822 秒，Maven0/freeze0；覆盖实际 native-call USER 序列化前缀和字段顺序、原 author facts/binding 不变、完整八文档、完整 script/stdin、时序 criteria 及 DENY 不 dispatch。soleowner 格式 write/check、显式 `-DskipTests package` 均通过，无失败轮；不重跑已过 snapshot 或全类/全量。3299 项仅 fixture 改变，最终字节等于定点及打包版本；owner SHA `053e06b42eceaa555bf898426d16d29dee4ac7a611a3d62634d3a45104e9d8cb`，JAR SHA `3dbc73cbc00ddfd9d2fbfd618490ecdcd8911bf3cf249d82b62d0bf0bfd2450b`，1048 个 class/113 份源资源逐字节匹配。原 log/XML/TXT SHA 已核，正常 CIM 核 27 组自有 PID/创建时刻全退，自有 tmp 已清。

[精确增量补丁](patches/BACKUP-REVIEW-ACTION-ORDER-166.patch)只含相对 164 最终 owner 原字节的本轮差分，纯 Python 正反应用均重建原字节。原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-review-action-order-166/`；父已实际 Git apply 正反复核 before/final 原字节，临时验证库已清；不整文件暂存混合源码，不修改旧证。本轮无实际 provider、SSH、DPAPI、原库连接或真实导出；167 使用真实全部输入的 source-only 序列化预检已 PASS：review 490530 UTF-8 字节，实际受控请求 532182 字节，内容 hash 语句位置 9532（前 15000 内）。原八份 JSON、script/stdin、author 节点完整相等；受控 NativeAiFixture 恰一请求，USER 全文与保存请求相等，正常两组自有进程已退、tmp 已清。受控 DENY 不是真实 APP；新 APP/远端执行仍待，不能据本地通过声明必要保全完成。
