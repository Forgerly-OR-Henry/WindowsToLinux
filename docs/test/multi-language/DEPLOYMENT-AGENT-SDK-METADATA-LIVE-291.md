# SDK 元数据观察 291：审批拒绝，未执行

本地 fixture 交付为 `e7971611eea79c8a47fe8c4c4886cfc88206fb74`。单次实机 selector 为 `DeploymentStaticLiveAcceptanceTest#observesExistingSdkMetadataThroughNativeApproval`，不是 FULL、SDK 执行或业务验收。

实际 Maven exit=1、JUnit 1F/0E/0skip/23.68s，UTC 2026-10-17T16:54:04.9510924Z—16:54:33.8824654Z。新任务 `c883e775-97d2-4e67-bcf9-982f115b10ab`；原 289 仍为此前已归档的失败任务，本次没有恢复原 289。

独立 APP 拒绝：Python 只读观察器本身可审，但通用 ModelCommandRunner 的完整封套在缺失时可能补建 `/etc/systemd/system/windowstolinux-workspace-recovery.service`，超出该次元数据读取声明。拒绝后没有 grant 或命令派发，未取得任何真实 SDK 元数据；不得宣称 SDK 存在、版本兼容或 READY。

失败保留为新观察任务 UNKNOWN。固定 existing-only、H2 ACCESS_MODE_DATA=r 的 bare JDBC reader 实际 javac/read 均 0，不启动 Runtime/TaskContext，不调用模型、SSH、恢复或写 SQL。其原始检查点证明：唯一 operation `9781a055-464c-4fb1-a5b4-b4e8781d7f9a`、body `c6dba5ba7a7484dc924b89d4b9e679a6caee2982b96364f80c8c1f8e6062625b`、60 秒、phase=INTENT、checkpoint 已释放；没有 DISPATCHED。执行器仅在取得 grant 后、execute 前持久化 DISPATCHED，结合零 SDK_METADATA_SEND，说明此观察未执行。49 个资源中 46 已释放，仅 MOD/SCHEMA/SERVER 三项保留；未据此手工结清。

完整脱敏拒因保存于本地证据 `file-transfer-sdk-metadata-observation-291/complete-persisted-denial-reason.json`，不是 512 字符失败摘要。reader 保留读取前、锁内和关闭后数据库 pair 属性一致证据，无数据库或 CP 哈希扫描。

本次纯观察的三次物理调用均为 APP：input=46,115、output=1,808、known IO=47,923、cache hit=30,720、miss=15,395；已知 IO 审批占比 100%，输入加权缓存命中率 66.6160685243%。三次 IO/cache 已知，reasoning 及完整 provider 账单未知。此数不计为成功业务验收指标，也不与此前失败 FULL 或恢复混合。

没有新 FULL、SDK PROBE/prepare/install/build、候选、发布、浏览器或 CSV。已退出本次 Java；仅清理本次观察的 owner 标记临时目录，保留 Runtime、原始日志、检查点及拒因。后续选择现有主机观测入口的新纯命令，仍保真实公开源码、完整独立 APP 与新 grant；不重放被拒封套、不用标签假冒固定 baseline、不修改 STATIC/ASSISTED 原生流程。新 UNKNOWN 观察任务须经原生、原任务限定的核对后收尾，不能强制写已知结论。

最终目标保持 File Transfer 完整 BUSINESS v2、真实浏览器、STOP/DISABLE → CSV Inspector 同样验收 → 暂停并报告指标。旧七个 UNKNOWN 保留，194、285、289 不恢复。当前两个项目仍均未完整通过。
