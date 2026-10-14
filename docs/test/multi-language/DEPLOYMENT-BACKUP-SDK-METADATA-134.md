# 134：重装保全的产品 SDK 只读盘点

用户授权选择性导回必要数据、暂不部署。针对131的实际工具参数阻塞，本轮增加独立 `inventorySelectiveBackupMetadataThroughProduct` fixture 路线：精确 selector 与 `managed.backup.metadataOnly=true` 准入，真实新 BACKUP_ONLY task、产品受保护目标、实际 SSH 指纹和捕获布局保持；只用既有 FileQueryService stat/list。不配置或探测模型，不调用 LANGUAGE/APP，不进入部署流程。原模型盘点、下载和三种生产部署模式未改变。

父封定 plan 的 SHA/run、原 metadata 文件 SHA、task/目标/指纹/布局及实际观测目录；Runtime 前拒绝未知目录、文件冒充目录、重复、越界、非严格 UTF-8/JSON、超长或 reparse。执行时重新 stat/list，以新事实记录当前目录，不冒称旧 mtime 为当前观测。最多128目录LIST，stat另限128目录加5根并去重，最多4096对象、深度0至3；逐项保存原始观测、回执、失败和未遍历 pending。链接、特殊项、失败、上限均保留未决。正常完成只证明本计划元数据已取得，selected为空、coverage UNKNOWN、backupComplete false。

唯一新增本地方法 `DeploymentStaticLiveAcceptanceTest#backupMetadataPlansBindObservedDirectoriesAndPreservePendingCoverage`，test-01 实际 1 PASS、0 FAIL、0 ERROR、0 SKIP，1.885秒。验证原 plan/观测绑定、篡改、未知路径、深度/数量/字节/UTF-8/边界和真实 junction 拒绝；fake只读查询覆盖新 mtime、partial/pending、链接/特殊项、列表失败及4096对象与128 LIST/133 stat上限。这些为本地受控场景，不能冒称远端盘点通过。

format-01仅owner write/check与package-01显式skipTests通过，未跑整类或全套、未重复此前通过方法。源码3299项仅fixture owner改变，final等于实测及打包前后字节。deployment.jar SHA `7392678c5f84dc796ec491fd1f648778934abbb8eb0a90f97db0b093423b6d1e`，1048class/113资源与当前编译/源码字节一致。正常非管理员CIM核实33组自有PID/创建时刻全退，自有TMP已清。本阶段无实际Runtime、DB、provider、SSH、DPAPI或导出。

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-sdk-metadata-134/`。父核原日志/报告SHA、最终实测源码和 [精确增量补丁](patches/BACKUP-SDK-METADATA-134.patch) 的正反向原字节，仅隔离任务改动本地提交，不推送。

后续135仅SDK盘点待准入。Root根据真实观测与已有证据冻结必要保全清单，不以名称或未遍历项作排除证明；下载仍完整独立APP审核、新grant和SDK回执。必要数据导回、敏感保护、SQLite一致性、覆盖和收尾均待，不能暂停或提示可重装。
