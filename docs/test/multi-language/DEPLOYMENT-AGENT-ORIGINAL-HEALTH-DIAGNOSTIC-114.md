# 原58健康绑定停止事件的只读核对

2026-10-14，仅读取113原任务 `4e3c7265-9e4c-4152-87d0-e3009c1630cd` 的持久诊断，固定UTC窗口10:30:03至10:30:34。未开启Runtime、SSH或provider。原状态目录为 `agent-main-only-diagnostic-live-task-board-30lc0aveo`，实际数据库在其 `data/` 子目录，schema保持version1。

首次辅助入口read-01因错误地把状态根当成数据库目录，被 `EXACT_ORIGINAL_TOP_LEVEL_REJECT` 在连接前拒绝，没有启动reader、DPAPI或数据库连接。原准备、compile-01与拒绝记录保留。只修正辅助类DATA路径及新轮目录，不修改产品源码；compile-02单辅助javac退出0，不执行Maven或业务测试。

read-02实际18:40:35.2318980至18:40:36.2074630（UTC+8），唯一只读连接退出0、stderr为空。官方LogStore目标与LogRecordCodec验证后的本轮ERROR为 `DEPLOYMENT_AGENT_FAILURE`，正文为 `ApiException: Original Agent health evidence is unconfirmed [UNKNOWN/DEPLOYMENT_AGENT_HEALTH_ORIGINAL_BINDING_CHANGED]`。原平台状态仍UNKNOWN、outcome未设置、progress为AI_RECOVERY_COMMAND_RECEIPTS_SETTLED。本轮时间窗内没有新增Agent事件；此结论仅限指定窗口，不代表原任务没有历史动作。

已定位到Agent健康原始绑定校验，尚未证明具体哪个子条件不符。异常的内部cause未进入已有诊断正文，不能由这个统一事件断言审批过严或主模型闭环失败。platform result的辅助context投影覆盖了同一map中的顶层投影，不能把空allowedDetails当作无问题；独立deployment日志code和正文保留且可用。没有为补齐这个辅助字段重开原库。

原MDB、db-access、lock字节与顶层名单前后相等；work仅验证为普通目录，内容不遍历。原排他lock、只读连接均已关闭。父普通用户CIM正向枚举确认自身，并核compile-01、compile-02、read-02六组PID/创建时间均已退出。无原数据写入、恢复、删除、模型调用或新增服务器操作。

证据目录：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original58-stop-diagnostic-114/`。prepared-v1保存原件；read-01保留连接前拒绝；read-02保存安全projection stdout、原文件指纹、stderr及closure；manifest固定辅助类、源码和依赖SHA，parent-process-closure记录退出核验。后续只针对已确认的绑定停止点排查，原UNKNOWN、App、资源和调查证据保持留存。本次不能算原恢复成功或task-board完整独立审批部署通过。
