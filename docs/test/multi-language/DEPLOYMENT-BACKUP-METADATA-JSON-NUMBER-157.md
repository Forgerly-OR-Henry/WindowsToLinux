# 157：备份元数据的 JSON 整数表示

下载准入将当前 `FileInfo.bytes` 的 Java `long` 经 `valueToTree` 转为 LongNode，再与磁盘 JSON 读取的小整数 IntNode 做树相等比较；例如同为 757760 字节也会被拒绝。此本地缺陷发生在 proof、正文读取和 APP 之前。156 原实际轮为 1FAIL、0ERROR、0SKIP，22.256 秒，Maven1/freeze0，未观察到 APP generation、批次审批或 transfer，Task `04fa69cb-68ea-4b1c-8197-2f6e37500dca` 保持 UNKNOWN。原运行没有内部 guard 栈帧，不能据离线复现断言其首个失败位置；151/156 原证与 UNKNOWN 均不改写。

本次仅修改验收 fixture：当前元数据先经过 JSON 写读，再与已持久化对象完整相等比较，使相同 JSON 整数不因 Java 节点宽度误拒；下载前 source 比较和后续必要性重核复用同一窄 helper。字段集合、identity/path/modified/kind/links、字节值及数值类型仍严格匹配，预算和审批绑定不放松；作者 script/stdin/exactIntent 不做规范化。后续 intent 事实仅含字符串及其集合，不存在同类整数宽度转换，未改该路径。任务内部异常新增 `backup-exception.json`，仅保存异常类名和最多 64 个栈帧的类名、方法名、行号，不保存 message、cause 正文或源文件名；记录失败也重新抛出原异常，不将 UNKNOWN 转成成功或自动重放。

只运行直接相关的 `protectedBackupBatchesBindExactTransfersAndRetainUnconfirmedPlaintext`：1PASS、0SKIP，5.029 秒，Maven0/freeze0。新增真实受控 FileInfo→磁盘 JSON→读取比较，覆盖 757760L、0L、大于 int 范围及 Long.MAX_VALUE 正例；字节错值、字符串/浮点替换、身份/路径/时间/种类/links、增减字段和嵌套必要性错值均拒绝，并核安全异常记录有界且不含测试正文。原 scope/grant 与未确认明文保留场景仍在同一方法中通过。无失败轮、未跑全类或全量；没有真实目标 Runtime、provider、SSH、DPAPI、原库连接或业务导回。

soleowner 格式 write/check 与显式 `-DskipTests package` 均通过。3299 项源码仅 fixture 改变，最终字节等于定点及打包版本；owner SHA `6dd34724957c8edf2575dc582a18f4fab95e8fede4af9e29ee817d3bbe1678a3`，JAR SHA `3d218d266bd4a36cdf0e74e1113fe6b7192bb1845f8cdef0f1c0e275ae342233`，1048 个 class、113 份源资源逐字节匹配。原 log/XML/TXT SHA 已核；正常 CIM 核 27 组自有 PID/创建时刻全退，自有 tmp 精确清理。

[精确增量补丁](patches/METADATA-JSON-NUMBER-157.patch)基于保留的 155 最终 owner 原字节，纯 Python 正反应用均重建原字节，未执行 Git apply 或修改索引。原证与材料校验在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-metadata-json-number-157/`；155 文档和补丁保持原样。后续仍需新 Task、完整独立 APP、exactIntent 新 grant、原 SDK 下载回执、受保护落盘及恢复校验；本阶段不能声明必要保全完成或可重装。
