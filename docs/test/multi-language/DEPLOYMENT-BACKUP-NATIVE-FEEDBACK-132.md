# 132：只读盘点参数错误的原生反馈

131 的完整 32-call 批次因对象身份或 page 参数非法，在零 dispatch 后直接终止，主模型未获得修正请求的回执。仅修改 BACKUP_ONLY fixture：仍先验证整个批次，对完整且唯一 callId 的参数错误返回每个原 callId 的 error ToolResult，保存原生历史和 partial，再由模型自主提出新请求。未知 ID 与非零 action page 的未来拒因拆分；不追补 131 原参数，不自动修改或重放任何请求。身份重复、不完整或越界仍终止。

所有收到的调用（包括拒绝）计入 512 上限；相同参数 SHA/拒因连续三次无进展时有界终止。执行期错误与纯校验拒绝分开，不能将已执行的前项冒称零 dispatch。生产、共享单结果适配器、独立 APP、三部署模式、配置和数据库版本未改，未放宽动作安全条件。

仅扩展并运行直接受影响的 `DeploymentStaticLiveAcceptanceTest#backupInventoryAcceptsNativeReadOnlyBatchesAndRetainsPartialMetadata`，test-02 实际 1 PASS、0 FAIL、0 ERROR、0 SKIP，0.315 秒；覆盖非法整批零回调、原完整错误回执和历史、其后新合法七调用、重复身份及含拒绝的预算。test-01 为新本地摘要签名的受检异常编译错误，0 实际测试，原证保留；改用既有无受检异常 hash 后只重跑同一方法。format-02 sole-owner write/check、package-01 显式 skipTests 均通过，无整类或全套测试。

源码清单 3299 项，仅 fixture owner 改动；final 与实际测试、打包前后完全一致。deployment.jar SHA `847fc903f3aca5f76625f23def79811e505fbfa066bd95ce68a5c3c1447a5243`，1048 个 class、113 个资源与当前字节一致。正常非管理员 CIM 核实 47 组自有 PID/创建时刻全部退出，自有 TMP 已清。本阶段无 Runtime、DB、provider、SSH 或导出。

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-native-feedback-132/`。父核原报告/日志 SHA 和 [精确增量补丁](patches/BACKUP-NATIVE-FEEDBACK-132.patch) 正反向原字节，只隔离任务改动本地提交，不推送。

针对实际阻塞的 Astra High 判断确认：用户要求部署自主闭环，并未要求重装保全的每次 SDK list/stat 都由模型选 ID。Root 可在真实 BACKUP_ONLY task、指纹、布局和原 SDK 边界内做有界只读盘点，依据实际证据冻结必要清单；这不授权部署、清理或绕过下载独立 APP/new grant/回执。后续不准入 133 付费模型盘点草稿；转用最小 SDK 元数据路线。备份、秘密保护、SQLite 一致性及必要范围覆盖仍未完成，不能暂停或提示可重装。
